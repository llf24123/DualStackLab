package com.dualstack.lab;

import android.content.Context;
import android.os.Build;
import android.system.Os;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.*;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Locale;

final class ExecutableFile {
    record Elf(int bits, int type, long entry, String abi, String interpreter) {
        boolean needsRoot() { return type != 3 || interpreter.isEmpty(); }
    }
    record Prepared(File file, String originalPath, Elf elf, String localPath, File launcher) implements AutoCloseable {
        String command(boolean root) {
            return command(root, false);
        }
        String command(boolean root, boolean pipe) {
            if (elf == null) {
                if (pipe) return "/system/bin/toybox cat " + FileStore.quote(file.getAbsolutePath())
                        + " | /system/bin/sh -c " + FileStore.quote(". /proc/self/fd/0")
                        + " " + FileStore.quote(originalPath);
                // Source through sh -c so $0 and relative resource paths retain the original location.
                return "/system/bin/sh -c " + FileStore.quote(". " + FileStore.quote(file.getAbsolutePath()))
                        + " " + FileStore.quote(originalPath);
            }
            String copy = (root || Build.VERSION.SDK_INT < 29 || elf.needsRoot() ? ""
                    : elf.bits == 64 ? "/system/bin/linker64 " : "/system/bin/linker ")
                    + FileStore.quote(file.getAbsolutePath());
            if (!root) return copy;
            return FileStore.quote(launcher.getAbsolutePath()) + " "
                    + FileStore.quote(localPath == null ? "" : localPath) + " " + FileStore.quote(file.getAbsolutePath());
        }
        String status() {
            return elf == null ? "脚本已就绪" : "ELF 已就绪 · " + elf.abi + (elf.needsRoot() ? " · 需 ROOT" : "");
        }
        @Override public void close() throws IOException {
            Files.deleteIfExists(file.toPath());
            if (launcher != null) Files.deleteIfExists(launcher.toPath());
            Files.deleteIfExists(file.getParentFile().toPath());
        }
    }

