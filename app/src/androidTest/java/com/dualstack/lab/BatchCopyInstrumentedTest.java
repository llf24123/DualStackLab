package com.dualstack.lab;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.system.Os;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BatchCopyInstrumentedTest {
    private static final Uri CONTROL = Uri.parse("content://" + CountingDocumentsProvider.AUTHORITY);
    private File work;
    private FileStore store;
    private ContentResolver resolver;
    private FileStore.Node documents;
    private Uri rootUri;

    @Before public void setup() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        resolver = context.getContentResolver(); store = new FileStore(context);
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "batch-copy-").toFile();
        rootUri = Uri.parse(grantFixture(context).getString("uri"));
        documents = store.document(rootUri);
    }
    @After public void cleanup() throws Exception {
        if (rootUri != null) call("reset", null);
        if (work != null) remove(work);
    }
    @Test public void manyTopLevelFilesEnumerateSafDestinationOnlyOnceAndKeepCollisions() throws Exception {
        seed("root", "item-000.txt", false, "pre-existing");
        File source = directory(work, "source");
        List<FileStore.Node> inputs = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            String name = String.format(Locale.ROOT, "item-%03d.txt", i);
            inputs.add(FileStore.local(file(source, name, "payload-" + i)));
        }
        call("clearCounters", null);
        List<FileStore.Node> copied = store.copyAll(inputs, documents, false);
        Bundle result = call("stats", null);
        assertEquals("Destination was re-enumerated for every file", 1, result.getInt("childQueries"));
        assertEquals(200, copied.size()); assertEquals(201, result.getStringArrayList("paths").size());
        assertEquals("pre-existing", documentText("root/item-000.txt"));
        assertEquals("item-000 (1).txt", copied.get(0).name);
        for (int i = 0; i < copied.size(); i++) {
            assertEquals("payload-" + i, nodeText(copied.get(i)));
            assertTrue(inputs.get(i).file.exists());
        }
    }
    @Test public void providerArchiveBatchReusesOneVerifiedSourceSnapshot() throws Exception {
        List<FileStore.Node> inputs = new ArrayList<>();
        for (int i = 0; i < 20; i++) inputs.add(FileStore.local(file(work, "source-" + i, "payload-" + i)));
        File input = new File(work, "input.zip"), output = new File(work, "output.zip");
        ArchiveOps.pack(store, inputs, input);
        ArchiveOps.pack(store, Collections.emptyList(), output);
        Bundle seed = new Bundle(); seed.putString("name", "input.zip"); seed.putByteArray("bytes", Files.readAllBytes(input.toPath()));
        call("seed", seed);
        FileStore.Node source = store.list(documents).get(0);
        List<FileStore.Node> members = new ArrayList<>();
        for (int i = 0; i < 20; i++) members.add(FileStore.archiveNode(source, "source-" + i, false, 0, 0));
        call("clearCounters", null);
        store.copyAll(members, FileStore.archiveNode(FileStore.local(output), "", true, 0, 0), false);
        assertTrue("Provider archive was re-read per member", call("stats", null).getInt("readOpens") <= 5);
        assertEquals(20, ArchiveOps.entryNames(output).size());
        assertEquals("payload-19", new String(ArchiveOps.bytes(output, "source-19", 100), StandardCharsets.UTF_8));
    }
    @Test public void providerArchiveToFolderReusesOneContainerRead() throws Exception {
        File archive = new File(work, "read-batch.zip"), destination = directory(work, "archive-out");
        List<FileStore.Node> inputs = new ArrayList<>();
        for (int i = 0; i < 24; i++) inputs.add(FileStore.local(file(work, "batch-" + i, "payload-" + i)));
        ArchiveOps.pack(store, inputs, archive);
        Bundle seed = new Bundle(); seed.putString("name", "read-batch.zip"); seed.putByteArray("bytes", Files.readAllBytes(archive.toPath())); call("seed", seed);
        FileStore.Node container = store.list(documents).get(0);
        List<FileStore.Node> members = new ArrayList<>();
        for (int i = 0; i < 24; i++) members.add(FileStore.archiveNode(container, "batch-" + i, false, 0, 0));
        call("clearCounters", null);
        long start = android.os.SystemClock.elapsedRealtime();
        List<FileStore.Node> outputs = store.copyAll(members, FileStore.local(destination), false);
        long elapsed = android.os.SystemClock.elapsedRealtime() - start;
        int reads = call("stats", null).getInt("readOpens");
        String run = InstrumentationRegistry.getArguments().getString("benchmarkRun", "candidate");
        org.json.JSONObject timing = new org.json.JSONObject().put("milliseconds", elapsed).put("containerReads", reads).put("members", outputs.size());
        Files.write(new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null), "archive-copy-" + run + ".json").toPath(), timing.toString(2).getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < outputs.size(); i++) assertEquals("payload-" + i, localText(outputs.get(i).file));
        if (!run.equals("baseline")) assertEquals("The provider ZIP should be opened once for the whole copy", 1, reads);
    }
    @Test public void nestedProviderArchiveDirectoryReusesViewsAndCleansTemporaryFiles() throws Exception {
        File tree = directory(work, "tree"), nested = directory(tree, "nested"); directory(nested, "empty");
        for (int i = 0; i < 16; i++) file(tree, "item-" + i, "data-" + i);
        file(nested, "zero", "");
        File inner = new File(work, "inner.zip"), outer = new File(work, "outer.zip"), output = directory(work, "nested-out");
        ArchiveOps.pack(store, List.of(FileStore.local(tree)), inner);
        ArchiveOps.pack(store, List.of(FileStore.local(inner)), outer);
        Bundle seed = new Bundle(); seed.putString("name", "outer.zip"); seed.putByteArray("bytes", Files.readAllBytes(outer.toPath())); call("seed", seed);
        FileStore.Node provider = store.list(documents).get(0);
        FileStore.Node embedded = FileStore.archiveNode(provider, "inner.zip", false, 0, 0);
        FileStore.Node selected = FileStore.archiveNode(embedded, "tree/", true, 0, 0);
        File cache = InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir();
        Set<String> before = new HashSet<>(Arrays.asList(cache.list((dir, name) -> name.startsWith("import-"))));
        call("clearCounters", null);
        store.copy(selected, FileStore.local(output), false);
        assertEquals(1, call("stats", null).getInt("readOpens"));
        for (int i = 0; i < 16; i++) assertEquals("data-" + i, localText(new File(output, "tree/item-" + i)));
        assertTrue(new File(output, "tree/nested/empty").isDirectory());
        assertEquals(0, new File(output, "tree/nested/zero").length());
        assertEquals(before, new HashSet<>(Arrays.asList(cache.list((dir, name) -> name.startsWith("import-")))));
    }
    @Test public void recursiveCopyDoesNotEnumerateEachNewSafDirectory() throws Exception {
        File source = directory(work, "tree"), nested = directory(source, "nested");
        directory(nested, "empty");
        for (int i = 0; i < 24; i++) file(source, "top-" + i + ".txt", "top-" + i);
        file(nested, "child.txt", "nested-payload"); file(nested, "zero.bin", "");
        call("clearCounters", null);
        List<FileStore.Node> copied = store.copyAll(List.of(FileStore.local(source)), documents, false);
        Bundle result = call("stats", null);
        assertEquals("Newly created directories should not need display listings", 1, result.getInt("childQueries"));
        assertEquals(1, copied.size());
        List<String> paths = result.getStringArrayList("paths");
        assertTrue(paths.contains("tree/")); assertTrue(paths.contains("tree/nested/empty/"));
        assertEquals("nested-payload", documentText("root/tree/nested/child.txt"));
        assertEquals("", documentText("root/tree/nested/zero.bin"));
        for (int i = 0; i < 24; i++) assertEquals("top-" + i, documentText("root/tree/top-" + i + ".txt"));
    }
    @Test public void providerReturnedNamesAndConcurrentCollisionsAreHonoured() throws Exception {
        Bundle configuration = new Bundle(); configuration.putString("renameName", "rename.txt");
        configuration.putString("collideName", "race.txt"); call("configure", configuration);
        File renamed = file(work, "rename.txt", "renamed-payload"), raced = file(work, "race.txt", "raced-payload");
        List<FileStore.Node> copied = store.copyAll(List.of(FileStore.local(renamed), FileStore.local(raced)), documents, false);
        assertEquals("provider-rename.txt", copied.get(0).name);
        assertEquals("renamed-payload", nodeText(copied.get(0)));
        assertEquals("race (1).txt", copied.get(1).name);
        assertEquals("concurrent-owner", documentText("root/race.txt"));
        assertEquals("raced-payload", nodeText(copied.get(1)));
        assertEquals(1, call("stats", null).getInt("childQueries"));
    }
    @Test public void duplicateBatchNamesAvoidExistingLocalFiles() throws Exception {
        File one = directory(work, "one"), two = directory(work, "two"), destination = directory(work, "destination");
        File first = file(one, "same.txt", "first"), second = file(two, "same.txt", "second");
        File existing = file(destination, "same.txt", "existing");
        List<FileStore.Node> copied = store.copyAll(List.of(FileStore.local(first), FileStore.local(second)),
                FileStore.local(destination), false);
        assertEquals("same (1).txt", copied.get(0).name); assertEquals("same (2).txt", copied.get(1).name);
        assertEquals("existing", localText(existing));
        assertEquals("first", localText(copied.get(0).file)); assertEquals("second", localText(copied.get(1).file));
        assertTrue(first.exists()); assertTrue(second.exists());
    }
    @Test public void sameVolumeBatchMovePreservesInodesAndPermissions() throws Exception {
        File source = directory(work, "source"), destination = directory(work, "destination");
        File first = file(source, "first.sh", "first"), second = file(source, "second.txt", "second");
        Os.chmod(first.getPath(), 0755); Os.chmod(second.getPath(), 0640);
        long firstInode = Os.stat(first.getPath()).st_ino, secondInode = Os.stat(second.getPath()).st_ino;
        List<FileStore.Node> moved = store.copyAll(List.of(FileStore.local(first), FileStore.local(second)), FileStore.local(destination), true);
        assertFalse(first.exists()); assertFalse(second.exists());
        assertEquals(firstInode, Os.stat(moved.get(0).path()).st_ino);
        assertEquals(secondInode, Os.stat(moved.get(1).path()).st_ino);
        assertEquals(0755, Os.stat(moved.get(0).path()).st_mode & 07777);
        assertEquals(0640, Os.stat(moved.get(1).path()).st_mode & 07777);
        assertEquals("first", localText(moved.get(0).file)); assertEquals("second", localText(moved.get(1).file));
    }
    @Test public void failedRecursiveMoveKeepsSourceAndUnrelatedSafFiles() throws Exception {
        seed("root", "keep.txt", false, "existing-neighbour");
        File source = directory(work, "source");
        File first = file(source, "a-good.txt", "first"), broken = file(source, "b-broken.txt", "second");
        Bundle configuration = new Bundle(); configuration.putString("failCreateName", "b-broken.txt"); call("configure", configuration);
        try {
            store.copyAll(List.of(FileStore.local(source)), documents, true);
            fail("Injected create failure was ignored");
        } catch (IOException expected) { }
        assertEquals("first", localText(first)); assertEquals("second", localText(broken));
        assertEquals(List.of("keep.txt"), call("stats", null).getStringArrayList("paths"));
        assertEquals("existing-neighbour", documentText("root/keep.txt"));
    }
    @Test public void failedSafReadRemovesOnlyNewLocalOutputAndKeepsMoveSource() throws Exception {
        seed("root", "broken.txt", false, "source-payload");
        File destination = directory(work, "destination"), keep = file(destination, "keep.txt", "existing-neighbour");
        Bundle configuration = new Bundle(); configuration.putString("failReadName", "broken.txt"); call("configure", configuration);
        FileStore.Node input = store.document(documentUri("root/broken.txt"));
        try {
            store.copyAll(List.of(input), FileStore.local(destination), true);
            fail("Injected read failure was ignored");
        } catch (IOException expected) { }
        assertEquals("existing-neighbour", localText(keep));
        assertEquals(List.of("keep.txt"), Arrays.asList(Objects.requireNonNull(destination.list())));
        assertEquals(List.of("broken.txt"), call("stats", null).getStringArrayList("paths"));
        call("configure", new Bundle());
        assertEquals("source-payload", nodeText(input));
    }
    @Test public void rollbackDoesNotClaimAFileExternallyReplacedDuringAnOrdinaryCopy() throws Exception {
        Assume.assumeFalse("Ordinary file descriptor ownership requires a device without su", RootAccess.available());
        seed("root", "source", true, "");
        char[] characters = new char[256 * 1024]; Arrays.fill(characters, 'x');
        seed("root/source", "a.txt", false, new String(characters));
        seed("root/source", "b-broken.txt", false, "second-source");
        File destination = directory(work, "destination"), keep = file(destination, "keep.txt", "existing-neighbour");
        File created = new File(destination, "source/a.txt"), replacement = file(work, "external-file", "external-replacement");
        long replacementInode = Os.stat(replacement.getPath()).st_ino;
        Bundle configuration = new Bundle(); configuration.putString("blockReadName", "a.txt");
        configuration.putString("failReadName", "b-broken.txt"); call("configure", configuration);
        FileStore.Node source = store.document(documentUri("root/source"));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Future<List<FileStore.Node>> copying = worker.submit(() -> store.copyAll(List.of(source), FileStore.local(destination), true));
        try {
            awaitReadPrefix(created);
            assertNotEquals(replacementInode, Os.stat(created.getPath()).st_ino);
            Files.move(replacement.toPath(), created.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            call("releaseRead", null);
            IOException failure = copyFailure(copying);
            assertTrue("The later source read must fail", causeContains(failure, "Injected read failure"));
            assertEquals(replacementInode, Os.stat(created.getPath()).st_ino);
            assertEquals("external-replacement", localText(created));
            assertEquals("existing-neighbour", localText(keep));
            assertFalse(new File(destination, "source/b-broken.txt").exists());
            assertEquals(List.of("source/", "source/a.txt", "source/b-broken.txt"), call("stats", null).getStringArrayList("paths"));
            call("configure", new Bundle());
            assertEquals("second-source", documentText("root/source/b-broken.txt"));
            assertEquals(new String(characters, 0, 4096), documentText("root/source/a.txt"));
        } finally {
            call("releaseRead", null); copying.cancel(true); worker.shutdownNow();
            assertTrue("Copy worker did not finish after pipe release", worker.awaitTermination(20, TimeUnit.SECONDS));
        }
    }
    @Test public void cleanupSecurityFailureIsSuppressedWithoutMaskingTheCreateFailure() throws Exception {
        seed("root", "keep.txt", false, "existing-neighbour");
        File source = directory(work, "source");
        File first = file(source, "a-good.txt", "first-source"), broken = file(source, "b-broken.txt", "second-source");
        Bundle configuration = new Bundle(); configuration.putString("failCreateName", "b-broken.txt");
        configuration.putString("failDeleteName", "a-good.txt"); call("configure", configuration);
        IOException failure;
        try {
            store.copyAll(List.of(FileStore.local(source)), documents, true);
            fail("Injected create failure was ignored"); return;
        } catch (IOException expected) { failure = expected; }
        assertTrue("Cleanup replaced the original create failure: " + failure, causeContains(failure, "Injected create failure"));
        assertTrue("Cleanup failure was not attached", failure.getSuppressed().length > 0);
        assertTrue("Injected delete SecurityException was lost", Arrays.stream(failure.getSuppressed())
                .anyMatch(error -> error instanceof SecurityException && causeContains(error, "Injected delete failure")));
        assertTrue(call("stats", null).getInt("deleteCalls") > 0);
        assertEquals("first-source", localText(first)); assertEquals("second-source", localText(broken));
        assertEquals("existing-neighbour", documentText("root/keep.txt"));
        assertEquals("first-source", documentText("root/source/a-good.txt"));
    }
    @Test public void measureLocalSmallFileBatchCopy() throws Exception {
        Assume.assumeFalse("Local benchmark excludes ROOT process overhead", RootAccess.available());
        org.json.JSONArray measurements = new org.json.JSONArray();
        for (int count : new int[]{1000, 5000, 10000}) {
            File source = directory(work, "source-" + count), destination = directory(work, "destination-" + count);
            List<FileStore.Node> inputs = new ArrayList<>();
            for (int i = 0; i < count; i++) inputs.add(FileStore.local(file(source, "item-" + i + ".txt", "payload-" + i)));
            long start = android.os.SystemClock.elapsedRealtimeNanos();
            List<FileStore.Node> copied = store.copyAll(inputs, FileStore.local(destination), false);
            double milliseconds = (android.os.SystemClock.elapsedRealtimeNanos() - start) / 1000000.0;
            assertEquals(count, copied.size()); assertEquals(count, Objects.requireNonNull(destination.list()).length);
            for (int i = 0; i < count; i++) assertEquals("payload-" + i, localText(copied.get(i).file));
            measurements.put(new org.json.JSONObject().put("files", count).put("elapsedMs", milliseconds));
        }
        org.json.JSONObject report = new org.json.JSONObject().put("backend", "local")
                .put("sdk", android.os.Build.VERSION.SDK_INT).put("measurements", measurements);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Files.write(new File(context.getExternalFilesDir(null), "batch-copy-benchmark.json").toPath(),
                report.toString(2).getBytes(StandardCharsets.UTF_8));
        android.util.Log.i("BatchCopyBenchmark", report.toString());
    }
    private void awaitReadPrefix(File created) throws Exception {
        long deadline = android.os.SystemClock.uptimeMillis() + 15000;
        do {
            Bundle state = call("readState", null);
            assertNull("Fixture pipe failed before release", state.getString("error"));
            if (state.getBoolean("opened") && state.getBoolean("prefixWritten") && created.length() > 0) return;
            Thread.sleep(10);
        } while (android.os.SystemClock.uptimeMillis() < deadline);
        fail("Copy did not write its first source prefix before waiting on the fixture pipe");
    }
    private static IOException copyFailure(Future<?> copying) throws Exception {
        try { copying.get(20, TimeUnit.SECONDS); fail("Copy unexpectedly completed"); return null; }
        catch (ExecutionException failure) {
            assertTrue("Unexpected copy error: " + failure.getCause(), failure.getCause() instanceof IOException);
            return (IOException) failure.getCause();
        }
    }
    private static boolean causeContains(Throwable failure, String expected) {
        for (Throwable error = failure; error != null; error = error.getCause())
            if (String.valueOf(error.getMessage()).contains(expected)) return true;
        return false;
    }
    private Bundle grantFixture(Context context) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Bundle> result = new AtomicReference<>();
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
        assertTrue("Fixture permission grant did not finish", finished.await(30, TimeUnit.SECONDS));
        assertNotNull(result.get());
        assertEquals(String.valueOf(result.get()), Activity.RESULT_OK, code.get());
        return result.get();
    }
    @Test public void cancelledCopyRollsBackTheItemInProgressAndKeepsSources() throws Exception {
        File source = directory(work, "tree"), destination = directory(work, "out");
        File neighbour = file(destination, "keep.txt", "neighbour");
        for (int i = 0; i < 30; i++) file(source, "f" + i + ".txt", "payload-" + i);
        Tasks.Progress progress = new Tasks.Progress();
        java.util.concurrent.atomic.AtomicInteger seen = new java.util.concurrent.atomic.AtomicInteger();
        try {
            progress.run(() -> {
                FileStore.Node node = FileStore.local(source);
                Thread watcher = new Thread(() -> {
                    while (progress.done.get() < 10) Thread.onSpinWait();
                    progress.cancelled.set(true); seen.set(progress.done.get());
                });
                watcher.start();
                try { return store.copyAll(List.of(node), FileStore.local(destination), false); }
                finally { watcher.join(5000); }
            });
            fail("Cancellation was ignored");
        } catch (Tasks.CancelledException expected) { }
        assertTrue("Cancellation happened mid-copy", seen.get() >= 10);
        assertEquals(List.of("keep.txt"), Arrays.asList(Objects.requireNonNull(destination.list())));
        assertEquals("neighbour", localText(neighbour));
        assertEquals("Sources are untouched", 30, Objects.requireNonNull(source.list()).length);
    }
    @Test public void cancellationBetweenTopLevelItemsKeepsFinishedOnes() throws Exception {
        File one = file(work, "one.txt", "1"), two = file(work, "two.txt", "2");
        File destination = directory(work, "dest");
        Tasks.Progress progress = new Tasks.Progress();
        try {
            progress.run(() -> {
                List<FileStore.Node> first = store.copyAll(List.of(FileStore.local(one)), FileStore.local(destination), false);
                progress.cancelled.set(true);
                store.copyAll(List.of(FileStore.local(two)), FileStore.local(destination), false);
                return first;
            });
            fail("Cancellation was ignored");
        } catch (Tasks.CancelledException expected) { }
        assertEquals("1", localText(new File(destination, "one.txt")));
        assertFalse(new File(destination, "two.txt").exists());
    }
    private Bundle call(String method, Bundle extras) {
        Bundle result = resolver.call(CONTROL, method, null, extras);
        assertNotNull("Fixture did not return " + method, result); return result;
    }
    private void seed(String parent, String name, boolean directory, String text) {
        Bundle extras = new Bundle(); extras.putString("parent", parent); extras.putString("name", name);
        extras.putBoolean("directory", directory); extras.putString("text", text); call("seed", extras);
    }
    private Uri documentUri(String id) { return DocumentsContract.buildDocumentUriUsingTree(rootUri, id); }
    private String documentText(String id) throws Exception {
        try (InputStream input = resolver.openInputStream(documentUri(id))) {
            assertNotNull(input); return new String(FileStore.readBytes(input, 4096), StandardCharsets.UTF_8);
        }
    }
    private String nodeText(FileStore.Node node) throws Exception {
        try (InputStream input = store.read(node)) { return new String(FileStore.readBytes(input, 4096), StandardCharsets.UTF_8); }
    }
    private static File directory(File parent, String name) {
        File directory = new File(parent, name); assertTrue(directory.mkdir()); return directory;
    }
    private static File file(File parent, String name, String text) throws Exception {
        File file = new File(parent, name); Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8)); return file;
    }
    private static String localText(File file) throws Exception { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
    private static void remove(File root) throws IOException {
        Files.walkFileTree(root.toPath(), new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path path, java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                Files.delete(path); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path path, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(path); return FileVisitResult.CONTINUE;
            }
        });
    }
}
