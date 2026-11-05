package com.dualstack.lab;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ArchiveFormatsInstrumentedTest {
    private File work;
    private FileStore store;

    @Before public void setup() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "tar-test-").toFile();
        store = new FileStore(context);
    }

    @After public void cleanup() throws Exception {
        if (work != null && work.exists()) store.delete(FileStore.local(work));
    }

    @Test public void tarAndGzipRoundTripLongUnicodeNamesAndEmptyDirectories() throws Exception {
        File source = new File(work, "source"); assertTrue(source.mkdir());
        String longName = "中文目录-".repeat(18);
        File nested = new File(source, longName); assertTrue(nested.mkdir());
        assertTrue(new File(source, "empty").mkdir());
        File text = new File(nested, "说明.txt");
        byte[] expected = "streaming TAR 中文内容".getBytes(StandardCharsets.UTF_8);
        Files.write(text.toPath(), expected);
        for (String format : List.of("tar", "tar.gz")) {
            File archive = new File(work, "sample." + format);
            ArchiveFormats.pack(store, List.of(FileStore.local(source)), archive, format);
            assertTrue(ArchiveFormats.supported(archive));
            assertEquals("source", ArchiveFormats.list(archive, "").get(0).name());
            assertTrue(ArchiveFormats.list(archive, "source/").stream()
                    .anyMatch(item -> item.name().equals("empty") && item.directory()));
            String entry = "source/" + longName + "/说明.txt";
            try (InputStream input = ArchiveFormats.openEntry(archive, entry)) {
                assertArrayEquals(expected, FileStore.readBytes(input, 1024));
            }
            File output = new File(work, "extract-" + format);
            ArchiveFormats.extract(archive, "", output, false);
            assertArrayEquals(expected, Files.readAllBytes(new File(output, entry).toPath()));
            assertTrue(new File(output, "source/empty").isDirectory());
        }
    }

    @Test public void implicitDirectoriesAndSelectedPrefixKeepTheirNames() throws Exception {
        File archive = new File(work, "implicit.tar");
        try (TarArchiveOutputStream output = output(archive)) {
            add(output, "nested/sub/note.txt", "value");
            add(output, "nested-extra/keep.txt", "keep");
        }
        assertEquals("nested", ArchiveFormats.list(archive, "").get(0).name());
        assertEquals("sub", ArchiveFormats.list(archive, "nested/").get(0).name());
        File selected = new File(work, "selected");
        ArchiveFormats.extract(archive, "nested/", selected, true);
        assertTrue(new File(selected, "nested/sub/note.txt").isFile());
        assertFalse(new File(selected, "nested-extra").exists());
        File current = new File(work, "current");
        ArchiveFormats.extract(archive, "nested/", current, false);
        assertTrue(new File(current, "sub/note.txt").isFile());
        File one = new File(work, "one");
        ArchiveFormats.extract(archive, "nested/sub/note.txt", one, true);
        assertEquals("value", new String(Files.readAllBytes(new File(one, "note.txt").toPath()), StandardCharsets.UTF_8));
    }

    @Test public void conflictingTargetRemainsUnchangedAndEarlierNewFilesAreRolledBack() throws Exception {
        File archive = new File(work, "conflict.tar");
        try (TarArchiveOutputStream output = output(archive)) {
            add(output, "new.txt", "new");
            add(output, "keep.txt", "replace");
        }
        File destination = new File(work, "destination"); assertTrue(destination.mkdir());
        File keep = new File(destination, "keep.txt"); Files.write(keep.toPath(), "original".getBytes(StandardCharsets.UTF_8));
        try { ArchiveFormats.extract(archive, "", destination, false); fail("Existing file overwritten"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("已存在")); }
        assertEquals("original", new String(Files.readAllBytes(keep.toPath()), StandardCharsets.UTF_8));
        assertArrayEquals(new String[]{"keep.txt"}, destination.list());
    }

    @Test public void traversalAndLinksFailWithoutLeavingExtractedFiles() throws Exception {
        for (String kind : List.of("traversal", "symlink", "hardlink")) {
            File archive = new File(work, kind + ".tar");
            try (TarArchiveOutputStream output = output(archive)) {
                add(output, "first.txt", "first");
                if (kind.equals("traversal")) add(output, "../escape.txt", "escape");
                else {
                    TarArchiveEntry link = new TarArchiveEntry("link", kind.equals("symlink")
                            ? TarConstants.LF_SYMLINK : TarConstants.LF_LINK);
                    link.setLinkName("../outside"); output.putArchiveEntry(link); output.closeArchiveEntry();
                }
            }
            try { ArchiveFormats.list(archive, ""); fail("Hostile TAR listed"); }
            catch (IOException expected) { }
            File destination = new File(work, "extract-" + kind);
            try { ArchiveFormats.extract(archive, "", destination, false); fail("Hostile TAR extracted"); }
            catch (IOException expected) { }
            assertFalse(destination.exists());
            assertFalse(new File(work, "escape.txt").exists());
        }
    }

    @Test public void failedPackLeavesExistingOutputUntouched() throws Exception {
        File first = new File(work, "first"), second = new File(work, "second");
        assertTrue(first.mkdir()); assertTrue(second.mkdir());
        File a = new File(first, "same.txt"), b = new File(second, "same.txt");
        Files.write(a.toPath(), "a".getBytes(StandardCharsets.UTF_8));
        Files.write(b.toPath(), "b".getBytes(StandardCharsets.UTF_8));
        File archive = new File(work, "preserve.tar");
        Files.write(archive.toPath(), "preserve".getBytes(StandardCharsets.UTF_8));
        try { ArchiveFormats.pack(store, List.of(FileStore.local(a), FileStore.local(b)), archive, "tar"); fail("Duplicate accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("同名")); }
        assertEquals("preserve", new String(Files.readAllBytes(archive.toPath()), StandardCharsets.UTF_8));
    }

    @Test public void sevenZRoundTripReadsExtractsAndRejectsTraversal() throws Exception {
        File source = new File(work, "seven"); assertTrue(source.mkdir());
        File nested = new File(source, "目录"); assertTrue(nested.mkdir());
        byte[] expected = "7z 中文内容".getBytes(StandardCharsets.UTF_8);
        Files.write(new File(nested, "note.txt").toPath(), expected);
        File archive = new File(work, "sample.7z");
        ArchiveFormats.pack(store, List.of(FileStore.local(source)), archive, "7z");
        assertTrue(ArchiveFormats.supported(archive));
        assertEquals("seven", ArchiveFormats.list(archive, "").get(0).name());
        try (InputStream input = ArchiveFormats.openEntry(archive, "seven/目录/note.txt")) {
            assertArrayEquals(expected, FileStore.readBytes(input, 1024));
        }
        File output = new File(work, "seven-extract");
        ArchiveFormats.extract(archive, "", output, false);
        assertArrayEquals(expected, Files.readAllBytes(new File(output, "seven/目录/note.txt").toPath()));
    }

    private static TarArchiveOutputStream output(File file) throws IOException {
        TarArchiveOutputStream output = new TarArchiveOutputStream(new FileOutputStream(file), "UTF-8");
        output.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
        output.setAddPaxHeadersForNonAsciiNames(true);
        return output;
    }

    private static void add(TarArchiveOutputStream output, String name, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry entry = new TarArchiveEntry(name); entry.setSize(bytes.length);
        output.putArchiveEntry(entry); output.write(bytes); output.closeArchiveEntry();
    }
}
