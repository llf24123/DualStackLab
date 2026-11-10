package com.dualstack.lab;

import android.content.Context;
import android.os.Environment;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SharedStorageTransferInstrumentedTest {
    private Context context;
    private File work;
    private FileStore store;
    private final byte[] payload = new byte[32 * 1024];

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).executeShellCommand(
                "cmd appops set " + context.getPackageName() + " MANAGE_EXTERNAL_STORAGE allow");
        work = Files.createTempDirectory(Environment.getExternalStorageDirectory().toPath(), "dualstack-verify-").toFile();
        store = new FileStore(context); new Random(20).nextBytes(payload);
        metadataChannel();
        long now = SystemClock.elapsedRealtime();
        set(RootAccess.class, "available", true); set(RootAccess.class, "checkedAt", now);
        set(RootAccess.class, "grantedUntil", now + 30000);
    }
    @After public void cleanup() throws Exception {
        RootAccess.reset(); RootDirectoryReader.close(); RootMutations.close();
        store.delete(FileStore.local(work));
    }

    @Test public void sharedArchiveCopyCreatesPublicRootFilesWithoutMutationHelper() throws Exception {
        assertTrue("Emulated filesystem must use the shared-storage path", StorageNavigation.appManagedStorage(work));
        assertFalse(StorageNavigation.appManagedStorage(context.getCacheDir()));
        File archive = archive();
        FileStore.Node parent = FileStore.local(work, true);
        Map<String, Set<String>> names = names(parent);
        FileStore.Node copied = store.copy(FileStore.archiveNode(FileStore.local(archive), "payload.bin", false, 0, 0), parent, false, names);
        assertArrayEquals(payload, Files.readAllBytes(copied.file.toPath()));
        assertEquals(FileStore.local(copied.file).identity, copied.identity);
        FileStore.Node empty = RootMutations.createShared(parent, "empty", true);
        assertTrue(empty.file.isDirectory());
        assertNull(get(RootMutations.class, "cached"));
        File alias = new File("/sdcard", work.getName());
        assertTrue(StorageNavigation.appManagedStorage(alias));
        RootMutations.createShared(FileStore.directory(alias, true), "alias.txt", false);
        assertTrue(new File(work, "alias.txt").isFile());
        File root = Environment.getExternalStorageDirectory();
        String rootName = "dualstack-verify-" + UUID.randomUUID() + ".bin";
        FileStore.Node created = RootMutations.createShared(FileStore.directory(root, true), rootName, false);
        try {
            assertEquals(FileStore.local(created.file).identity, created.identity);
            assertTrue(created.file.isFile());
        } finally { Files.delete(created.file.toPath()); }
    }

    @Test public void sharedMovePreservesDataAndRejectsOccupiedOrStaleEntries() throws Exception {
        File source = new File(work, "source.bin"); Files.write(source.toPath(), payload);
        File destination = new File(work, "destination"); assertTrue(destination.mkdir());
        FileStore.Node selected = FileStore.local(source, true), parent = FileStore.directory(destination, true);
        FileStore.Node moved = RootMutations.moveShared(selected, parent, "moved.bin");
        assertFalse(source.exists()); assertEquals(selected.identity, moved.identity);
        assertArrayEquals(payload, Files.readAllBytes(moved.file.toPath()));
        Files.write(source.toPath(), new byte[]{9});
        try { RootMutations.moveShared(FileStore.local(source, true), parent, "moved.bin"); fail("Must reject occupied target"); }
        catch (IOException expected) { assertTrue(source.exists()); }
        File replacement = new File(work, "replacement.bin"); Files.write(replacement.toPath(), new byte[]{7});
        FileStore.Node stale = FileStore.local(source, true);
        Files.move(source.toPath(), new File(work, "old-source.bin").toPath());
        Files.move(replacement.toPath(), source.toPath());
        try { RootMutations.moveShared(stale, parent, "stale.bin"); fail("Must reject changed inode"); }
        catch (IOException expected) { assertFalse(new File(destination, "stale.bin").exists()); }
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(source.toPath()));
        assertArrayEquals(payload, Files.readAllBytes(moved.file.toPath()));
        assertNull(get(RootMutations.class, "cached"));
        File folder = new File(work, "folder"); assertTrue(folder.mkdir());
        File child = new File(folder, "child"); assertTrue(child.mkdir());
        FileStore.Node folderNode = FileStore.directory(folder, true);
        try { RootMutations.moveShared(folderNode, FileStore.directory(child, true), "nested"); fail("Must reject descendant target"); }
        catch (IOException expected) { assertTrue(folder.isDirectory()); }
        FileStore.Node movedFolder = RootMutations.moveShared(folderNode, parent, "folder");
        assertEquals(folderNode.identity, movedFolder.identity);
        assertTrue(new File(movedFolder.file, "child").isDirectory()); assertFalse(folder.exists());
    }

    @Test public void measureSmallSharedTransfers() throws Exception {
        boolean legacy = InstrumentationRegistry.getArguments().getString("legacyHelper", "false").equals("true");
        File archive = archive(), copied = new File(work, "copied"), moved = new File(work, "moved");
        assertTrue(copied.mkdir()); assertTrue(moved.mkdir());
        // App-owned external folders also work with the previous strict helper, allowing paired timing.
        File owned = Files.createTempDirectory(context.getExternalFilesDir(null).toPath(), "transfer-timing-").toFile();
        try {
            FileStore.Node parent = FileStore.directory(owned, true);
            List<Long> archiveTimes = new ArrayList<>(), copyTimes = new ArrayList<>(), moveTimes = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                RootMutations.close(); long start = SystemClock.elapsedRealtime();
                if (legacy) {
                    set(RootMutations.class, "cached", new RootMutations.Session(context, false));
                    set(RootMutations.class, "cachedRoot", true);
                }
                FileStore.Node from = FileStore.archiveNode(FileStore.local(archive), "payload.bin", false, 0, 0);
                FileStore.Node extracted = store.copy(from, parent, false, names(parent));
                archiveTimes.add(SystemClock.elapsedRealtime() - start);
                assertArrayEquals(payload, Files.readAllBytes(extracted.file.toPath()));
                FileStore.Node normalTarget = FileStore.directory(copied, true);
                start = SystemClock.elapsedRealtime();
                FileStore.Node normal = store.copy(extracted, normalTarget, false, names(normalTarget));
                copyTimes.add(SystemClock.elapsedRealtime() - start);
                FileStore.Node moveTarget = FileStore.directory(moved, true);
                start = SystemClock.elapsedRealtime();
                FileStore.Node output = store.copy(normal, moveTarget, true, names(moveTarget));
                moveTimes.add(SystemClock.elapsedRealtime() - start);
                assertArrayEquals(payload, Files.readAllBytes(output.file.toPath())); assertFalse(normal.file.exists());
                Files.delete(extracted.file.toPath()); Files.delete(output.file.toPath());
            }
            String result = "legacy=" + legacy + " bytes=" + payload.length + " archiveMs=" + archiveTimes + " copyMs=" + copyTimes + " moveMs=" + moveTimes;
            android.util.Log.i("SharedTransferTiming", result);
            Files.write(new File(context.getExternalFilesDir(null), "shared-transfer-" + (legacy ? "before" : "after") + ".txt").toPath(), result.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (!legacy) assertNull(get(RootMutations.class, "cached"));
        } finally { RootAccess.reset(); store.delete(FileStore.local(owned)); }
    }
    private File archive() throws Exception {
        File file = new File(work, "source.zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("payload.bin")); zip.write(payload); zip.closeEntry();
        }
        return file;
    }
    private Map<String, Set<String>> names(FileStore.Node parent) {
        Map<String, Set<String>> result = new HashMap<>(); result.put(parent.key(), new HashSet<>()); return result;
    }
    private void metadataChannel() throws Exception {
        RootDirectoryReader.close(); RootMutations.close();
        Class<?> type = Class.forName("com.dualstack.lab.RootDirectoryReader$Session");
        Constructor<?> constructor = type.getDeclaredConstructor(ProcessBuilder.class); constructor.setAccessible(true);
        set(RootDirectoryReader.class, "session", constructor.newInstance(new ProcessBuilder("/system/bin/sh")));
    }
    private static void set(Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(null, value);
    }
    private static Object get(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(null);
    }
}
