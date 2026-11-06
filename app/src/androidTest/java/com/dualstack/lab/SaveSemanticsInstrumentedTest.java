package com.dualstack.lab;

import android.content.Context;
import android.system.Os;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SaveSemanticsInstrumentedTest {
    private File work;
    private FileStore store;
    @Before public void setup() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        store = new FileStore(context);
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "save-semantics-").toFile();
    }
    @After public void cleanup() throws Exception {
        Files.walkFileTree(work.toPath(), new java.nio.file.SimpleFileVisitor<>() {
            @Override public java.nio.file.FileVisitResult visitFile(java.nio.file.Path path,
                    java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                Files.delete(path); return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override public java.nio.file.FileVisitResult postVisitDirectory(java.nio.file.Path path, IOException error) throws IOException {
                Files.delete(path); return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }
    @Test public void localPublicationPreservesModesIncludingReadOnly() throws Exception {
        File target = file("original", "before"), payload = file("payload", "after");
        for (int mode : new int[]{0640, 0755, 0444}) {
            Os.chmod(target.getPath(), mode);
            FileStore.FileState before = FileStore.FileState.read(target);
            File temporary = file("temporary", "after");
            FileStore.publish(temporary, target, before);
            assertEquals(mode, Os.stat(target.getPath()).st_mode & 07777);
            assertEquals(before.uid(), Os.stat(target.getPath()).st_uid);
            assertEquals(before.gid(), Os.stat(target.getPath()).st_gid);
            assertEquals("after", text(target));
            assertFalse(temporary.exists());
        }
        Os.chmod(payload.getPath(), 0444);
        store.write(FileStore.local(target), payload);
        assertEquals(0444, Os.stat(target.getPath()).st_mode & 07777);
    }
    @Test public void localAndAutomaticWritesRejectLinksWithoutChangingEitherFile() throws Exception {
        File target = file("real", "before"), payload = file("payload", "after"), link = new File(work, "link");
        Os.symlink("real", link.getPath());
        try { FileStore.FileState.read(link); fail("Link accepted"); } catch (IOException expected) { }
        try { store.write(FileStore.local(link), payload); fail("Link written"); } catch (IOException expected) { }
        assertTrue(Files.isSymbolicLink(link.toPath()));
        assertEquals("before", text(target));
        assertEquals("before", text(link));
    }
    @Test public void changedTargetIsNotReplacedByStalePublication() throws Exception {
        File target = file("original", "before"), temporary = file("temporary", "edited");
        FileStore.FileState before = FileStore.FileState.read(target);
        Files.delete(target.toPath()); file("original", "external-content");
        try { FileStore.publish(temporary, target, before); fail("Stale save accepted"); } catch (IOException expected) { }
        assertEquals("external-content", text(target));
        assertEquals("edited", text(temporary));
    }
    @Test public void archiveRewriteRejectsOuterSymlinkAndPreservesOriginal() throws Exception {
        File archive = zip("original.zip"), link = new File(work, "linked.zip"), payload = file("payload", "after");
        byte[] before = Files.readAllBytes(archive.toPath());
        Os.symlink("original.zip", link.getPath());
        try { ArchiveOps.rewrite(link, null, null, Map.of("a.txt", payload)); fail("Linked archive changed"); }
        catch (IOException expected) { }
        assertTrue(Files.isSymbolicLink(link.toPath()));
        assertArrayEquals(before, Files.readAllBytes(archive.toPath()));
    }
    @Test public void archiveRewriteRetainsModeAndWritesTheEntry() throws Exception {
        File archive = zip("original.zip"), payload = file("payload", "after");
        Os.chmod(archive.getPath(), 0640);
        ArchiveOps.rewrite(archive, null, null, Map.of("a.txt", payload));
        assertEquals(0640, Os.stat(archive.getPath()).st_mode & 07777);
        assertEquals("after", new String(ArchiveOps.bytes(archive, "a.txt", 100), StandardCharsets.UTF_8));
    }
    @Test public void shellWriterRejectsLinksAndKeepsExistingTemporary() throws Exception {
        File target = file("real", "before"), link = new File(work, "link"), temporary = new File(work, "temporary");
        Os.symlink("real", link.getPath());
        assertNotEquals(0, shellWrite(link, temporary, "after", 5));
        assertTrue(Files.isSymbolicLink(link.toPath())); assertFalse(temporary.exists());
        assertEquals("before", text(target));
        file("temporary", "unrelated");
        assertNotEquals(0, shellWrite(target, temporary, "after", 5));
        assertEquals("unrelated", text(temporary)); assertEquals("before", text(target));
    }
    @Test public void shellWriterRestoresModeAfterOwnershipAndRejectsShortPayload() throws Exception {
        File target = file("original", "before"), temporary = new File(work, "temporary");
        Os.chmod(target.getPath(), 04750);
        assertEquals(0, shellWrite(target, temporary, "after", 5));
        assertEquals(04750, Os.stat(target.getPath()).st_mode & 07777);
        assertEquals("after", text(target));
        assertNotEquals(0, shellWrite(target, temporary, "short", 100));
        assertEquals("after", text(target)); assertFalse(temporary.exists());
    }
    private int shellWrite(File target, File temporary, String value, int length) throws Exception {
        Process process = new ProcessBuilder("/system/bin/sh", "-c", FileStore.rootWriteCommand(
                target.getPath(), temporary.getPath(), length)).redirectErrorStream(true).start();
        try (OutputStream output = process.getOutputStream()) { output.write(value.getBytes(StandardCharsets.UTF_8)); }
        catch (IOException earlyExit) { }
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        return process.exitValue();
    }
    private File zip(String name) throws Exception {
        File archive = new File(work, name);
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(archive))) {
            output.putNextEntry(new ZipEntry("a.txt")); output.write("before".getBytes(StandardCharsets.UTF_8)); output.closeEntry();
        }
        return archive;
    }
    private File file(String name, String value) throws Exception {
        File file = new File(work, name); Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8)); return file;
    }
    private String text(File file) throws Exception { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
}
