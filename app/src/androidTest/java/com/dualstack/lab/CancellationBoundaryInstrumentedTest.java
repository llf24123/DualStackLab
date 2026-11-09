package com.dualstack.lab;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.system.Os;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.*;

/** Deterministic item boundaries: completed work is retained, not advertised as rolled back. */
@RunWith(AndroidJUnit4.class)
public class CancellationBoundaryInstrumentedTest {
    private static final Uri CONTROL = Uri.parse("content://" + CountingDocumentsProvider.AUTHORITY);
    private Context context;
    private File work;
    private FileStore store;
    private ContentResolver resolver;
    private Uri rootUri;
    private String fixtureId;

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        store = new FileStore(context); resolver = context.getContentResolver();
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "cancel-" + UUID.randomUUID() + "-").toFile();
        // No activity, global permissions, root credentials, preferences, or recovery backups are changed.
    }

    @After public void cleanup() throws Exception {
        try {
            if (rootUri != null) {
                call("releaseRead", null); call("releaseDelete", null); call("configure", new Bundle());
                if (fixtureId != null) Tasks.Progress.cleanup(() -> {
                    if (!DocumentsContract.deleteDocument(resolver, documentUri(fixtureId)))
                        throw new IOException("Cannot remove this test's provider directory");
                });
            }
        } finally {
            if (work != null) Tasks.Progress.cleanup(() -> remove(work));
        }
    }

    @Test public void preCancelledLocalBatchAndRecursiveDeleteChangeNothing() throws Exception {
        File one = file(work, "one.txt", "one"), two = file(work, "two.txt", "two");
        File tree = directory(work, "tree"), nested = directory(tree, "nested");
        File leaf = file(nested, "leaf.txt", "leaf");
        String oneIdentity = identity(one), twoIdentity = identity(two), treeIdentity = identity(tree);
        Tasks.Progress progress = cancelledProgress();
        expectCancelled(() -> progress.run(() -> {
            store.deleteAll(List.of(FileStore.local(one), FileStore.local(two), FileStore.local(tree))); return null;
        }));
        expectCancelled(() -> progress.run(() -> { store.delete(FileStore.local(tree)); return null; }));
        assertEquals(0, progress.done.get());
        assertEquals(oneIdentity, identity(one)); assertEquals(twoIdentity, identity(two));
        assertEquals(treeIdentity, identity(tree));
        assertEquals("one", localText(one)); assertEquals("two", localText(two)); assertEquals("leaf", localText(leaf));
        assertNames(work, "one.txt", "two.txt", "tree"); assertNames(tree, "nested"); assertNames(nested, "leaf.txt");
    }

    @Test public void preCancelledSafBatchDeleteNeverCallsProviderDelete() throws Exception {
        fixture();
        String one = seed(fixtureId, "one.txt", false, "one"), two = seed(fixtureId, "two.txt", false, "two");
        String tree = seed(fixtureId, "tree", true, ""); seed(tree, "leaf.txt", false, "leaf");
        List<FileStore.Node> nodes = List.of(document(one), document(two), document(tree));
        List<String> before = paths(); call("clearCounters", null);
        Tasks.Progress progress = cancelledProgress();
        expectCancelled(() -> progress.run(() -> { store.deleteAll(nodes); return null; }));
        assertEquals(0, progress.done.get()); assertEquals(0, call("stats", null).getInt("deleteCalls"));
        assertEquals(before, paths());
        assertEquals("one", documentText(one)); assertEquals("two", documentText(two));
        assertEquals("leaf", documentText(tree + "/leaf.txt"));
    }

    @Test public void preCancelledSameVolumeBatchMoveChangesNeitherSide() throws Exception {
        File source = directory(work, "source"), destination = directory(work, "destination");
        File one = file(source, "one.txt", "one"), two = file(source, "two.txt", "two");
        File sentinel = file(destination, "keep.txt", "neighbour");
        String oneIdentity = identity(one), twoIdentity = identity(two), sentinelIdentity = identity(sentinel);
        assertEquals(Os.stat(source.getPath()).st_dev, Os.stat(destination.getPath()).st_dev);
        Tasks.Progress progress = cancelledProgress();
        expectCancelled(() -> progress.run(() -> store.copyAll(
                List.of(FileStore.local(one), FileStore.local(two)), FileStore.local(destination), true)));
        assertEquals(0, progress.done.get());
        assertNames(source, "one.txt", "two.txt"); assertNames(destination, "keep.txt");
        assertEquals(oneIdentity, identity(one)); assertEquals(twoIdentity, identity(two));
        assertEquals(sentinelIdentity, identity(sentinel));
        assertEquals("one", localText(one)); assertEquals("two", localText(two)); assertEquals("neighbour", localText(sentinel));
    }

    @Test public void fastBatchMoveKeepsFirstItemWhenCancelledBeforeSecond() throws Exception {
        // Runs on both device classes: ordinary local fast path without su, automatic ROOT with su.
        assertMoveBoundary(false);
    }

    @Test public void rootFastBatchMoveKeepsFirstItemWhenCancelledBeforeSecond() throws Exception {
        Assume.assumeTrue("Requires granted su, not a particular helper verdict", RootAccess.available());
        assertMoveBoundary(true);
    }

    private void assertMoveBoundary(boolean root) throws Exception {
        File source = directory(work, "source"), destination = directory(work, "destination");
        File one = file(source, "one.txt", "one"), two = file(source, "two.txt", "two"), three = file(source, "three.txt", "three");
        File keep = file(destination, "keep.txt", "neighbour");
        String oneIdentity = identity(one), twoIdentity = identity(two), threeIdentity = identity(three);
        assertEquals(Os.stat(source.getPath()).st_dev, Os.stat(destination.getPath()).st_dev);
        Tasks.Progress progress = new Tasks.Progress();
        CancelOnSecondPass inputs = new CancelOnSecondPass(List.of(FileStore.local(one, root),
                FileStore.local(two, root), FileStore.local(three, root)), progress);
        expectCancelled(() -> progress.run(() -> store.copyAll(inputs, FileStore.local(destination, root), true)));
        assertTrue("Cancellation must occur in the actual move pass", inputs.triggered);
        assertEquals("One preflight and one execution iterator", 2, inputs.passes);
        assertTrue("The completed first move must be counted", progress.done.get() > 0);
        assertFalse("Completed move was falsely rolled back", one.exists());
        assertEquals(oneIdentity, identity(new File(destination, "one.txt")));
        assertEquals("one", localText(new File(destination, "one.txt")));
        assertEquals(twoIdentity, identity(two)); assertEquals(threeIdentity, identity(three));
        assertEquals("two", localText(two)); assertEquals("three", localText(three));
        assertNames(source, "two.txt", "three.txt"); assertNames(destination, "keep.txt", "one.txt");
        assertEquals("neighbour", localText(keep));
    }

    @Test public void safBatchDeleteFinishesAdmittedItemAndStopsLaterItems() throws Exception {
        fixture();
        String one = seed(fixtureId, "one.txt", false, "one"), two = seed(fixtureId, "two.txt", false, "two");
        String three = seed(fixtureId, "three.txt", false, "three"), keep = seed(fixtureId, "keep.txt", false, "neighbour");
        List<FileStore.Node> nodes = List.of(document(one), document(two), document(three));
        Bundle configuration = new Bundle(); configuration.putString("blockDeleteName", "one.txt");
        call("configure", configuration); call("clearCounters", null);
        Tasks.Progress progress = new Tasks.Progress();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Future<?> deleting = worker.submit(() -> progress.run(() -> { store.deleteAll(nodes); return null; }));
        try {
            awaitGate("deleteState", "opened");
            Bundle blocked = call("deleteState", null);
            assertEquals(one, blocked.getString("documentId"));
            assertFalse(blocked.getBoolean("released")); assertFalse(blocked.getBoolean("completed"));
            assertEquals("one", documentText(one)); // Controls and queries remain usable while delete waits.
            assertFalse(deleting.isDone()); assertEquals(1, progress.done.get());
            progress.cancelled.set(true); call("releaseDelete", null);
            assertTrue(failure(deleting) instanceof Tasks.CancelledException);
            Bundle state = call("deleteState", null);
            assertTrue(state.getBoolean("completed")); assertNull(state.getString("error"));
            assertEquals("Only the already admitted delete may reach the provider", 1, call("stats", null).getInt("deleteCalls"));
            assertEquals(1, progress.done.get());
            assertFalse("The first completed deletion must not be recreated", paths().contains(relative(one)));
            assertEquals("two", documentText(two)); assertEquals("three", documentText(three));
            assertEquals("neighbour", documentText(keep));
            assertEquals(sorted(relative(fixtureId) + "/", relative(two), relative(three), relative(keep)), paths());
        } finally { finishWorker(worker, "releaseDelete"); }
    }

    @Test public void cancelledSafCopyKeepsCancellationPrimaryWhenCleanupThrowsSecurityException() throws Exception {
        fixture();
        String source = seed(fixtureId, "source", true, ""), destination = seed(fixtureId, "destination", true, "");
        seed(source, "a-good.txt", false, "first"); seed(source, "b-block.bin", false, blockedPayload());
        seed(source, "c-later.txt", false, "later"); seed(destination, "keep.txt", false, "neighbour");
        FileStore.Node sourceNode = document(source), destinationNode = document(destination);
        Bundle configuration = new Bundle(); configuration.putString("blockReadName", "b-block.bin");
        configuration.putString("failDeleteName", "a-good.txt"); call("configure", configuration);
        Tasks.Progress progress = new Tasks.Progress(); ExecutorService worker = Executors.newSingleThreadExecutor();
        Future<?> copying = worker.submit(() -> progress.run(() -> store.copyAll(List.of(sourceNode), destinationNode, true)));
        try {
            awaitGate("readState", "prefixWritten");
            assertEquals("first", documentText(destination + "/source/a-good.txt"));
            progress.cancelled.set(true); call("releaseRead", null);
            IOException error = failure(copying);
            assertTrue("Cleanup masked cancellation: " + error, error instanceof Tasks.CancelledException);
            assertTrue("Injected cleanup failure was not suppressed", Arrays.stream(error.getSuppressed())
                    .anyMatch(suppressed -> suppressed instanceof SecurityException
                            && String.valueOf(suppressed.getMessage()).contains("Injected delete failure")));
            assertTrue(progress.done.get() > 0);
            assertEquals("first", documentText(source + "/a-good.txt"));
            assertEquals("later", documentText(source + "/c-later.txt"));
            assertEquals(blockedPayload(), documentText(source + "/b-block.bin"));
            assertEquals("neighbour", documentText(destination + "/keep.txt"));
            assertEquals("The intentionally undeletable owned file remains visible", "first",
                    documentText(destination + "/source/a-good.txt"));
            assertFalse(paths().contains(relative(destination + "/source/b-block.bin")));
            assertFalse(paths().contains(relative(destination + "/source/c-later.txt")));
        } finally { finishWorker(worker, "releaseRead"); }
    }

    @Test public void cancelledRootCopyRemovesOnlyOwnedOutputAndKeepsForeignSentinels() throws Exception {
        Assume.assumeTrue("Requires granted su", RootAccess.available());
        fixture();
        String source = seed(fixtureId, "source", true, "");
        seed(source, "a-good.txt", false, "first"); seed(source, "b-block.bin", false, blockedPayload());
        seed(source, "c-later.txt", false, "later");
        File destination = directory(work, "destination"), keep = file(destination, "keep.txt", "neighbour");
        File output = new File(destination, "source"), replaced = new File(output, "a-good.txt");
        File foreign = new File(output, "foreign.txt"), replacement = file(work, "replacement.txt", "external-replacement");
        String keepIdentity = identity(keep), replacementIdentity = identity(replacement);
        FileStore.Node sourceNode = document(source), destinationNode = FileStore.local(destination, true);
        Bundle configuration = new Bundle(); configuration.putString("blockReadName", "b-block.bin"); call("configure", configuration);
        Tasks.Progress progress = new Tasks.Progress(); ExecutorService worker = Executors.newSingleThreadExecutor();
        Future<?> copying = worker.submit(() -> progress.run(() -> store.copyAll(List.of(sourceNode), destinationNode, true)));
        try {
            awaitGate("readState", "prefixWritten");
            assertEquals("first", localText(replaced));
            assertTrue("Second owned output exists before cancellation", new File(output, "b-block.bin").exists());
            assertNotEquals(replacementIdentity, identity(replaced));
            Files.move(replacement.toPath(), replaced.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            file(output, foreign.getName(), "external-neighbour"); String foreignIdentity = identity(foreign);
            progress.cancelled.set(true); call("releaseRead", null);
            IOException error = failure(copying);
            assertTrue("ROOT cleanup masked cancellation: " + error, error instanceof Tasks.CancelledException);
            assertTrue("Nonempty owned directory should report, not hide, cleanup failure", error.getSuppressed().length > 0);
            assertTrue("Cleanup inherited the cancelled caller instead of suspending it", Arrays.stream(error.getSuppressed())
                    .noneMatch(suppressed -> suppressed instanceof Tasks.CancelledException));
            assertTrue(progress.done.get() > 0);
            assertEquals(replacementIdentity, identity(replaced)); assertEquals("external-replacement", localText(replaced));
            assertEquals(foreignIdentity, identity(foreign)); assertEquals("external-neighbour", localText(foreign));
            assertEquals(keepIdentity, identity(keep)); assertEquals("neighbour", localText(keep));
            assertNames(destination, "keep.txt", "source"); assertNames(output, "a-good.txt", "foreign.txt");
            assertEquals("first", documentText(source + "/a-good.txt"));
            assertEquals(blockedPayload(), documentText(source + "/b-block.bin"));
            assertEquals("later", documentText(source + "/c-later.txt"));
            assertEquals(sorted(relative(fixtureId) + "/", relative(source) + "/", relative(source + "/a-good.txt"),
                    relative(source + "/b-block.bin"), relative(source + "/c-later.txt")), paths());
        } finally { finishWorker(worker, "releaseRead"); }
    }

    @Test public void cancelledArchiveSingleAndBatchDeletionKeepOriginalZipBytes() throws Exception {
        File zip = new File(work, "original.zip"), sentinel = file(work, "keep.txt", "not-selected");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip.toPath()))) {
            for (String name : List.of("one.txt", "two.txt", "keep.txt")) {
                output.putNextEntry(new ZipEntry(name)); output.write(name.getBytes(StandardCharsets.UTF_8)); output.closeEntry();
            }
        }
        byte[] original = Files.readAllBytes(zip.toPath()); String zipIdentity = identity(zip);
        FileStore.Node container = FileStore.local(zip);
        List<FileStore.Node> entries = List.of(FileStore.archiveNode(container, "one.txt", false, 0, 0),
                FileStore.archiveNode(container, "two.txt", false, 0, 0));
        Tasks.Progress progress = cancelledProgress();
        expectCancelled(() -> progress.run(() -> { store.delete(entries.get(0)); return null; }));
        expectCancelled(() -> progress.run(() -> { store.deleteAll(entries); return null; }));
        assertEquals(0, progress.done.get());
        assertArrayEquals(original, Files.readAllBytes(zip.toPath()));
        // Also cancel after validation but before the archive rewrite's selected-entry boundary.
        Tasks.Progress late = new Tasks.Progress(); CancelOnSecondPass selected = new CancelOnSecondPass(entries, late);
        expectCancelled(() -> late.run(() -> { store.deleteAll(selected); return null; }));
        assertTrue(selected.triggered); assertEquals(0, late.done.get());
        assertEquals(zipIdentity, identity(zip)); assertArrayEquals(original, Files.readAllBytes(zip.toPath()));
        try (ZipFile opened = new ZipFile(zip)) {
            assertEquals(3, opened.size());
            for (String name : List.of("one.txt", "two.txt", "keep.txt")) assertNotNull(opened.getEntry(name));
        }
        assertEquals("not-selected", localText(sentinel)); assertNames(work, "original.zip", "keep.txt");
    }

    @Test public void helperStepObservesOriginalCallerCancellationWithoutSu() throws Exception {
        File source = file(work, "source.txt", "source"), destination = directory(work, "destination");
        String sourceIdentity = identity(source); Thread caller = Thread.currentThread();
        Tasks.Progress progress = new Tasks.Progress(); AtomicInteger prepared = new AtomicInteger();
        RootFileOps.Request request = RootFileOps.Request.rename(spec(source), spec(destination), "moved.txt", true);
        try (RootMutations.Session session = new RootMutations.Session(context, false)) {
            expectCancelled(() -> progress.run(() -> session.execute(request, (event, name) -> {
                assertSame("Hook must run on the original Progress thread", caller, Thread.currentThread());
                if (event == RootFileOps.PREPARED) { prepared.incrementAndGet(); progress.cancelled.set(true); }
                return true;
            })));
            assertEquals(1, prepared.get()); assertEquals(0, progress.done.get());
            assertEquals(sourceIdentity, identity(source)); assertEquals("source", localText(source));
            assertNames(destination); assertTrue("A protocol cancellation should not poison the session", session.isAlive());
            // The request was not replayed. Only a deliberate new call, outside Progress, moves it.
            assertNotNull(session.execute(request, null));
            assertFalse(source.exists()); assertEquals(sourceIdentity, identity(new File(destination, "moved.txt")));
            assertEquals("source", localText(new File(destination, "moved.txt")));
        }
    }

    /** copyAll(move) validates one complete pass, then executes a fresh pass. Never cancel in preflight. */
    private static final class CancelOnSecondPass extends AbstractCollection<FileStore.Node> {
        private final List<FileStore.Node> nodes;
        private final Tasks.Progress progress;
        int passes;
        boolean triggered;
        CancelOnSecondPass(List<FileStore.Node> nodes, Tasks.Progress progress) { this.nodes = nodes; this.progress = progress; }
        @Override public int size() { return nodes.size(); }
        @Override public Iterator<FileStore.Node> iterator() {
            int pass = ++passes;
            return new Iterator<>() {
                int next;
                @Override public boolean hasNext() { return next < nodes.size(); }
                @Override public FileStore.Node next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    if (pass == 2 && next == 1) { triggered = true; progress.cancelled.set(true); }
                    return nodes.get(next++);
                }
            };
        }
    }

    private static Tasks.Progress cancelledProgress() {
        Tasks.Progress progress = new Tasks.Progress(); progress.cancelled.set(true); return progress;
    }
    private interface Attempt { void run() throws Exception; }
    private static Tasks.CancelledException expectCancelled(Attempt attempt) throws Exception {
        try { attempt.run(); fail("Cancellation was ignored"); return null; }
        catch (Tasks.CancelledException expected) { return expected; }
    }
    private static IOException failure(Future<?> task) throws Exception {
        try { task.get(45, TimeUnit.SECONDS); fail("Cancelled operation unexpectedly completed"); return null; }
        catch (ExecutionException error) {
            assertTrue("Unexpected worker error: " + error.getCause(), error.getCause() instanceof IOException);
            return (IOException) error.getCause();
        }
    }
    private void finishWorker(ExecutorService worker, String release) throws Exception {
        try { call(release, null); }
        finally {
            worker.shutdown();
            if (!worker.awaitTermination(45, TimeUnit.SECONDS)) {
                worker.shutdownNow();
                assertTrue("Worker did not finish after fixture release", worker.awaitTermination(15, TimeUnit.SECONDS));
            }
        }
    }
    private void awaitGate(String method, String state) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 15000;
        do {
            Bundle gate = call(method, null); assertNull("Fixture gate failed", gate.getString("error"));
            if (gate.getBoolean("opened") && gate.getBoolean(state)) return;
            Thread.sleep(10); // Poll an explicit fixture state, never guess the operation's speed.
        } while (SystemClock.uptimeMillis() < deadline);
        fail("Fixture did not reach " + method + "/" + state);
    }
    private void fixture() throws Exception {
        rootUri = Uri.parse(grantFixture().getString("uri"));
        fixtureId = seed("root", "cancel-" + UUID.randomUUID(), true, "");
    }
    private Bundle grantFixture() throws Exception {
        CountDownLatch finished = new CountDownLatch(1); AtomicReference<Bundle> result = new AtomicReference<>();
        AtomicInteger code = new AtomicInteger(Activity.RESULT_CANCELED);
        String testPackage = InstrumentationRegistry.getInstrumentation().getContext().getPackageName();
        Intent intent = new Intent(CountingDocumentsProvider.AUTHORITY + ".GRANT")
                .setComponent(new ComponentName(testPackage, CountingDocumentsProvider.GrantReceiver.class.getName()))
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        context.sendOrderedBroadcast(intent, null, new BroadcastReceiver() {
            @Override public void onReceive(Context receiverContext, Intent received) {
                code.set(getResultCode()); result.set(getResultExtras(false)); finished.countDown();
            }
        }, null, Activity.RESULT_CANCELED, null, null);
        assertTrue("Fixture grant did not finish", finished.await(30, TimeUnit.SECONDS));
        assertNotNull(result.get()); assertEquals(String.valueOf(result.get()), Activity.RESULT_OK, code.get());
        return result.get();
    }
    private Bundle call(String method, Bundle extras) {
        Bundle result = resolver.call(CONTROL, method, null, extras);
        assertNotNull("Fixture did not return " + method, result); return result;
    }
    private String seed(String parent, String name, boolean directory, String text) {
        Bundle extras = new Bundle(); extras.putString("parent", parent); extras.putString("name", name);
        extras.putBoolean("directory", directory); extras.putString("text", text); return call("seed", extras).getString("id");
    }
    private Uri documentUri(String id) { return DocumentsContract.buildDocumentUriUsingTree(rootUri, id); }
    private FileStore.Node document(String id) throws IOException { return store.document(documentUri(id)); }
    private String documentText(String id) throws IOException {
        try (InputStream input = resolver.openInputStream(documentUri(id))) {
            assertNotNull(input); return new String(FileStore.readBytes(input, 512 * 1024), StandardCharsets.UTF_8);
        }
    }
    private List<String> paths() { return call("stats", null).getStringArrayList("paths"); }
    private static String relative(String id) { return id.substring("root/".length()); }
    private static List<String> sorted(String... values) {
        List<String> result = new ArrayList<>(Arrays.asList(values)); Collections.sort(result); return result;
    }
    private static String blockedPayload() {
        char[] characters = new char[256 * 1024]; Arrays.fill(characters, 'x'); return new String(characters);
    }
    private static RootFileOps.Spec spec(File file) throws Exception {
        FileStore.Node node = FileStore.local(file);
        return new RootFileOps.Spec(node.path(), node.identity, node.parentIdentity, node.directoryIdentity, node.identityFollowsLinks);
    }
    private static String identity(File file) throws Exception { return FileStore.fileIdentity(Os.lstat(file.getPath())); }
    private static File directory(File parent, String name) {
        File file = new File(parent, name); assertTrue(file.mkdir()); return file;
    }
    private static File file(File parent, String name, String text) throws IOException {
        File file = new File(parent, name); Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8)); return file;
    }
    private static String localText(File file) throws IOException { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
    private static void assertNames(File directory, String... names) {
        String[] actual = directory.list(); assertNotNull("Cannot list " + directory, actual);
        assertEquals(sorted(names), sorted(actual));
    }
    private static void remove(File root) throws IOException {
        if (!root.exists()) return;
        Files.walkFileTree(root.toPath(), new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                Files.delete(path); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path path, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(path); return FileVisitResult.CONTINUE;
            }
        });
    }
}
