package com.dualstack.lab;

import android.content.Context;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RootInstrumentedTest {
    private Context context;
    private File work;
    private FileStore store;
    private final List<ShellRunner> runners = new ArrayList<>();

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try { Assume.assumeTrue("Requires granted su", FileStore.shell("id -u", true).trim().equals("0")); }
        catch (IOException e) { Assume.assumeNoException("Requires granted su", e); }
        store = new FileStore(context);
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "root-review-").toFile();
    }
    @After public void cleanup() throws Exception {
        for (ShellRunner runner : runners) runner.stop();
        if (work != null) store.delete(FileStore.directory(work, true));
    }
    private ShellRunner runner() {
        ShellRunner runner = new ShellRunner(); runners.add(runner); return runner;
    }

    @Test public void rootWaitsForCompleteOutputAndPropagatesExitCode() throws Exception {
        long started = SystemClock.elapsedRealtime();
        ShellRunner.Result result = runner().run("id; sleep .4; printf ROOT_COMPLETE; exit 23",
                work, true, 10000, null, false);
        assertTrue(result.output(), result.output().contains("uid=0(root)"));
        assertTrue(result.output(), result.output().contains("ROOT_COMPLETE"));
        assertEquals(23, result.exitCode());
        assertTrue("Returned before command completed", SystemClock.elapsedRealtime() - started >= 350);
        assertFalse(result.timedOut());
    }

    @Test public void rootForwardsStdinAndStopsTimedOutChildGroups() throws Exception {
        ShellRunner interactive = runner(); CountDownLatch ready = new CountDownLatch(1);
        FutureTask<ShellRunner.Result> task = new FutureTask<>(() -> interactive.run(
                "printf READY; read value; printf '\\n%s\\n' \"$value\"; exit 7", work, true, 10000,
                text -> { if (text.contains("READY")) ready.countDown(); }, true));
        new Thread(task).start();
        assertTrue(ready.await(5, TimeUnit.SECONDS)); interactive.send("ROOT_STDIN_OK");
        ShellRunner.Result read = task.get(5, TimeUnit.SECONDS);
        assertEquals(7, read.exitCode()); assertTrue(read.output(), read.output().contains("ROOT_STDIN_OK"));

        File orphan = new File(work, "orphan-marker");
        String delayed = "trap '' TERM; (sleep 1; printf orphan > " + FileStore.quote(orphan.getAbsolutePath()) + ") & wait";
        assertTrue(runner().run(delayed, work, true, 200, null, false).timedOut());
        Thread.sleep(1300); assertFalse("Timed-out ROOT child survived", orphan.exists());

        ShellRunner cancel = runner(); CountDownLatch running = new CountDownLatch(1);
        // Add a readiness marker before waiting so cancellation targets the created group.
        FutureTask<ShellRunner.Result> cancelled = new FutureTask<>(() -> cancel.run("printf READY; " + delayed, work, true, 10000,
                text -> { if (text.contains("READY")) running.countDown(); }, false));
        new Thread(cancelled).start(); assertTrue(running.await(5, TimeUnit.SECONDS)); cancel.stop();
        assertTrue(cancelled.get(5, TimeUnit.SECONDS).stopped());
        Thread.sleep(1300); assertFalse("Cancelled ROOT child survived", orphan.exists());
    }

    @Test public void rootFileStoreReadsWritesRenamesTransfersAndDeletes() throws Exception {
        File protectedFolder = new File(work, "protected");
        FileStore.shell("mkdir " + FileStore.quote(protectedFolder.getAbsolutePath()) + "; chmod 700 "
                + FileStore.quote(protectedFolder.getAbsolutePath()), true);
        FileStore.Node parent = RootAccess.directory(protectedFolder, true); assertTrue(parent.root);
        FileStore.Node file = store.create(parent, "note.txt", false);
        byte[] bytes = ("ROOT data \u4e2d\u6587\n").repeat(8000).getBytes(StandardCharsets.UTF_8);
        File content = new File(work, "content"); Files.write(content.toPath(), bytes);
        store.write(file, content);
        try (InputStream input = store.read(file)) {
            assertArrayEquals(bytes, FileStore.readBytes(input, bytes.length + 4096));
        }
        FileStore.Node renamed = store.rename(file, "renamed.txt");
        assertTrue(store.list(parent).stream().anyMatch(node -> node.name.equals("renamed.txt") && node.size == bytes.length));
        File destination = new File(work, "normal"); assertTrue(destination.mkdir());
        FileStore.Node copied = store.copy(renamed, FileStore.directory(destination, false), false);
        assertArrayEquals(bytes, Files.readAllBytes(copied.file.toPath()));
        FileStore.Node moved = store.copy(renamed, FileStore.directory(destination, false), true);
        assertTrue(store.list(parent).isEmpty()); assertArrayEquals(bytes, Files.readAllBytes(moved.file.toPath()));
        store.delete(copied); store.delete(moved); assertEquals(0, destination.list().length);
    }

    @Test public void androidDataAutomaticallyUsesGrantedRootAndKeepsChildAccess() throws Exception {
        File data = new File("/storage/emulated/0/Android/data");
        FileStore.Node parent = RootAccess.directory(data, false);
        assertTrue("Android/data still requires a manual ROOT switch", parent.root);
        assertTrue(DirectoryPlaces.Entry.from(FileStore.directory(data, false)).resolve(store).root);
        List<FileStore.Node> entries = store.list(FileStore.directory(data, false));
        assertTrue(entries.stream().anyMatch(node -> node.name.equals(context.getPackageName()) && node.root));
        FileStore.Node own = entries.stream().filter(node -> node.name.equals(context.getPackageName())).findFirst().get();
        assertTrue(store.list(own).stream().allMatch(node -> node.root));
        for (String alias : List.of("/sdcard/Android/data", "/storage/self/primary/Android/data")) {
            assertTrue(alias, RootAccess.directory(new File(alias), false).root);
        }
        assertTrue(RootAccess.directory(work, false).root);
    }

    @Test public void privateDataAutomaticRootMatchesGlobalNamespace() throws Exception {
        for (String path : List.of("/data/user/0", "/data/data", "/data/user_de/0")) {
            String reference = FileStore.shell("su -M -c " + FileStore.quote("/system/bin/ls -1A " + FileStore.quote(path)), false);
            java.util.Set<String> expected = new java.util.TreeSet<>();
            for (String name : reference.split("\n")) if (!name.isEmpty()) expected.add(name);
            assertTrue("No reference apps at " + path, expected.size() > 10);
            int rootCount = store.list(FileStore.directory(new File(path), true)).size();
            android.util.Log.i("PrivateData067", path + " global=" + expected.size() + " rootShell=" + rootCount);
            List<FileStore.Node> actual = store.list(FileStore.directory(new File(path), false));
            java.util.Set<String> names = new java.util.TreeSet<>();
            for (FileStore.Node node : actual) names.add(node.name);
            android.util.Log.i("PrivateData067", path + " global=" + expected.size() + " app=" + names.size());
            assertEquals("Incomplete app directory list at " + path, expected, names);
            assertTrue(path + " lost ROOT access", actual.stream().allMatch(node -> node.root));
            assertTrue(DirectoryPlaces.Entry.from(FileStore.directory(new File(path), false)).resolve(store).root);
            FileStore.Node other = actual.stream().filter(node -> node.directory
                    && !node.name.startsWith(context.getPackageName())).findFirst().get();
            assertTrue("Cannot enter " + other.path(), store.list(other).stream().allMatch(node -> node.root));
        }
        assertTrue("Filesystem panes default to ROOT", RootAccess.directory(work, false).root);
    }

    @Test public void androidDataAutomaticRootSupportsWriteRenameAndDelete() throws Exception {
        File external = context.getExternalFilesDir(null);
        FileStore.Node parent = FileStore.directory(external, false);
        FileStore.Node folder = store.create(parent, "automatic-root-" + java.util.UUID.randomUUID(), true);
        try {
            assertTrue(folder.root);
            FileStore.Node note = store.create(folder, "settings.ini", false);
            File payload = new File(work, "payload.ini");
            Files.write(payload.toPath(), "[root]\naccess=ok\n".getBytes(StandardCharsets.UTF_8));
            store.write(note, payload);
            FileStore.Node renamed = store.rename(note, "renamed.ini");
            try (InputStream input = store.read(renamed)) {
                assertArrayEquals(Files.readAllBytes(payload.toPath()), FileStore.readBytes(input, 100));
            }
            store.delete(renamed);
            assertTrue(store.list(folder).isEmpty());
        } finally { store.delete(folder); }
    }

    @Test public void isolatedPrivateDataSupportsDirectReadWriteAndRootWorkingDirectory() throws Exception {
        File folder = new File("/data/user/0/com.dualstack.privatefixture." + java.util.UUID.randomUUID());
        FileStore.shell("mkdir " + FileStore.quote(folder.getAbsolutePath()) + " && chmod 700 "
                + FileStore.quote(folder.getAbsolutePath()), true);
        try {
            FileStore.Node created = store.create(FileStore.directory(folder, false), "original.ini", false);
            assertTrue(created.root);
            File payload = new File(work, "private-payload.ini");
            byte[] expected = "[private]\nvalue=original\n".getBytes(StandardCharsets.UTF_8);
            Files.write(payload.toPath(), expected);
            store.write(FileStore.local(created.file), payload);
            try (InputStream input = store.read(FileStore.local(created.file))) {
                assertArrayEquals(expected, FileStore.readBytes(input, 1024));
            }
            FileStore.Node renamed = store.rename(FileStore.local(created.file), "renamed.ini");
            assertTrue(renamed.root);
            FileStore.Node copied = store.copy(FileStore.local(renamed.file), FileStore.directory(work, false), false);
            assertArrayEquals(expected, Files.readAllBytes(copied.file.toPath()));
            ShellRunner.Result result = runner().run("cat renamed.ini; printf PRIVATE_CWD_OK", folder,
                    true, 10000, null, false);
            assertEquals(0, result.exitCode());
            assertTrue(result.output(), result.output().contains("value=original"));
            assertTrue(result.output(), result.output().contains("PRIVATE_CWD_OK"));
            store.delete(FileStore.local(renamed.file));
            assertTrue(store.list(FileStore.directory(folder, false)).isEmpty());
        } finally { store.delete(FileStore.directory(folder, true)); }
    }

    @Test public void normalRestrictedNodesUseConsistentRootForDirectOperations() throws Exception {
        File folder = new File(context.getExternalFilesDir(null), "direct-root-" + java.util.UUID.randomUUID());
        assertTrue(folder.mkdir());
        try {
            File payload = new File(work, "direct.txt");
            Files.write(payload.toPath(), "direct access\n".getBytes(StandardCharsets.UTF_8));
            File child = new File(folder, "direct.ini");
            Files.write(child.toPath(), "original\n".getBytes(StandardCharsets.UTF_8));
            store.write(FileStore.local(child), payload);
            FileStore.Node renamed = store.rename(FileStore.local(child), "renamed.ini");
            assertTrue(renamed.root);
            File staged = store.materialize(FileStore.local(renamed.file));
            assertNotEquals(renamed.file, staged);
            store.release(FileStore.local(renamed.file), staged); assertFalse(staged.exists());
            FileStore.Node moved = store.copy(FileStore.local(renamed.file), FileStore.directory(work, false), true);
            assertArrayEquals(Files.readAllBytes(payload.toPath()), Files.readAllBytes(moved.file.toPath()));
            assertTrue(store.list(FileStore.directory(folder, false)).isEmpty());
            assertTrue(RootAccess.protectedStorageDirectory(new File("/sdcard/Android/data")));
            assertTrue(RootAccess.protectedStorageDirectory(new File("/storage/emulated/0/Android/obb")));
            assertFalse(RootAccess.protectedStorageDirectory(folder));
        } finally { store.delete(FileStore.directory(folder, false)); }
        assertFalse(folder.exists());
    }

    @Test public void normalRestrictedZipEditsWriteBackToOriginalArchive() throws Exception {
        File zip = new File(context.getExternalFilesDir(null), "automatic-root-" + java.util.UUID.randomUUID() + ".zip");
        try {
            try (java.util.zip.ZipOutputStream output = new java.util.zip.ZipOutputStream(new FileOutputStream(zip))) {
                output.putNextEntry(new java.util.zip.ZipEntry("original.txt"));
                output.write("original".getBytes(StandardCharsets.UTF_8)); output.closeEntry();
            }
            FileStore.Node archive = FileStore.archiveNode(FileStore.local(zip), "", true, 0, 0);
            FileStore.Node created = store.create(archive, "added.ini", false);
            assertTrue(ArchiveOps.list(zip, "").stream().anyMatch(item -> item.path().equals("added.ini")));
            File payload = new File(work, "zip-payload.ini");
            Files.write(payload.toPath(), "[root]\nzip=ok\n".getBytes(StandardCharsets.UTF_8));
            store.write(created, payload);
            assertArrayEquals(Files.readAllBytes(payload.toPath()), ArchiveOps.bytes(zip, "added.ini", 100));
            store.delete(created);
            assertFalse(ArchiveOps.list(zip, "").stream().anyMatch(item -> item.path().equals("added.ini")));
            assertEquals("original", new String(ArchiveOps.bytes(zip, "original.txt", 100), StandardCharsets.UTF_8));
        } finally { store.delete(FileStore.local(zip)); }
    }

    @Test public void rootSilentInstallReceivesApkBytesFromPrivateCache() throws Exception {
        File source = new File(work, "fixture.apk.1");
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("install-fixture.apk");
             OutputStream output = new FileOutputStream(source)) { FileStore.pump(input, output); }
        assertTrue(ApkInstall.isApkName(source.getName()));
        File staged = ApkInstall.stage(context, FileStore.local(source));
        FileStore.shell("pm uninstall com.dualstack.installfixture || true", true);
        try {
            assertEquals("安装成功", ApkInstall.silent(staged));
            assertTrue(FileStore.shell("pm path com.dualstack.installfixture", true).trim().startsWith("package:"));
        } finally {
            FileStore.shell("pm uninstall com.dualstack.installfixture || true", true);
            Files.deleteIfExists(staged.toPath());
        }
    }

    @Test public void rootDirectorySurvivesDanglingLinksAndRejectsMissingPaths() throws Exception {
        FileStore.Node root = RootAccess.directory(new File("/"), true);
        assertTrue(root.root);
        List<FileStore.Node> entries = store.list(root);
        assertTrue(entries.stream().anyMatch(node -> node.name.equals("data") && node.directory));
        assertTrue(entries.stream().anyMatch(node -> node.name.equals("system") && node.directory));
        assertFalse(store.list(RootAccess.directory(new File("/data/adb"), true)).isEmpty());

        File folder = new File(work, "links"); assertTrue(folder.mkdir());
        FileStore.shell("ln -s missing " + FileStore.quote(new File(folder, "broken").getAbsolutePath())
                + "; mkdir " + FileStore.quote(new File(folder, "real").getAbsolutePath())
                + "; ln -s real " + FileStore.quote(new File(folder, "linked").getAbsolutePath()), true);
        List<FileStore.Node> links = store.list(FileStore.directory(folder, true));
        assertEquals(3, links.size());
        assertTrue(links.stream().anyMatch(node -> node.name.equals("broken") && !node.directory));
        assertTrue(links.stream().anyMatch(node -> node.name.equals("linked") && node.directory));
        assertTrue(store.list(FileStore.directory(new File(folder, "real"), true)).isEmpty());
        FileStore.shell("touch " + FileStore.quote(new File(folder, "real/inside").getAbsolutePath()), true);
        assertTrue(store.list(FileStore.directory(new File(folder, "real"), true))
                .stream().anyMatch(node -> node.name.equals("inside")));
        try {
            store.list(FileStore.directory(new File(folder, "missing"), true));
            fail("Missing directory must report an error");
        } catch (IOException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains("not a directory")
                || expected.getMessage().contains("已关闭")); }
    }
}
