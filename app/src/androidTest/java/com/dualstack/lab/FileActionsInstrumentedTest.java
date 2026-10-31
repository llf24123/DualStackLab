package com.dualstack.lab;

import android.content.*;
import android.net.Uri;
import android.system.Os;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class FileActionsInstrumentedTest {
    Context context; File work; FileStore store;
    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.executeShellCommand("cmd appops set com.dualstack.lab MANAGE_EXTERNAL_STORAGE allow");
        device.executeShellCommand("cmd appops set com.dualstack.lab.test MANAGE_EXTERNAL_STORAGE allow");
        store = new FileStore(context);
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "actions-069-").toFile();
    }
    @After public void cleanup() throws Exception { store.delete(FileStore.local(work)); }
    @Test public void sameVolumeMovePreservesInodePermissionsAndDirectoryChildren() throws Exception {
        File source = new File(work, "source"), destination = new File(work, "destination");
        assertTrue(source.mkdir()); assertTrue(destination.mkdir());
        File payload = new File(source, "payload.bin");
        try (RandomAccessFile out = new RandomAccessFile(payload, "rw")) { out.setLength(32L * 1024 * 1024); }
        Os.chmod(payload.getPath(), 0600);
        long inode = Os.stat(payload.getPath()).st_ino, folderInode = Os.stat(source.getPath()).st_ino;
        FileStore.Node moved = store.copy(FileStore.local(source), FileStore.local(destination), true);
        assertFalse(source.exists()); assertEquals(folderInode, Os.stat(moved.path()).st_ino);
        File result = new File(moved.file, payload.getName());
        assertEquals(inode, Os.stat(result.getPath()).st_ino);
        assertEquals(0600, Os.stat(result.getPath()).st_mode & 07777);
        assertEquals(32L * 1024 * 1024, result.length());
    }
    @Test public void moveToCurrentParentIsNoOpAndCollisionDoesNotOverwrite() throws Exception {
        File file = new File(work, "a.txt"); Files.write(file.toPath(), new byte[]{1, 2, 3});
        FileStore.Node same = store.copy(FileStore.local(file), FileStore.local(work), true);
        assertEquals(file.getPath(), same.path()); assertEquals(1, work.list().length);
        File dest = new File(work, "dest"); assertTrue(dest.mkdir());
        Files.write(new File(dest, "a.txt").toPath(), new byte[]{9});
        FileStore.Node moved = store.copy(FileStore.local(file), FileStore.local(dest), true);
        assertEquals("a (1).txt", moved.name);
        assertArrayEquals(new byte[]{9}, Files.readAllBytes(new File(dest, "a.txt").toPath()));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(moved.file.toPath()));
    }
    @Test public void permissionModesRoundTripAndInvalidOctalIsRejected() throws Exception {
        File file = new File(work, "mode.txt"); Files.write(file.toPath(), new byte[]{65});
        for (String mode : List.of("600", "644", "755", "0640", "1750")) {
            int parsed = FilePermissions.parse(mode);
            FilePermissions.State result = FilePermissions.apply(FileStore.local(file), parsed);
            assertEquals(parsed, result.mode());
            assertEquals(parsed, Os.stat(file.getPath()).st_mode & 07777);
        }
        assertEquals("rw-------", FilePermissions.symbolic(0600));
        assertEquals("rwxr-xr-x", FilePermissions.symbolic(0755));
        for (String invalid : List.of("900", "888", "60", "10000", "-600", "rwx", "7 55")) {
            try { FilePermissions.parse(invalid); fail(invalid); } catch (IOException expected) { }
        }
    }
    @Test public void directoryPermissionsAndSymlinkProtection() throws Exception {
        File directory = new File(work, "mode-dir"); assertTrue(directory.mkdir());
        File child = new File(directory, "child"); Files.write(child.toPath(), new byte[]{1}); Os.chmod(child.getPath(), 0644);
        assertEquals(0700, FilePermissions.apply(FileStore.local(directory), 0700).mode());
        assertEquals(0644, Os.stat(child.getPath()).st_mode & 07777);
        File link = new File(work, "link"); Os.symlink(child.getPath(), link.getPath());
        try { FilePermissions.apply(FileStore.local(link), 0600); fail("Link chmod"); } catch (IOException expected) { }
        assertEquals(0644, Os.stat(child.getPath()).st_mode & 07777);
    }
    @Test public void recursiveDirectoryStatisticsIncludeNestedAndEmptyFiles() throws Exception {
        File directory = new File(work, "stats"), child = new File(work, "stats/child");
        assertTrue(directory.mkdir()); assertTrue(child.mkdir());
        Files.write(new File(directory, "a.bin").toPath(), new byte[]{1, 2, 3});
        Files.write(new File(child, "b.bin").toPath(), new byte[]{4, 5});
        assertTrue(new File(child, "empty.bin").createNewFile());
        DirectoryStats.Result result = DirectoryStats.measure(store, FileStore.local(directory), new java.util.concurrent.atomic.AtomicBoolean());
        assertEquals(5, result.bytes()); assertEquals(3, result.files());
        assertEquals(2, result.directories()); assertEquals(0, result.unknownSizeFiles());
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean(true);
        try { DirectoryStats.measure(store, FileStore.local(directory), cancelled); fail("cancelled walk continued"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("停止")); }
    }
    @Test public void directoryStatisticsNeverFollowLinksOrCountExternalPayload() throws Exception {
        File directory = new File(work, "stats-links"); assertTrue(directory.mkdir());
        File outside = new File(work, "outside"); assertTrue(outside.mkdir());
        Files.write(new File(directory, "inside.bin").toPath(), new byte[]{1, 2, 3});
        File external = new File(outside, "external.bin"); Files.write(external.toPath(), new byte[4096]);
        Os.symlink(outside.getPath(), new File(directory, "outside-link").getPath());
        Os.symlink(external.getPath(), new File(directory, "file-link").getPath());
        Os.symlink(directory.getPath(), new File(directory, "cycle").getPath());
        Os.symlink(new File(work, "missing").getPath(), new File(directory, "dangling").getPath());
        DirectoryStats.Result result = DirectoryStats.measure(store, FileStore.local(directory), new java.util.concurrent.atomic.AtomicBoolean());
        assertEquals(3, result.bytes()); assertEquals(1, result.files()); assertEquals(1, result.directories());
    }
    @Test public void externalFileDescriptorResolvesProviderOriginalWithoutImporting() throws Exception {
        File base = new File("/storage/emulated/0/Download/dualstack-external-069"); assertTrue(base.isDirectory() || base.mkdirs());
        File file = new File(base, "provider-" + UUID.randomUUID() + ".zip"); Files.write(file.toPath(), ExternalFixtureProvider.zipBytes());
        try {
            Uri uri = Uri.parse("content://" + ExternalFixtureProvider.AUTHORITY + "/disk").buildUpon()
                    .appendQueryParameter("path", file.getPath()).build();
            ExternalFiles.Received received = ExternalFiles.receive(context, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            assertEquals(file.getCanonicalPath(), received.node().file.getCanonicalPath());
            assertEquals(base.getCanonicalPath(), received.directory().getCanonicalPath());
        } finally { Files.delete(file.toPath()); }
    }
    @Test public void externalPipeRemainsOriginalUriWithoutPersistentCopy() throws Exception {
        File imported = new File(context.getFilesDir(), "received-files");
        Set<String> before = imported.exists() ? new HashSet<>(Arrays.asList(imported.list())) : Set.of();
        Uri uri = Uri.parse("content://" + ExternalFixtureProvider.AUTHORITY + "/pipe");
        ExternalFiles.Received received = ExternalFiles.receive(context, uri, 0);
        assertNull(received.directory()); assertNull(received.node().file); assertEquals(uri, received.node().uri);
        assertEquals("provider.zip", received.node().name);
        File temporary = store.materialize(received.node());
        try { assertEquals("provider.txt", ArchiveOps.list(temporary, "").get(0).name()); }
        finally { store.release(received.node(), temporary); }
        Set<String> after = imported.exists() ? new HashSet<>(Arrays.asList(imported.list())) : Set.of();
        assertEquals(before, after);
    }
    @Test public void externalPrivateProviderDescriptorUsesRootForOriginalDirectory() throws Exception {
        Uri uri = Uri.parse("content://" + ExternalFixtureProvider.AUTHORITY + "/private");
        ExternalFiles.Received received = ExternalFiles.receive(context, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        assertNotNull(received.directory()); assertTrue(received.node().root);
        assertEquals("private-original.zip", received.node().name);
        assertTrue(store.list(FileStore.directory(received.directory(), true)).stream()
                .anyMatch(n -> n.name.equals("private-original.zip")));
        try (InputStream input = store.read(received.node())) {
            assertArrayEquals(ExternalFixtureProvider.zipBytes(), FileStore.readBytes(input, 4096));
        }
    }
    @Test public void extractionCreatesUniqueFolderAndSupportsSelectedEntries() throws Exception {
        File zip = new File(work, "sample.zip"); Files.write(zip.toPath(), ExternalFixtureProvider.zipBytes());
        FileStore.Node first = store.extract(List.of(FileStore.local(zip)), FileStore.local(work), "unpacked");
        FileStore.Node second = store.extract(List.of(FileStore.local(zip)), FileStore.local(work), "unpacked");
        assertEquals("unpacked", first.name); assertEquals("unpacked (1)", second.name);
        assertEquals("PROVIDER_OK", new String(Files.readAllBytes(new File(first.file, "provider.txt").toPath())));
        FileStore.Node entry = FileStore.archiveNode(FileStore.local(zip), "provider.txt", false, 11, 0);
        FileStore.Node partial = store.extract(List.of(entry), FileStore.local(work), "partial");
        assertEquals(List.of("provider.txt"), Arrays.asList(partial.file.list()));
    }
    @Test public void failedExtractionCleansDestinationAndKeepsOriginals() throws Exception {
        File zip = new File(work, "bad.zip");
        try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(new FileOutputStream(zip))) {
            out.putNextEntry(new java.util.zip.ZipEntry("valid.txt")); out.write(65); out.closeEntry();
            out.putNextEntry(new java.util.zip.ZipEntry("../escape.txt")); out.write(66); out.closeEntry();
        }
        try { store.extract(List.of(FileStore.local(zip)), FileStore.local(work), "failed"); fail("Zip slip"); }
        catch (IOException expected) { }
        assertFalse(new File(work, "failed").exists()); assertTrue(zip.exists());
        assertFalse(new File(work, "escape.txt").exists());
    }
    @Test public void rootMoveAndPermissionChangePreserveInode() throws Exception {
        assertEquals("0", FileStore.shell("id -u", true).trim());
        File rootWork = new File("/data/local/tmp/dualstack-actions-" + UUID.randomUUID());
        FileStore.shell("mkdir " + FileStore.quote(rootWork.getPath()), true);
        try {
            FileStore.Node parent = FileStore.directory(rootWork, true);
            FileStore.Node file = store.create(parent, "root.txt", false), dest = store.create(parent, "dest", true);
            FilePermissions.State changed = FilePermissions.apply(file, 0600);
            assertEquals(0600, changed.mode());
            String inode = FileStore.shell("stat -c %i " + FileStore.quote(file.path()), true).trim();
            FileStore.Node moved = store.copy(file, dest, true);
            assertEquals(inode, FileStore.shell("stat -c %i " + FileStore.quote(moved.path()), true).trim());
            assertEquals(0600, FilePermissions.read(moved).mode()); assertEquals(1, store.list(parent).size());
        } finally { store.delete(FileStore.directory(rootWork, true)); }
    }
    @Test public void crossVolumeMoveStreamsWithoutCacheAndPreservesPayload() throws Exception {
        File source = new File(work, "cross.bin");
        byte[] data = new byte[3 * 1024 * 1024]; new Random(69).nextBytes(data); Files.write(source.toPath(), data);
        File dest = new File(context.getExternalFilesDir(null), "cross-069-" + UUID.randomUUID()); assertTrue(dest.mkdir());
        try {
            FileStore.Node moved = store.copy(FileStore.local(source), FileStore.local(dest), true);
            assertFalse(source.exists()); assertArrayEquals(data, Files.readAllBytes(moved.file.toPath()));
        } finally { store.delete(FileStore.local(dest)); }
    }
    @Test public void selectedArchiveFoldersKeepNamesAndOnlyExtractSelectedContent() throws Exception {
        File zip = new File(work, "folders.zip");
        try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(new FileOutputStream(zip))) {
            for (String path : List.of("one/item.txt", "two/item.txt", "one/deep/keep.txt")) {
                out.putNextEntry(new java.util.zip.ZipEntry(path)); out.write(path.getBytes()); out.closeEntry();
            }
        }
        FileStore.Node source = FileStore.local(zip);
        FileStore.Node one = FileStore.archiveNode(source, "one/", true, 0, 0);
        FileStore.Node result = store.extract(List.of(one), FileStore.local(work), "selection");
        assertTrue(new File(result.file, "one/item.txt").isFile());
        assertTrue(new File(result.file, "one/deep/keep.txt").isFile()); assertFalse(new File(result.file, "two").exists());
        FileStore.Node two = FileStore.archiveNode(source, "two/item.txt", false, 0, 0);
        FileStore.Node file = store.extract(List.of(two), FileStore.local(work), "single");
        assertTrue(new File(file.file, "item.txt").isFile()); assertFalse(new File(file.file, "two").exists());
    }
}