    static Prepared prepare(Context context, FileStore.Node node) throws Exception {
        return prepare(context, node, false);
    }
    static Prepared prepare(Context context, FileStore.Node node, boolean forceScript) throws Exception {
        if (node.directory) throw new IOException("请选择 .sh 或 ELF 文件");
        FileStore store = new FileStore(context);
        FileStore.checkName(node.name);
        File folder = Files.createTempDirectory(context.getCodeCacheDir().toPath(), "executable-").toFile();
        File staged = new File(folder, node.name);
        File launcher = null;
        try {
            boolean elf;
            try (BufferedInputStream input = new BufferedInputStream(store.read(node))) {
                input.mark(4);
                byte[] magic = FileStore.readBytes(input, 4); input.reset();
                elf = magic.length == 4 && magic[0] == 0x7f && magic[1] == 'E' && magic[2] == 'L' && magic[3] == 'F';
                if (!elf && !forceScript && !node.name.toLowerCase(Locale.ROOT).endsWith(".sh"))
                    throw new IOException("文件不是 ELF，也不是 .sh 脚本");
                try (OutputStream out = new BufferedOutputStream(new FileOutputStream(staged))) {
                    FileStore.pump(input, out);
                }
            }
            if (!elf) normalizeScript(staged);
            Elf info = elf ? inspect(staged) : null;
            if (info != null) {
                // Native bridge ABIs cannot launch standalone executables through the kernel linker.
                String primary = Build.SUPPORTED_ABIS[0];
                boolean sameFamily = primary.startsWith("x86") == info.abi.startsWith("x86");
                if (!sameFamily || !Arrays.asList(Build.SUPPORTED_ABIS).contains(info.abi))
                    throw new IOException("ELF 架构 " + info.abi + " 与设备不兼容（" + primary + "）");
                String expected = info.bits == 64 ? "linker64" : "linker";
                if (!info.interpreter.isEmpty() && !info.interpreter.equals("/system/bin/" + expected)
                        && !info.interpreter.equals("/apex/com.android.runtime/bin/" + expected))
                    throw new IOException("不兼容的 ELF 解释器：" + info.interpreter + "；需要 Android/Bionic 可执行文件");
                if (!info.needsRoot() && !new File("/system/bin/" + expected).canExecute())
                    throw new IOException("设备缺少 " + expected);
            }
            Os.chmod(staged.getAbsolutePath(), info == null ? 0400 : 0500);
            if (info != null) {
                launcher = File.createTempFile("launcher-", ".bin", folder);
                try (InputStream input = context.getAssets().open("executable-launcher/" + Build.SUPPORTED_ABIS[0]);
                     OutputStream output = new FileOutputStream(launcher)) { FileStore.pump(input, output); }
                Os.chmod(launcher.getAbsolutePath(), 0500);
            }
            String original = node.file == null ? node.name : node.file.getAbsolutePath();
            return new Prepared(staged, original, info,
                    node.file != null && node.container == null && node.uri == null ? node.file.getAbsolutePath() : null, launcher);
        } catch (Exception e) {
            try {
                Files.deleteIfExists(staged.toPath());
                if (launcher != null) Files.deleteIfExists(launcher.toPath());
                Files.deleteIfExists(folder.toPath());
            }
            catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }

    private static void normalizeScript(File staged) throws IOException {
        byte[] bytes;
        try (InputStream input = new FileInputStream(staged)) { bytes = FileStore.readBytes(input, 8192); }
        String unicode = null;
        int offset = 0;
        if (bytes.length >= 4 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe && bytes[2] == 0 && bytes[3] == 0)
            { unicode = "UTF-32LE"; offset = 4; }
        else if (bytes.length >= 4 && bytes[0] == 0 && bytes[1] == 0 && bytes[2] == (byte) 0xfe && bytes[3] == (byte) 0xff)
            { unicode = "UTF-32BE"; offset = 4; }
        else if (bytes.length >= 2 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe) unicode = "UTF-16LE";
        else if (bytes.length >= 2 && bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff) unicode = "UTF-16BE";
        if (unicode != null && offset == 0) offset = 2;
        boolean declaredUnicode = unicode != null;
        if (unicode == null && bytes.length >= 16 && (bytes[0] == 0 || bytes[1] == 0)) {
            try {
                TextCodec.Decoded decoded = TextCodec.decodePrefix(bytes, null);
                if (decoded.charset().name().startsWith("UTF-16") || decoded.charset().name().startsWith("UTF-32"))
                    unicode = decoded.charset().name();
            } catch (IOException ignored) { }
        }
        File normalized = null;
        try {
            if (unicode != null) {
                normalized = File.createTempFile("normalized-", ".sh", staged.getParentFile());
                try { transcodeScript(staged, normalized, Charset.forName(unicode), offset); }
                catch (IOException error) {
                    if (declaredUnicode || !(error instanceof CharacterCodingException)
                            && !(error instanceof BinaryScriptException)) throw error;
                    // A speculative Unicode match must not damage a script with a binary tail.
                    return;
                }
            } else {
                offset = bytes.length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb
                        && bytes[2] == (byte) 0xbf ? 3 : 0;
                boolean binary = false, cr = false;
                // Inspect the entire payload before changing any newline, including binary tails beyond 4 MiB.
                try (InputStream input = new BufferedInputStream(new FileInputStream(staged))) {
                    FileStore.skip(input, offset);
                    byte[] buffer = new byte[64 * 1024]; int count;
                    scan: while ((count = input.read(buffer)) != -1) {
                        for (int i = 0; i < count; i++) {
                            byte value = buffer[i];
                            if (value >= 0 && value < 32 && value != '\n' && value != '\r' && value != '\t' && value != 27) {
                                binary = true; break scan;
                            }
                            if (value == '\r') cr = true;
                        }
                    }
                }
                if (offset == 0 && (binary || !cr)) return;
                normalized = File.createTempFile("normalized-", ".sh", staged.getParentFile());
                try (InputStream input = new BufferedInputStream(new FileInputStream(staged));
                     OutputStream output = new BufferedOutputStream(new FileOutputStream(normalized))) {
                    FileStore.skip(input, offset);
                    if (binary || !cr) FileStore.pump(input, output);
                    else normalizeNewlines(input, output);
                }
            }
            FileStore.replace(normalized, staged);
        } finally {
            if (normalized != null) Files.deleteIfExists(normalized.toPath());
        }
    }

    private static final class BinaryScriptException extends IOException {
        BinaryScriptException() { super("Unicode 脚本包含二进制数据"); }
    }
    private static void transcodeScript(File source, File target, Charset charset, int offset) throws IOException {
        try (InputStream input = new BufferedInputStream(new FileInputStream(source));
             Writer writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(target),
                     StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                             .onUnmappableCharacter(CodingErrorAction.REPORT)))) {
            FileStore.skip(input, offset);
            try (Reader reader = new InputStreamReader(input, charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))) {
                char[] buffer = new char[32768]; int count; boolean previousCr = false;
                while ((count = reader.read(buffer)) != -1) {
                    int length = 0;
                    for (int i = 0; i < count; i++) {
                        char value = buffer[i];
                        if (value == 0) throw new BinaryScriptException();
                        if (value != '\n' || !previousCr) buffer[length++] = value == '\r' ? '\n' : value;
                        previousCr = value == '\r';
                    }
                    writer.write(buffer, 0, length);
                }
            }
        }
    }
    private static void normalizeNewlines(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[64 * 1024]; int count; boolean previousCr = false;
        while ((count = input.read(buffer)) != -1) {
            int length = 0;
            for (int i = 0; i < count; i++) {
                byte value = buffer[i];
                if (value != '\n' || !previousCr) buffer[length++] = value == '\r' ? (byte) '\n' : value;
                previousCr = value == '\r';
            }
            output.write(buffer, 0, length);
        }
    }

    static Elf inspect(File file) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            byte[] bytes = new byte[64];
            if (input.length() < 52) throw new IOException("ELF 文件头不完整");
            input.readFully(bytes, 0, (int) Math.min(64, input.length()));
            if (bytes[0] != 0x7f || bytes[1] != 'E' || bytes[2] != 'L' || bytes[3] != 'F'
                    || (bytes[4] != 1 && bytes[4] != 2) || bytes[5] != 1 || bytes[6] != 1)
                throw new IOException("无效或不支持的 ELF 文件头");
            boolean wide = bytes[4] == 2;
            if (wide && input.length() < 64) throw new IOException("ELF64 文件头不完整");
            ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int machine = Short.toUnsignedInt(header.getShort(18));
            String abi = switch (machine) {
                case 3 -> "x86"; case 62 -> "x86_64"; case 40 -> "armeabi-v7a"; case 183 -> "arm64-v8a";
                default -> throw new IOException("不支持的 ELF 架构：" + machine);
            };
            if (wide != (machine == 62 || machine == 183)) throw new IOException("ELF 位数与架构不一致");
            int type = Short.toUnsignedInt(header.getShort(16));
            long entry = wide ? header.getLong(24) : Integer.toUnsignedLong(header.getInt(24));
            if ((type != 2 && type != 3) || entry == 0) throw new IOException("该 ELF 不是可执行程序（可能是 .so 库）");
            long offset = wide ? header.getLong(32) : Integer.toUnsignedLong(header.getInt(28));
            int recordSize = Short.toUnsignedInt(header.getShort(wide ? 54 : 42));
            int count = Short.toUnsignedInt(header.getShort(wide ? 56 : 44));
            if (recordSize < (wide ? 56 : 32) || count == 0 || count > 1024 || offset < (wide ? 64 : 52)
                    || offset > input.length() || (long) recordSize * count > input.length() - offset)
                throw new IOException("ELF 程序头损坏");
            String interpreter = "";
            for (int i = 0; i < count; i++) {
                input.seek(offset + (long) i * recordSize);
                byte[] program = new byte[wide ? 56 : 32]; input.readFully(program);
                ByteBuffer segment = ByteBuffer.wrap(program).order(ByteOrder.LITTLE_ENDIAN);
                if (segment.getInt(0) != 3) continue;
                long start = wide ? segment.getLong(8) : Integer.toUnsignedLong(segment.getInt(4));
                long length = wide ? segment.getLong(32) : Integer.toUnsignedLong(segment.getInt(16));
                if (start < 0 || length < 2 || length > 4096 || start > input.length() || length > input.length() - start)
                    throw new IOException("ELF 解释器段损坏");
                byte[] name = new byte[(int) length]; input.seek(start); input.readFully(name);
                if (name[name.length - 1] != 0) throw new IOException("ELF 解释器路径损坏");
                interpreter = new String(name, 0, name.length - 1, StandardCharsets.US_ASCII);
            }
            return new Elf(wide ? 64 : 32, type, entry, abi, interpreter);
        }
    }
    private ExecutableFile() {}
}
