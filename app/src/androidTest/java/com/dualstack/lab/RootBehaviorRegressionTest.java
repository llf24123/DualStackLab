package com.dualstack.lab;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RootBehaviorRegressionTest {
    private Context context;
    private File work;

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "root-integrity-").toFile();
    }
    @After public void cleanup() throws Exception { new FileStore(context).delete(FileStore.local(work)); }

    @Test public void incompletePayloadDoesNotReplaceOriginalOrTouchAdjacentFile() throws Exception {
        File target = new File(work, "original.bin"), temporary = new File(work, "partial.tmp");
        File adjacent = new File(work, "original.bin.dualstack");
        Files.write(target.toPath(), "original".getBytes(StandardCharsets.UTF_8));
        Files.write(adjacent.toPath(), "keep".getBytes(StandardCharsets.UTF_8));
        Process process = new ProcessBuilder("/system/bin/sh", "-c",
                FileStore.rootWriteCommand(context.getApplicationInfo().sourceDir, target.getPath(), temporary.getPath(), 100, null)).start();
        try (OutputStream output = process.getOutputStream()) { output.write(new byte[]{1, 2, 3}); }
        assertTrue(process.waitFor(10, TimeUnit.SECONDS)); assertNotEquals(0, process.exitValue());
        assertEquals("original", new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
        assertEquals("keep", new String(Files.readAllBytes(adjacent.toPath()), StandardCharsets.UTF_8));
        assertFalse(temporary.exists());
    }

    @Test public void protectedRootStatsSkipExternalSymlinksWithoutLosingRootAccess() throws Exception {
        File root = new File("/data/local/tmp/dualstack-stats-" + UUID.randomUUID());
        String path = FileStore.quote(root.getPath());
        FileStore.shell("mkdir -m 700 " + path + "; mkdir " + path + "/tree " + path + "/outside; "
                + "printf abc > " + path + "/tree/inside; printf external > " + path + "/outside/big; "
                + "ln -s ../outside " + path + "/tree/link; ln -s . " + path + "/tree/cycle", true);
        try {
            FileStore store = new FileStore(context);
            DirectoryStats.Result stats = DirectoryStats.measure(store,
                    FileStore.directory(new File(root, "tree"), true), new AtomicBoolean());
            assertEquals(3, stats.bytes()); assertEquals(1, stats.files()); assertEquals(1, stats.directories());
            assertTrue(store.list(FileStore.directory(new File(root, "tree"), true)).stream()
                    .anyMatch(node -> node.name.equals("link") && node.directory));
        } finally { new FileStore(context).delete(FileStore.directory(root, true)); }
    }

    @Test public void encryptedZipPasswordSurvivesRootMaterialization() throws Exception {
        FileStore store = new FileStore(context);
        File payload = new File(work, "payload.txt"), archive = new File(work, "encrypted.zip");
        Files.write(payload.toPath(), "secret-payload".getBytes(StandardCharsets.UTF_8));
        char[] password = "test-password".toCharArray();
        try {
            ArchiveOps.pack(store, List.of(FileStore.local(payload)), archive, "zip", password);
            ArchiveOps.setPassword(archive, password);
            FileStore.Node directory = FileStore.archiveNode(FileStore.local(archive), "", true, 0, 0);
            FileStore.Node entry = store.list(directory).get(0);
            try (InputStream input = store.read(entry)) {
                assertEquals("secret-payload", new String(FileStore.readBytes(input, 100), StandardCharsets.UTF_8));
            }
        } finally { Arrays.fill(password, '\0'); ArchiveOps.clearPassword(archive); }
    }

    @Test public void shellMetacharactersInMissingDirectoryNeverBecomeCommands() throws Exception {
        File injected = new File(work, "injected");
        File missing = new File(work, "missing$(touch injected)\"'|.dir");
        try { RootDirectoryReader.list(missing.getPath()); fail("Missing path was accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains("not a directory")); }
        assertFalse(injected.exists());
        assertEquals(0, new FileStore(context).list(FileStore.directory(work, true)).size());
    }
}
