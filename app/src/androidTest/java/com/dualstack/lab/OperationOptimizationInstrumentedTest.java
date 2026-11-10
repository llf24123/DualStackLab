package com.dualstack.lab;

import android.content.*;
import android.os.*;
import android.widget.TextView;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.zip.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class OperationOptimizationInstrumentedTest {
    private Context context;
    private File work;
    private FileStore store;

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        store = new FileStore(context);
        work = Files.createTempDirectory(context.getFilesDir().toPath(), "optimization-test-").toFile();
    }
    @After public void cleanup() throws Exception {
        ArchiveOps.testRewriteBoundary = null;
        store.delete(FileStore.local(work));
    }
    @Test public void archiveDirectoryCannotCopyOrMoveIntoItsOwnNestedArchive() throws Exception {
        File inner = zip("inner.zip", Map.of("keep", "keep"));
        File outer = new File(work, "outer.zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(outer))) {
            zip.putNextEntry(new ZipEntry("dir/inner.zip"));
            zip.write(Files.readAllBytes(inner.toPath())); zip.closeEntry();
        }
        byte[] before = Files.readAllBytes(outer.toPath());
        FileStore.Node container = FileStore.local(outer);
        FileStore.Node source = FileStore.archiveNode(container, "dir/", true, 0, 0);
        FileStore.Node target = FileStore.archiveNode(member(container, "dir/inner.zip"), "", true, 0, 0);
        for (boolean move : new boolean[]{false, true}) {
            try { store.copyAll(List.of(source), target, move); fail("Nested self-target must be rejected"); }
            catch (IOException expected) { }
            assertArrayEquals(before, Files.readAllBytes(outer.toPath()));
        }
    }
    @Test public void copyIntoNestedArchiveVerifiesThePublishedOuterVersion() throws Exception {
        File inner = zip("inner.zip", Map.of("keep.txt", "kept"));
        File neighbour = file(work, "outside.txt", "outside");
        File outer = new File(work, "outer-target.zip");
        ArchiveOps.pack(store, List.of(FileStore.local(inner), FileStore.local(neighbour)), outer);
        FileStore.Node embedded = FileStore.archiveNode(FileStore.local(outer), "inner.zip", false, 0, 0);
        FileStore.Node target = FileStore.archiveNode(embedded, "", true, 0, 0);
        File input = file(work, "added.txt", "new bytes");
        List<FileStore.Node> copied = store.copyAll(List.of(FileStore.local(input)), target, false);
        try (InputStream in = store.read(copied.get(0))) {
            assertEquals("new bytes", new String(FileStore.readBytes(in, 100), StandardCharsets.UTF_8));
        }
        assertEquals("outside", entry(outer, "outside.txt"));
        assertEquals(List.of("keep.txt"), ArchiveOps.entryNames(inner));
    }
    @Test public void copyOuterMemberIntoInnerArchiveReadsBackLiveBytes() throws Exception {
        File inner = zip("inner.zip", Map.of("keep.txt", "kept"));
        File payload = file(work, "payload.txt", "member bytes");
        File outer = new File(work, "same-outer.zip");
        ArchiveOps.pack(store, List.of(FileStore.local(inner), FileStore.local(payload)), outer);
        FileStore.Node container = FileStore.local(outer);
        FileStore.Node source = member(container, "payload.txt");
        FileStore.Node embedded = member(container, "inner.zip");
        FileStore.Node target = FileStore.archiveNode(embedded, "", true, 0, 0);
        List<FileStore.Node> copied = store.copyAll(List.of(source), target, false);
        try (InputStream in = store.read(copied.get(0))) {
            assertEquals("member bytes", new String(FileStore.readBytes(in, 100), StandardCharsets.UTF_8));
        }
        assertEquals("member bytes", entry(outer, "payload.txt"));
    }

    @Test public void targetArchiveCopiesManyFilesWithOneRebuildAndKeepsCollision() throws Exception {
        File archive = zip("target.zip", Map.of("item.txt", "existing", "keep.txt", "keep"));
        File input = directory("input");
        List<FileStore.Node> sources = new ArrayList<>();
        for (int i = 0; i < 12; i++) sources.add(FileStore.local(file(input, i == 0 ? "item.txt" : "file-" + i, "payload-" + i)));
        AtomicInteger rewrites = new AtomicInteger(); ArchiveOps.testRewriteBoundary = a -> rewrites.incrementAndGet();
        List<FileStore.Node> outputs = store.copyAll(sources, FileStore.archiveNode(FileStore.local(archive), "", true, 0, 0), false);
        assertEquals(1, rewrites.get()); assertEquals(12, outputs.size());
        assertEquals("item (1).txt", outputs.get(0).name);
        assertEquals("existing", entry(archive, "item.txt")); assertEquals("payload-0", entry(archive, "item (1).txt"));
        assertEquals("keep", entry(archive, "keep.txt"));
    }
    @Test public void sameArchiveMovePublishesAdditionsAndRemovalsTogether() throws Exception {
        File archive = zip("move.zip", Map.of("a.txt", "A", "b.txt", "B", "dest/a.txt", "existing", "ab.txt", "prefix"));
        FileStore.Node container = FileStore.local(archive);
        AtomicInteger rewrites = new AtomicInteger(); ArchiveOps.testRewriteBoundary = a -> rewrites.incrementAndGet();
        store.copyAll(List.of(member(container, "a.txt"), member(container, "b.txt")), FileStore.archiveNode(container, "dest/", true, 0, 0), true);
        assertEquals(1, rewrites.get()); List<String> names = ArchiveOps.entryNames(archive);
        assertFalse(names.contains("a.txt")); assertFalse(names.contains("b.txt"));
        assertEquals("A", entry(archive, "dest/a (1).txt")); assertEquals("B", entry(archive, "dest/b.txt"));
        assertEquals("existing", entry(archive, "dest/a.txt")); assertEquals("prefix", entry(archive, "ab.txt"));
    }
    @Test public void movingArchiveMembersOutRebuildsSourceOnce() throws Exception {
        File archive = zip("move-out.zip", Map.of("a", "A", "b", "B", "keep", "keep"));
        FileStore.Node container = FileStore.local(archive);
        File output = directory("move-output");
        AtomicInteger rewrites = new AtomicInteger();
        ArchiveOps.testRewriteBoundary = a -> rewrites.incrementAndGet();
        store.copyAll(List.of(member(container, "a"), member(container, "b")), FileStore.local(output), true);
        assertEquals(1, rewrites.get());
        assertEquals(List.of("keep"), ArchiveOps.entryNames(archive));
        assertEquals("A", readText(new File(output, "a").toPath()));
        assertEquals("B", readText(new File(output, "b").toPath()));
    }
    @Test public void crossArchiveMoveRebuildsEachArchiveOnce() throws Exception {
        File source = zip("from.zip", Map.of("a", "A", "b", "B", "keep", "keep"));
        File target = zip("to.zip", Map.of("a", "existing"));
        FileStore.Node container = FileStore.local(source);
        AtomicInteger rewrites = new AtomicInteger();
        ArchiveOps.testRewriteBoundary = a -> rewrites.incrementAndGet();
        store.copyAll(List.of(member(container, "a"), member(container, "b")), FileStore.archiveNode(FileStore.local(target), "", true, 0, 0), true);
        assertEquals(2, rewrites.get());
        assertEquals(List.of("keep"), ArchiveOps.entryNames(source));
        assertEquals("existing", entry(target, "a"));
        assertEquals("A", entry(target, "a (1)"));
        assertEquals("B", entry(target, "b"));
    }
    @Test public void cancelledArchiveCopyKeepsCompletedOutputAndSource() throws Exception {
        File archive = zip("copy-out.zip", Map.of("a", "A", "b", "B"));
        FileStore.Node container = FileStore.local(archive);
        File output = directory("copy-output");
        Tasks.Progress progress = new Tasks.Progress();
        try {
            progress.run(() -> {
                store.copyArchiveBatch(List.of(member(container, "a"), member(container, "b")), FileStore.local(output), null,
                        node -> progress.cancelled.set(true));
                return null;
            });
            fail("Copy must stop between members");
        } catch (Tasks.CancelledException expected) { }
        assertEquals("A", readText(new File(output, "a").toPath()));
        assertFalse(new File(output, "b").exists());
        assertEquals("A", entry(archive, "a"));
        assertEquals("B", entry(archive, "b"));
    }
    @Test public void changedEarlierMoveOutputPreventsSourceRemoval() throws Exception {
        File archive = zip("move-output-conflict.zip", Map.of("a", "A", "b", "B"));
        FileStore.Node container = FileStore.local(archive);
        File output = directory("conflict-output");
        AtomicInteger published = new AtomicInteger();
        try {
            store.moveArchiveBatch(List.of(member(container, "a"), member(container, "b")), FileStore.local(output), node -> {
                if (published.incrementAndGet() == 2) Files.delete(new File(output, "a").toPath());
            });
            fail("Source removal must recheck every output");
        } catch (IOException expected) { }
        assertEquals("A", entry(archive, "a"));
        assertEquals("B", entry(archive, "b"));
        assertEquals("B", readText(new File(output, "b").toPath()));
    }

    @Test public void archiveCopyConflictDoesNotOverwriteExternalRevision() throws Exception {
        File archive = zip("copy-conflict.zip", Map.of("old", "old"));
        File a = file(work, "a", "A"), b = file(work, "b", "B");
        ArchiveOps.testRewriteBoundary = ignored -> writeZip(archive, Map.of("external", "new"));
        try { store.copyAll(List.of(FileStore.local(a), FileStore.local(b)), FileStore.archiveNode(FileStore.local(archive), "", true, 0, 0), false); fail("Conflict must stop publication"); }
        catch (IOException expected) { }
        assertEquals(List.of("external"), ArchiveOps.entryNames(archive));
    }

    @Test public void readOnlyResumeKeepsExistingArchiveWorkspace() throws Exception {
        File archive = zip("resume.zip", Map.of("a", "old")); Intent intent = new Intent(context, ArchiveActivity.class); FileStore.local(archive).put(intent);
        try (ActivityScenario<ArchiveActivity> screen = ActivityScenario.launch(intent)) {
            AtomicReference<File> workspace = new AtomicReference<>();
            await(() -> { screen.onActivity(a -> { try { workspace.set((File) field(a, "archive")); } catch (Exception e) { throw new RuntimeException(e); } }); return workspace.get() != null; });
            byte[] original = Files.readAllBytes(workspace.get().toPath());
            screen.moveToState(Lifecycle.State.CREATED); writeZip(archive, Map.of("external", "new")); screen.moveToState(Lifecycle.State.RESUMED);
            Thread.sleep(500); assertArrayEquals(original, Files.readAllBytes(workspace.get().toPath()));
            screen.onActivity(a -> { try { Method result = ArchiveActivity.class.getDeclaredMethod("onActivityResult", int.class, int.class, Intent.class); result.setAccessible(true); result.invoke(a, 303, android.app.Activity.RESULT_OK, null); } catch (Exception e) { throw new RuntimeException(e); } });
            await(() -> { try { return ArchiveOps.entryNames(workspace.get()).contains("external"); } catch (IOException e) { return false; } });
        }
    }

    private File directory(String name) throws IOException { File directory = new File(work, name); Files.createDirectories(directory.toPath()); return directory; }
    private static File file(File parent, String name, String text) throws IOException { File file = new File(parent, name); writeText(file.toPath(), text); return file; }
    private File zip(String name, Map<String, String> entries) throws IOException { File file = new File(work, name); writeZip(file, entries); return file; }
    private static void writeZip(File file, Map<String, String> entries) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) { zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); }
        }
        ArchiveOps.forget(file);
    }
    private static String readText(java.nio.file.Path path) throws IOException { return new String(Files.readAllBytes(path), StandardCharsets.UTF_8); }
    private static void writeText(java.nio.file.Path path, String text) throws IOException { Files.write(path, text.getBytes(StandardCharsets.UTF_8)); }
    private static FileStore.Node member(FileStore.Node container, String name) { return FileStore.archiveNode(container, name, false, 0, 0); }
    private static String entry(File archive, String name) throws IOException { return new String(ArchiveOps.bytes(archive, name, 100000), StandardCharsets.UTF_8); }
    private static Object field(Object object, String name) throws Exception { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
    private static void await(BooleanSupplier condition) throws Exception { long until = SystemClock.uptimeMillis() + 20000; while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < until) Thread.sleep(30); assertTrue("Timed out", condition.getAsBoolean()); }
}
