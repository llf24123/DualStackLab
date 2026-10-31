package com.dualstack.lab;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.Arrays;
import org.mozilla.universalchardet.UniversalDetector;

final class TextCodec {
    record Decoded(String text, Charset charset, byte[] bom, boolean uncertain) {}
    static Decoded decode(byte[] data, String requested) throws IOException {
        return decode(data, requested, false, false);
    }
    static Decoded decodePrefix(byte[] data, String requested) throws IOException {
        return decode(data, requested, true, false);
    }
    static Decoded decodeForEditor(byte[] data, String requested, boolean prefix) throws IOException {
        try { return decode(data, requested, prefix, true); }
        catch (IOException error) {
            if (requested != null) throw error;
            // A damaged BOM or mixed payload must remain editable without dropping original bytes.
            Decoded raw = decodeAs(data, StandardCharsets.ISO_8859_1, 0, prefix, true);
            return new Decoded(raw.text(), raw.charset(), raw.bom(), true);
        }
    }
    private static Decoded decode(byte[] data, String requested, boolean prefix, boolean allowControls) throws IOException {
        int skip = 0; Charset charset = requested == null ? null : Charset.forName(requested);
        if (data.length >= 4 && data[0] == (byte) 0xff && data[1] == (byte) 0xfe && data[2] == 0 && data[3] == 0) {
            skip = 4; if (charset == null) charset = Charset.forName("UTF-32LE");
        } else if (data.length >= 4 && data[0] == 0 && data[1] == 0 && data[2] == (byte) 0xfe && data[3] == (byte) 0xff) {
            skip = 4; if (charset == null) charset = Charset.forName("UTF-32BE");
        } else if (data.length >= 3 && data[0] == (byte) 0xef && data[1] == (byte) 0xbb && data[2] == (byte) 0xbf) {
            skip = 3; if (charset == null) charset = StandardCharsets.UTF_8;
        } else if (data.length >= 2 && data[0] == (byte) 0xff && data[1] == (byte) 0xfe) {
            skip = 2; if (charset == null) charset = StandardCharsets.UTF_16LE;
        } else if (data.length >= 2 && data[0] == (byte) 0xfe && data[1] == (byte) 0xff) {
            skip = 2; if (charset == null) charset = StandardCharsets.UTF_16BE;
        }
        if (charset == null && data.length >= 2) {
            int sampleEnd = Math.min(data.length, 4096);
            if (allowControls) while (sampleEnd > 0 && data[sampleEnd - 1] == 0) sampleEnd--;
            int[] zeros = new int[4]; int units = Math.min((sampleEnd + 3) / 4, data.length / 4);
            for (int i = 0; i < units * 4; i++) if (data[i] == 0) zeros[i % 4]++;
            if (units > 0 && zeros[2] > units * .8 && zeros[3] > units * .8 && (zeros[0] < units * .4 || zeros[1] < units * .4))
                charset = Charset.forName("UTF-32LE");
            else if (units > 0 && zeros[0] > units * .8 && zeros[1] > units * .8 && (zeros[2] < units * .4 || zeros[3] < units * .4))
                charset = Charset.forName("UTF-32BE");
            int even = 0, odd = 0, count = Math.min(data.length, (sampleEnd + 1) / 2 * 2);
            for (int i = 0; i < count; i++) if (data[i] == 0) { if ((i & 1) == 0) even++; else odd++; }
            if (charset == null && odd > count / 6.0 && even < count / 16.0) charset = StandardCharsets.UTF_16LE;
            else if (charset == null && even > count / 6.0 && odd < count / 16.0) charset = StandardCharsets.UTF_16BE;
        }
        if (charset != null) return decodeAs(data, charset, skip, prefix, allowControls);
        // Escape-based encodings contain valid ASCII bytes; detect them before UTF-8.
        UniversalDetector detector = new UniversalDetector();
        int sample = Math.min(data.length, 256 * 1024);
        detector.handleData(data, 0, sample); detector.dataEnd();
        String detected = detector.getDetectedCharset();
        if (detected != null && detected.toUpperCase(java.util.Locale.ROOT).startsWith("ISO-2022")) {
            try { return decodeAutomaticAs(data, Charset.forName(detected), prefix, allowControls); }
            catch (IOException | IllegalArgumentException ignored) {}
        }
        try { return decodeAutomaticAs(data, StandardCharsets.UTF_8, prefix, allowControls); } catch (IOException ignored) {}
        int highBytes = 0;
        for (int i = 0; i < sample; i++) if (data[i] < 0) highBytes++;
        // A few Chinese byte pairs can receive a misleading Cyrillic statistical score.
        // Prefer a valid Han decoding for an ambiguous short sample, and expose the uncertainty.
        if (highBytes <= 12 && highBytes >= 4 && !isEastAsian(detected)) {
            try {
                Decoded chinese = decodeAutomaticAs(data, Charset.forName("GB18030"), prefix, allowControls);
                long han = chinese.text().codePoints().filter(cp -> Character.UnicodeScript.of(cp)
                        == Character.UnicodeScript.HAN).count();
                long nonAscii = chinese.text().codePoints().filter(cp -> cp > 127).count();
                if (han >= 2 && han >= nonAscii * .8)
                    return new Decoded(chinese.text(), chinese.charset(), chinese.bom(), true);
            } catch (IOException ignored) { }
        }
        if (detected != null && !detected.equalsIgnoreCase("UTF-8")) {
            try {
                Decoded result = decodeAutomaticAs(data, Charset.forName(detected), prefix, allowControls);
                return new Decoded(result.text(), result.charset(), result.bom(), highBytes < 16);
            }
            catch (IOException | IllegalArgumentException ignored) {}
        }
        try { return decodeAutomaticAs(data, Charset.forName("GB18030"), prefix, allowControls); }
        catch (IOException ignored) {}
        // Unknown legacy bytes remain editable without replacing or discarding them.
        Decoded fallback = decodeAs(data, StandardCharsets.ISO_8859_1, 0, prefix, allowControls);
        return new Decoded(fallback.text(), fallback.charset(), fallback.bom(), true);
    }
    private static boolean isEastAsian(String name) {
        if (name == null) return false;
        String normalized = name.toUpperCase(java.util.Locale.ROOT);
        return normalized.startsWith("GB") || normalized.startsWith("BIG5") || normalized.startsWith("EUC-")
                || normalized.startsWith("SHIFT") || normalized.equals("WINDOWS-31J");
    }
    static boolean isStateful(Charset charset) {
        String name = charset.name().toUpperCase(java.util.Locale.ROOT);
        return name.startsWith("ISO-2022") || name.startsWith("HZ-");
    }
    private static Decoded decodeAs(byte[] data, Charset charset, int skip, boolean prefix, boolean allowControls) throws IOException {
        return new Decoded(decodeSegment(data, charset, skip, prefix, allowControls).text(), charset, Arrays.copyOf(data, skip), false);
    }
    private static Decoded decodeAutomaticAs(byte[] data, Charset charset, boolean prefix, boolean allowControls) throws IOException {
        Segment segment = decodeSegment(data, charset, 0, prefix, allowControls);
        byte[] restored = encode(segment.text(), charset, new byte[0]);
        // Stateful prefixes need not include the reset sequence emitted by a finished encoder.
        if (!(prefix && isStateful(charset)) && !Arrays.equals(restored, Arrays.copyOf(data, segment.consumed())))
            throw new IOException("此编码无法保留原文件字节");
        return new Decoded(segment.text(), charset, new byte[0], false);
    }
    record Segment(String text, int consumed) {}
    static Segment decodeSegment(byte[] data, Charset charset, int skip, boolean prefix) throws IOException {
        return decodeSegment(data, charset, skip, prefix, false);
    }
    static Segment decodeSegment(byte[] data, Charset charset, int skip, boolean prefix, boolean allowControls) throws IOException {
        CharacterCodingException failure = null;
        if (prefix) {
            CharsetDecoder decoder = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            CharBuffer output = CharBuffer.allocate((int) Math.ceil((data.length - skip) * decoder.maxCharsPerByte()) + 2);
            CoderResult result = decoder.decode(ByteBuffer.wrap(data, skip, data.length - skip), output, false);
            if (result.isError()) {
                try { result.throwException(); }
                catch (CharacterCodingException e) { throw new IOException("文件不符合 " + charset.name() + " 编码", e); }
            }
        }
        // Android's ICU decoder may consume an unfinished sequence on underflow.
        // Retain an incomplete multibyte sequence or UTF-32 surrogate pair for the next page.
        for (int trim = 0; trim <= (prefix ? 7 : 0); trim++) {
            if (data.length - skip < trim) break;
            try {
                String text = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(data, skip, data.length - skip - trim)).toString();
                if (!allowControls && text.indexOf('\0') >= 0) throw new IOException("此文件包含二进制数据");
                return new Segment(text, data.length - trim);
            } catch (CharacterCodingException e) { failure = e; }
        }
        throw new IOException("文件不符合 " + charset.name() + " 编码", failure);
    }
    static byte[] encode(String text, Charset charset, byte[] bom) throws IOException {
        try {
            ByteBuffer bytes = charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
            byte[] out = new byte[bom.length + bytes.remaining()];
            System.arraycopy(bom, 0, out, 0, bom.length); bytes.get(out, bom.length, bytes.remaining()); return out;
        } catch (CharacterCodingException e) { throw new IOException("当前编码无法保存部分字符，请切换 UTF-8", e); }
    }
    static void transcode(File source, File destination, Charset inputCharset, Charset outputCharset,
                          byte[] inputBom, byte[] outputBom) throws IOException {
        try (InputStream input = new BufferedInputStream(new FileInputStream(source));
             OutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
            FileStore.skip(input, inputBom.length);
            output.write(outputBom);
            try (Reader reader = new InputStreamReader(input, inputCharset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT));
                 Writer writer = new OutputStreamWriter(output, outputCharset.newEncoder()
                         .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))) {
                char[] buffer = new char[32768]; int count;
                while ((count = reader.read(buffer)) != -1) if (count > 0) writer.write(buffer, 0, count);
            }
        }
    }
    private TextCodec() {}
}
