package com.dualstack.lab;

import android.content.Context;
import android.os.SystemClock;
import android.system.Os;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RootStreamingCopyInstrumentedTest {
    private Context context;
    private File work;
    private RootMutations.Session session;
    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "root-stream-").toFile();
        session = new RootMutations.Session(context, false);
    }
    @After public void cleanup() throws Exception {
        session.close(); new FileStore(context).delete(FileStore.local(work));
    }
    @Test public void chunksPreserveTheCreatedInodeMetadataAndCountBytes() throws Exception {
        byte[] bytes = new byte[327681]; new Random(21).nextBytes(bytes);
        RootFileOps.Result created = create("chunks.bin");
        Tasks.Progress progress = new Tasks.Progress();
        RootFileOps.Result written = progress.run(() -> session.execute(request(created, bytes.length), null, new ByteArrayInputStream(bytes)));
        assertEquals(created.identity, written.identity); assertEquals(created.mode, written.mode);
        assertEquals(created.uid, written.uid); assertEquals(created.gid, written.gid);
        assertEquals(bytes.length, written.size); assertEquals(bytes.length, progress.transferred.get());
        assertArrayEquals(bytes, Files.readAllBytes(new File(written.path).toPath()));
        RootFileOps.Result zero = create("zero.bin");
        assertEquals(0, session.execute(request(zero, 0), null, new ByteArrayInputStream(new byte[0])).size);
        assertTrue(session.isAlive());
    }
    @Test public void shortLongAndInvalidLengthsFinishTheirRequestBeforeReuse() throws Exception {
        for (long size : new long[]{0, 2, -2}) {
            RootFileOps.Result created = create("size-" + size);
            expectFailure(() -> session.execute(request(created, size), null, new ByteArrayInputStream(new byte[]{9})));
            assertTrue(session.isAlive());
        }
        RootFileOps.Result unknown = create("unknown");
        assertEquals(3, session.execute(request(unknown, -1), null, new ByteArrayInputStream(new byte[]{1, 2, 3})).size);
    }
    @Test public void sourceReadFailureIsDrainedAndTheNextRequestWorks() throws Exception {
        RootFileOps.Result created = create("read-failure");
        InputStream broken = new InputStream() {
            int reads;
            public int read() throws IOException { throw new IOException("fixture source failure"); }
            public int read(byte[] buffer, int offset, int count) throws IOException {
                if (reads++ > 0) throw new IOException("fixture source failure");
                Arrays.fill(buffer, offset, offset + 8, (byte) 7); return 8;
            }
        };
        IOException error = expectFailure(() -> session.execute(request(created, -1), null, broken));
        assertTrue(error.getMessage(), error.getMessage().contains("fixture source failure"));
        assertEquals(8, new File(created.path).length()); assertTrue(session.isAlive());
        RootFileOps.Result next = create("after-failure");
        session.execute(request(next, 1), null, new ByteArrayInputStream(new byte[]{3}));
        assertArrayEquals(new byte[]{3}, Files.readAllBytes(new File(next.path).toPath()));
    }
    @Test public void cancellationAtTheFinalBoundaryKeepsProtocolAndOwnedRollback() throws Exception {
        RootFileOps.Result created = create("cancelled");
        try {
            session.execute(request(created, 3), (code, text) -> !text.startsWith("sync:"), new ByteArrayInputStream(new byte[]{4, 5, 6}));
            fail("Must cancel after the final data block");
        } catch (Tasks.CancelledException expected) { }
        assertTrue(session.isAlive());
        session.execute(RootFileOps.Request.deleteEmpty(List.of(spec(created))), null);
        assertFalse(new File(created.path).exists());
        assertNotNull(create("after-cancel"));
    }
    @Test public void cancellationDuringSourceReadKeepsCallingThreadProgress() throws Exception {
        RootFileOps.Result created = create("during-read"); Tasks.Progress progress = new Tasks.Progress();
        InputStream cancelled = new ByteArrayInputStream(new byte[RootFileOps.PAYLOAD_LIMIT * 2]) {
            int reads;
            @Override public synchronized int read(byte[] buffer, int offset, int count) {
                int result = super.read(buffer, offset, count);
                if (++reads == 2) progress.cancelled.set(true);
                return result;
            }
        };
        try { progress.run(() -> session.execute(request(created, -1), null, cancelled)); fail("Must cancel source read"); }
        catch (Tasks.CancelledException expected) { }
        assertEquals(RootFileOps.PAYLOAD_LIMIT, progress.transferred.get());
        assertEquals(RootFileOps.PAYLOAD_LIMIT, new File(created.path).length());
        assertTrue(session.isAlive()); assertNotNull(create("after-source-cancel"));
    }
    @Test public void nonemptyAndSymlinkTargetsAreNeverWritten() throws Exception {
        RootFileOps.Result used = create("used"); Files.write(new File(used.path).toPath(), new byte[]{8});
        expectFailure(() -> session.execute(request(used, 1), null, new ByteArrayInputStream(new byte[]{9})));
        assertArrayEquals(new byte[]{8}, Files.readAllBytes(new File(used.path).toPath()));
        File symlink = new File(work, "link"); Os.symlink(used.path, symlink.getPath());
        FileStore.Node link = FileStore.local(symlink, true);
        RootFileOps.Spec linkSpec = new RootFileOps.Spec(link.path(), link.identity, link.parentIdentity, null, false);
        expectFailure(() -> session.execute(RootFileOps.Request.writeEmpty(linkSpec, 1), null, new ByteArrayInputStream(new byte[]{9})));
        assertTrue(session.isAlive());
    }
    @Test public void hardlinkTargetsAreNeverWrittenWhenPlatformAllowsLinks() throws Exception {
        RootFileOps.Result shared = create("hardlinked");
        try { Os.link(shared.path, new File(work, "second-name").getPath()); }
        catch (android.system.ErrnoException denied) { Assume.assumeNoException("Platform forbids app hardlinks", denied); }
        expectFailure(() -> session.execute(request(shared, 1), null, new ByteArrayInputStream(new byte[]{9})));
        assertEquals(0, new File(shared.path).length()); assertTrue(session.isAlive());
    }
    @Test public void abortClosesABlockedSourceAndReleasesTheCallingThread() throws Exception {
        RootFileOps.Result created = create("blocked-source");
        java.util.concurrent.CountDownLatch reading = new java.util.concurrent.CountDownLatch(1), closed = new java.util.concurrent.CountDownLatch(1);
        InputStream blocking = new InputStream() {
            public int read() throws IOException {
                reading.countDown();
                try { if (!closed.await(10, TimeUnit.SECONDS)) throw new IOException("source was not closed"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
                throw new IOException("source closed");
            }
            public void close() { closed.countDown(); }
        };
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Thread writer = new Thread(() -> {
            try { session.execute(request(created, -1), null, blocking); }
            catch (Throwable error) { failure.set(error); }
        });
        writer.start(); assertTrue(reading.await(5, TimeUnit.SECONDS));
        java.lang.reflect.Method abort = RootMutations.Session.class.getDeclaredMethod("abort"); abort.setAccessible(true); abort.invoke(session);
        writer.join(5000); assertFalse(writer.isAlive()); assertNotNull(failure.get()); assertFalse(session.isAlive());
    }
    @Test public void fileStoreArchiveFallbackStreamsThroughTheExistingHelper() throws Exception {
        RootFileOps.Result created = create("fallback.bin");
        File archive = new File(work, "fallback.zip"); byte[] payload = new byte[32768]; new Random(7).nextBytes(payload);
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("payload.bin")); zip.write(payload); zip.closeEntry();
        }
        // A different app-view parent makes the ordinary FD guard decline the fast path.
        // The helper receives the real absolute path and checks the real ROOT parent itself.
        File appView = new File(created.path) { @Override public String getParent() { return "/"; } };
        FileStore.Node target = new FileStore.Node("fallback.bin", appView, null, false, true, 0, 0);
        target.identity = created.identity; target.parentIdentity = created.parentIdentity; target.identityFollowsLinks = false;
        java.lang.reflect.Field cache = RootMutations.class.getDeclaredField("cached"), root = RootMutations.class.getDeclaredField("cachedRoot");
        cache.setAccessible(true); root.setAccessible(true); cache.set(null, session); root.set(null, true);
        FileStore store = new FileStore(context); FileStore.CopyLedger ledger = store.new CopyLedger(); ledger.record(target);
        java.lang.reflect.Method copy = FileStore.class.getDeclaredMethod("copyContent", FileStore.Node.class, FileStore.Node.class, Set.class, FileStore.CopyLedger.class);
        copy.setAccessible(true);
        try {
            copy.invoke(store, FileStore.archiveNode(FileStore.local(archive), "payload.bin", false, 0, 0), target, new HashSet<String>(), ledger);
            assertArrayEquals(payload, Files.readAllBytes(new File(created.path).toPath()));
            assertEquals(created.identity, FileStore.local(new File(created.path)).identity);
            assertSame(session, cache.get(null));
        } finally { RootMutations.close(); }
    }
    @Test public void abortUnblocksAnAndroidPipeSource() throws Exception {
        RootFileOps.Result created = create("android-pipe");
        android.os.ParcelFileDescriptor[] pipe = android.os.ParcelFileDescriptor.createPipe();
        try (InputStream source = new android.os.ParcelFileDescriptor.AutoCloseInputStream(pipe[0]);
                android.os.ParcelFileDescriptor writerEnd = pipe[1]) {
            java.util.concurrent.CountDownLatch reading = new java.util.concurrent.CountDownLatch(1);
            InputStream observed = new FilterInputStream(source) {
                @Override public int read(byte[] buffer, int offset, int count) throws IOException {
                    reading.countDown(); return super.read(buffer, offset, count);
                }
            };
            Thread worker = new Thread(() -> {
                try { session.execute(request(created, -1), null, observed); }
                catch (IOException expected) { }
            });
            worker.start(); assertTrue(reading.await(5, TimeUnit.SECONDS));
            SystemClock.sleep(50);
            java.lang.reflect.Method abort = RootMutations.Session.class.getDeclaredMethod("abort"); abort.setAccessible(true); abort.invoke(session);
            worker.join(5000); assertFalse("Closing the source must release Android pipe read", worker.isAlive());
        }
    }
    @Test public void replacedLeafAfterPrepareDoesNotTouchForeignData() throws Exception {
        RootFileOps.Result created = create("race"); File original = new File(work, "original");
        expectFailure(() -> session.execute(request(created, 1), (code, text) -> {
            if (code == RootFileOps.PREPARED) {
                Files.move(new File(created.path).toPath(), original.toPath());
                Files.write(new File(created.path).toPath(), new byte[]{6});
            }
            return true;
        }, new ByteArrayInputStream(new byte[]{9})));
        assertArrayEquals(new byte[]{6}, Files.readAllBytes(new File(created.path).toPath()));
        assertEquals(0, original.length()); assertTrue(session.isAlive());
    }
    @Test public void fortyMegabyteZipSmallMemberReusesTheRootWriterProcess() throws Exception {
        File archive = new File(work, "forty-megabytes.zip");
        byte[] chunk = new byte[1024 * 1024], payload = new byte[32768];
        new Random(40).nextBytes(chunk); new Random(21).nextBytes(payload);
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(archive)))) {
            zip.setLevel(Deflater.NO_COMPRESSION); zip.putNextEntry(new ZipEntry("unused.bin"));
            for (int i = 0; i < 40; i++) zip.write(chunk);
            zip.closeEntry(); zip.putNextEntry(new ZipEntry("payload.bin")); zip.write(payload); zip.closeEntry();
        }
        assertTrue(archive.length() > 40L * 1024 * 1024);
        List<Long> oldTimes = new ArrayList<>(), streamedTimes = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            RootFileOps.Result old = create("old-" + i), direct = create("direct-" + i);
            long start = SystemClock.elapsedRealtime();
            File staged = new File(work, "staged-" + i);
            try (InputStream input = ArchiveOps.openEntry(archive, "payload.bin"); OutputStream output = new FileOutputStream(staged)) {
                FileStore.pump(input, output);
            }
            String command = FileStore.rootWriteCommand(context.getApplicationInfo().sourceDir, old.path, old.path + ".tmp", payload.length, "");
            Process writer = new ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(true).start();
            try (InputStream input = new FileInputStream(staged); OutputStream output = writer.getOutputStream()) { FileStore.pump(input, output); }
            String response = new String(FileStore.readBytes(writer.getInputStream(), 8192), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(writer.waitFor(30, TimeUnit.SECONDS)); assertEquals(response, 0, writer.exitValue());
            oldTimes.add(SystemClock.elapsedRealtime() - start);
            start = SystemClock.elapsedRealtime();
            try (ArchiveOps.ReadSession reader = new ArchiveOps.ReadSession(archive); InputStream input = reader.open("payload.bin")) {
                session.execute(request(direct, reader.size("payload.bin")), null, input);
            }
            streamedTimes.add(SystemClock.elapsedRealtime() - start);
            assertArrayEquals(payload, Files.readAllBytes(new File(direct.path).toPath()));
            assertArrayEquals(payload, Files.readAllBytes(new File(old.path).toPath()));
        }
        String result = "zipBytes=" + archive.length() + " selectedBytes=" + payload.length + " oldWriterMs=" + oldTimes + " reusableWriterMs=" + streamedTimes;
        android.util.Log.i("RootStreamingTiming", result);
        Files.write(new File(context.getExternalFilesDir(null), "root-streaming-timing.txt").toPath(), result.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private RootFileOps.Result create(String name) throws IOException {
        FileStore.Node directory = FileStore.directory(work, true);
        return session.execute(RootFileOps.Request.create(new RootFileOps.Spec(work.getPath(), null, null, directory.directoryIdentity, true), name, false), null);
    }
    private RootFileOps.Spec spec(RootFileOps.Result node) {
        return new RootFileOps.Spec(node.path, node.identity, node.parentIdentity, null, false);
    }
    private RootFileOps.Request request(RootFileOps.Result node, long size) { return RootFileOps.Request.writeEmpty(spec(node), size); }
    private interface Failing { void run() throws Exception; }
    private IOException expectFailure(Failing operation) throws Exception {
        try { operation.run(); fail("Expected guarded failure"); return null; }
        catch (IOException expected) { return expected; }
    }
}
