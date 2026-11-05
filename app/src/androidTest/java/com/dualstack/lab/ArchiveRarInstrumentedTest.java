package com.dualstack.lab;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ArchiveRarInstrumentedTest {
    private File work;
    private FileStore store;

    @Before public void setup() throws Exception {
        File cache = InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir();
        work = Files.createTempDirectory(cache.toPath(), "rar-test-").toFile();
        store = new FileStore(InstrumentationRegistry.getInstrumentation().getTargetContext());
    }

    @After public void cleanup() throws Exception {
        if (work != null && work.exists()) store.delete(FileStore.local(work));
    }

    @Test public void rar4ListsNestedNamesStreamsFilesAndExtractsSafely() throws Exception {
        File rar = fixture("rar4-ext_time.rar");
        assertTrue(ArchiveOps.isRar(rar));
        List<ArchiveOps.Item> root = ArchiveOps.list(rar, "");
        assertEquals("files", root.get(0).name());

        FileStore.Node archive = FileStore.archiveNode(FileStore.local(rar), "", true, 0, 0);
        FileStore.Node files = store.list(archive).get(0);
        FileStore.Node test = store.list(files).get(0);
        FileStore.Node note = store.list(test).get(0);
        try (InputStream in = store.read(note)) {
            assertEquals("Short text for example", new String(FileStore.readBytes(in, 100), StandardCharsets.UTF_8));
        }

        File output = new File(work, "extract");
        ArchiveOps.extract(rar, "", output);
        assertEquals("Short text for example", new String(Files.readAllBytes(
                new File(output, "files/test/short-text.txt").toPath()), StandardCharsets.UTF_8));
        try { ArchiveOps.rewrite(rar, null, null, java.util.Collections.emptyMap()); fail("RAR rewrite accepted"); }
        catch (IOException expected) { assertTrue("message=" + expected.getMessage(), expected.getMessage() != null
                && expected.getMessage().contains("暂不支持直接修改")); }
    }

    @Test public void rar5ListsReadsAndExtractsFiles() throws Exception {
        File rar = fixture("rar5.rar");
        List<ArchiveOps.Item> items = ArchiveOps.list(rar, "");
        assertEquals(2, items.size());
        assertEquals("FILE1.TXT", items.get(0).name());
        assertEquals(7, ArchiveOps.bytes(rar, "FILE1.TXT", 64).length);
        File extracted = new File(work, "FILE1.TXT");
        ArchiveOps.extractOne(rar, "FILE1.TXT", extracted);
        assertEquals(7, extracted.length());
        File conflict = new File(work, "preserve.txt");
        Files.write(conflict.toPath(), "keep".getBytes(StandardCharsets.UTF_8));
        try { ArchiveOps.extractOne(rar, "FILE1.TXT", conflict); fail("existing target overwritten"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("已存在")); }
        assertEquals("keep", new String(Files.readAllBytes(conflict.toPath()), StandardCharsets.UTF_8));
    }

    @Test public void hostileRarTraversalNamesAreRejectedBeforeExtraction() throws Exception {
        File rar = fixture("mkdir-escape.rar");
        File output = new File(work, "safe");
        try { ArchiveOps.list(rar, ""); fail("traversal path accepted"); }
        catch (IOException expected) { }
        try { ArchiveOps.extract(rar, "", output); fail("hostile archive extracted"); }
        catch (IOException expected) { }
        assertFalse(new File(work, "extract_evil").exists());
    }

    private File fixture(String name) throws IOException {
        File target = new File(work, name);
        try (InputStream in = InstrumentationRegistry.getInstrumentation().getContext()
                .getAssets().open("rar-fixtures/" + name); OutputStream out = new FileOutputStream(target)) {
            FileStore.pump(in, out);
        }
        return target;
    }
}
