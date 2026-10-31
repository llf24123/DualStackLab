package com.dualstack.lab;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.Arrays;

final class PagedTextFile {
    record Page(long start, long end, String text) {}

    static Page read(File file, long start, Charset charset, byte[] bom, int pageSize) throws IOException {
        if (TextCodec.isStateful(charset)) throw new IOException("有状态编码须先连续转换为 UTF-8 分页副本");
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            input.seek(start);
            byte[] data = new byte[(int) Math.min(pageSize, input.length() - start)];
            input.readFully(data);
            int skip = start == 0 && data.length >= bom.length
                    && Arrays.equals(Arrays.copyOf(data, bom.length), bom) ? bom.length : 0;
            TextCodec.Segment segment = TextCodec.decodeSegment(data, charset, skip, start + data.length < input.length(), true);
            return new Page(start, start + segment.consumed(), segment.text());
        }
    }

    static Page write(File input, File output, Page page, String text, Charset charset, byte[] bom) throws IOException {
        if (TextCodec.isStateful(charset)) throw new IOException("有状态编码须连续转码保存");
        byte[] replacement = TextCodec.encode(text, charset, page.start() == 0 ? bom : new byte[0]);
        try (InputStream source = new BufferedInputStream(new FileInputStream(input));
             OutputStream target = new BufferedOutputStream(new FileOutputStream(output))) {
            byte[] buffer = new byte[128 * 1024];
            long remaining = page.start();
            while (remaining > 0) {
                int n = source.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (n < 0) throw new EOFException("文件已在外部改变，请重新打开");
                target.write(buffer, 0, n); remaining -= n;
            }
            FileStore.skip(source, page.end() - page.start());
            target.write(replacement);
            FileStore.pump(source, target);
        }
        return new Page(page.start(), page.start() + replacement.length, text);
    }

    private PagedTextFile() {}
}
