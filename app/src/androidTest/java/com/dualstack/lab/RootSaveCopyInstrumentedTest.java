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
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RootSaveCopyInstrumentedTest {
    private FileStore store;
    private File work, guarded;

    @Before public void setup() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try { Assume.assumeTrue("Requires granted su", FileStore.shell("id -u", true).trim().equals("0")); }
        catch (IOException e) { Assume.assumeNoException("Requires granted su", e); }
        store = new FileStore(context);
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "root-save-copy-").toFile();
        guarded = new File("/data/local/tmp/dualstack-root-save-" + UUID.randomUUID());
        root("mkdir -m 700 " + q(guarded));
    }
    @After public void cleanup() throws Exception {
        if (guarded != null) FileStore.shell("rm -rf -- " + q(guarded), true);
        if (work != null) store.delete(FileStore.directory(work, true));
    }
    @Test public void rootWriteRejectsLinksAndPreservesModeAndOwner() throws Exception {
        File target = new File(guarded, "real.txt"), link = new File(guarded, "link.txt");
        root("printf before > " + q(target) + "; chown 1000:1000 " + q(target) + "; chmod 4750 " + q(target)
                + "; ln -s real.txt " + q(link));
        File payload = local("payload", "after");
        try { store.write(FileStore.local(link, true), payload); fail("ROOT link was written"); }
        catch (IOException expected) { }
        assertTrue("ROOT write replaced the link", stat("%F", link).contains("link"));
        assertEquals("before", root("cat " + q(target)));
        store.write(FileStore.local(target, true), payload);
        assertEquals("after", root("cat " + q(target)));
        assertEquals("4750:1000:1000", stat("%a:%u:%g", target));
        assertEquals("", root("ls -A " + q(guarded) + " | grep dualstack || true"));
    }
    @Test public void rootBatchCopyAvoidsExistingNamesWithoutClobbering() throws Exception {
        root("printf existing > " + q(new File(guarded, "same.txt")));
        File one = new File(work, "one"), two = new File(work, "two");
        assertTrue(one.mkdir()); assertTrue(two.mkdir());
        File first = local(one, "same.txt", "first"), second = local(two, "same.txt", "second");
        List<FileStore.Node> copied = store.copyAll(List.of(FileStore.local(first), FileStore.local(second)),
                FileStore.directory(guarded, true), false);
        assertEquals("same (1).txt", copied.get(0).name); assertEquals("same (2).txt", copied.get(1).name);
        assertEquals("existing", root("cat " + q(new File(guarded, "same.txt"))));
        assertEquals("first", root("cat " + q(new File(guarded, "same (1).txt"))));
        assertEquals("second", root("cat " + q(new File(guarded, "same (2).txt"))));
    }
    @Test public void failedRootCopyRollsBackOnlyItsOwnOutputAndKeepsSource() throws Exception {
        root("printf neighbour > " + q(new File(guarded, "keep.txt")));
        File source = new File(work, "source"); assertTrue(source.mkdir());
        local(source, "a-good.txt", "good");
        root("ln -s missing-target " + q(new File(source, "b-broken.txt")));
        try {
            store.copyAll(List.of(FileStore.directory(source, false)), FileStore.directory(guarded, true), false);
            fail("Broken source entry was copied");
        } catch (IOException expected) { }
        assertEquals("keep.txt", root("ls -A " + q(guarded)));
        assertEquals("neighbour", root("cat " + q(new File(guarded, "keep.txt"))));
        assertEquals("good", new String(Files.readAllBytes(new File(source, "a-good.txt").toPath()), StandardCharsets.UTF_8));
        assertTrue(Files.isSymbolicLink(new File(source, "b-broken.txt").toPath()));
    }
    @Test public void rootCopyStreamsLocalFilesWithoutStagingAndVerifiesContent() throws Exception {
        File source = new File(work, "big.bin");
        byte[] data = new byte[3 * 1024 * 1024 + 17];
        new Random(7).nextBytes(data); Files.write(source.toPath(), data);
        File cache = InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir();
        Set<String> before = new HashSet<>(Arrays.asList(Objects.requireNonNull(cache.list())));
        List<FileStore.Node> copied = store.copyAll(List.of(FileStore.local(source)), FileStore.directory(guarded, true), false);
        Set<String> after = new HashSet<>(Arrays.asList(Objects.requireNonNull(cache.list())));
        after.removeAll(before);
        assertTrue("A plain local source must not be staged in cache: " + after,
                after.stream().noneMatch(name -> name.startsWith("root-copy-")));
        File copy = new File(guarded, "big.bin");
        assertEquals(copied.get(0).path(), copy.getPath());
        String digest = root("sha256sum " + q(copy)).split("\\s+")[0];
        java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
        StringBuilder expected = new StringBuilder();
        for (byte value : sha.digest(data)) expected.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        assertEquals(expected.toString(), digest);
    }
    @Test public void rootWriterRejectsSwappedTemporaryWithoutFollowingOrRemovingForeignLink() throws Exception {
        File target = new File(guarded, "original ' target.txt"), victim = new File(guarded, "victim.txt");
        File temporary = new File(guarded, ".dualstack ' pending"), saved = new File(guarded, "saved-payload");
        root("printf target-original > " + q(target) + " && chown 1000:1000 " + q(target)
                + " && chmod 0640 " + q(target) + " && printf victim-original > " + q(victim)
                + " && chown 0:0 " + q(victim) + " && chmod 0600 " + q(victim));
        String targetIdentity = stat("%d:%i", target), victimIdentity = stat("%d:%i", victim);
        assertRootFile(target, "target-original", "640:1000:1000");
        assertRootFile(victim, "victim-original", "600:0:0");
        // An old non-dereferencing stat sees the link's pathname length. Match it so its
        // size check passes and a pathname-based chown/chmod deterministically hits victim.
        String victimPath = victim.getAbsolutePath();
        byte[] pathBytes = victimPath.getBytes(StandardCharsets.US_ASCII);
        assertEquals(victimPath, new String(pathBytes, StandardCharsets.US_ASCII));
        byte[] payload = new byte[pathBytes.length]; Arrays.fill(payload, (byte) 'x');
        int prefixLength = 3;
        try (RootWriter writer = new RootWriter(target, temporary, payload.length)) {
            writer.input().write(payload, 0, prefixLength); writer.input().flush();
            String ownedIdentity = awaitPrefix(writer, temporary, prefixLength);
            root("/system/bin/toybox mv -T -- " + q(temporary) + " " + q(saved)
                    + " && ln -s -- " + q(victim) + " " + q(temporary));
            String foreignIdentity = stat("%d:%i", temporary);
            assertNotEquals(ownedIdentity, foreignIdentity);
            assertEquals(ownedIdentity, stat("%d:%i", saved));
            assertTrue("cat must still be blocked on the open stdin", writer.process.isAlive());
            writer.input().write(payload, prefixLength, payload.length - prefixLength);
            writer.input().close();
            String output = writer.awaitOutput();
            assertNotEquals("Foreign temporary was accepted: " + output, 0, writer.exitCode());
            assertEquals("Original target inode changed", targetIdentity, stat("%d:%i", target));
            assertEquals("Victim inode changed", victimIdentity, stat("%d:%i", victim));
            assertRootFile(target, "target-original", "640:1000:1000");
            assertRootFile(victim, "victim-original", "600:0:0");
            assertEquals("EXIT cleanup removed or claimed the foreign symlink", foreignIdentity,
                    stat("%d:%i", temporary));
            assertTrue(stat("%F", temporary).contains("link"));
            assertEquals(victimPath, root("readlink -- " + q(temporary)));
            assertEquals(ownedIdentity, stat("%d:%i", saved));
            assertArrayEquals("The pinned payload fd did not receive the remaining bytes", payload,
                    FileStore.shell("cat " + q(saved), true).getBytes(StandardCharsets.UTF_8));
        }
    }
    @Test public void rootWriterPinsOriginalParentAcrossRenameAndRecreatedPath() throws Exception {
        File parent = new File(guarded, "parent ' original"), moved = new File(guarded, "parent ' moved");
        File target = new File(parent, "target.txt"), temporary = new File(parent, ".dualstack-pending");
        File neighbour = new File(parent, "neighbour.txt");
        File movedTarget = new File(moved, target.getName()), movedTemporary = new File(moved, temporary.getName());
        File movedNeighbour = new File(moved, neighbour.getName());
        root("mkdir -m 700 " + q(parent) + " && printf target-original > " + q(target)
                + " && chown 1000:1000 " + q(target) + " && chmod 0640 " + q(target)
                + " && printf moved-neighbour > " + q(neighbour) + " && chown 0:0 " + q(neighbour)
                + " && chmod 0600 " + q(neighbour));
        String parentIdentity = stat("%d:%i", parent), neighbourIdentity = stat("%d:%i", neighbour);
        byte[] payload = "complete-payload-in-the-pinned-parent".getBytes(StandardCharsets.UTF_8);
        int prefixLength = 3;
        try (RootWriter writer = new RootWriter(target, temporary, payload.length)) {
            writer.input().write(payload, 0, prefixLength); writer.input().flush();
            String ownedIdentity = awaitPrefix(writer, temporary, prefixLength);
            root("/system/bin/toybox mv -T -- " + q(parent) + " " + q(moved)
                    + " && mkdir -m 700 " + q(parent)
                    + " && printf replacement-target-victim > " + q(target) + " && chown 0:0 " + q(target)
                    + " && chmod 0600 " + q(target)
                    + " && printf replacement-temporary-victim > " + q(temporary) + " && chown 0:0 " + q(temporary)
                    + " && chmod 0600 " + q(temporary));
            String replacementTargetIdentity = stat("%d:%i", target);
            String replacementTemporaryIdentity = stat("%d:%i", temporary);
            assertEquals(parentIdentity, stat("%d:%i", moved));
            assertNotEquals(parentIdentity, stat("%d:%i", parent));
            assertEquals(ownedIdentity, stat("%d:%i", movedTemporary));
            assertTrue("cat must still be blocked on the open stdin", writer.process.isAlive());
            writer.input().write(payload, prefixLength, payload.length - prefixLength);
            writer.input().close();
            String output = writer.awaitOutput();
            assertEquals("Pinned-directory write failed: " + output, 0, writer.exitCode());
            assertEquals("Published identity must be the pinned temporary inode", ownedIdentity, output.trim());
            assertEquals(ownedIdentity, stat("%d:%i", movedTarget));
            assertRootFile(movedTarget, new String(payload, StandardCharsets.UTF_8), "640:1000:1000");
            root("test ! -e " + q(movedTemporary) + " && test ! -L " + q(movedTemporary));
            assertEquals(neighbourIdentity, stat("%d:%i", movedNeighbour));
            assertRootFile(movedNeighbour, "moved-neighbour", "600:0:0");
            assertEquals(replacementTargetIdentity, stat("%d:%i", target));
            assertRootFile(target, "replacement-target-victim", "600:0:0");
            assertEquals(replacementTemporaryIdentity, stat("%d:%i", temporary));
            assertRootFile(temporary, "replacement-temporary-victim", "600:0:0");
        }
    }
    @Test public void rootWriteRejectsStaleFingerprintAfterSameMtimeSameLengthInPlaceChange() throws Exception {
        File target = new File(guarded, "revision.txt"), timestamp = new File(guarded, "timestamp-reference");
        root("printf before > " + q(target) + " && chown 1000:1000 " + q(target)
                + " && chmod 0640 " + q(target) + " && touch -t 202001020304.05 " + q(target)
                + " && cp -p -- " + q(target) + " " + q(timestamp));
        FileStore.Node node = FileStore.local(target, true);
        String expectedFingerprint = store.fingerprint(node);
        String metadata = stat("%d:%i:%s:%Y:%a:%u:%g", target);
        root("printf change > " + q(target) + " && touch -r " + q(timestamp) + " " + q(target));
        assertEquals("Only file content should change, not inode, size, mtime or permissions",
                metadata, stat("%d:%i:%s:%Y:%a:%u:%g", target));
        String changedFingerprint = store.fingerprint(node);
        assertNotEquals("Fingerprint must include content, not just metadata", expectedFingerprint, changedFingerprint);
        File payload = local("stale-edit-payload", "edited");
        try {
            store.write(node, payload, expectedFingerprint);
            fail("A stale expectedFingerprint overwrote a same-size, same-mtime external edit");
        } catch (IOException expected) {
            assertTrue("Expected a revision conflict, got: " + expected,
                    expected.getMessage() != null && expected.getMessage().contains("原文件已在外部改变"));
        }
        assertRootFile(target, "change", "640:1000:1000");
        assertEquals(metadata, stat("%d:%i:%s:%Y:%a:%u:%g", target));
        assertEquals(changedFingerprint, store.fingerprint(node));
        assertEquals("revision.txt\ntimestamp-reference", root("ls -A " + q(guarded)));
    }
    private static void assertRootFile(File file, String text, String permissions) throws IOException {
        assertArrayEquals("Unexpected bytes in " + file, text.getBytes(StandardCharsets.UTF_8),
                FileStore.shell("cat " + q(file), true).getBytes(StandardCharsets.UTF_8));
        assertEquals("Unexpected mode/uid/gid in " + file, permissions, stat("%a:%u:%g", file));
    }
    private static String awaitPrefix(RootWriter writer, File temporary, int length) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        String prefix = length + ":";
        do {
            assertTrue("Root helper exited before receiving the prefix", writer.process.isAlive());
            String command = "if test -f " + q(temporary) + " && test ! -L " + q(temporary)
                    + "; then /system/bin/toybox stat -c '%s:%d:%i' -- " + q(temporary) + "; fi";
            String state;
            try (RootWriter probe = new RootWriter(command)) {
                probe.input().close();
                state = probe.awaitOutput(deadline).trim();
                assertEquals("Root prefix probe failed: " + state, 0, probe.exitCode());
            }
            if (state.startsWith(prefix)) return state.substring(prefix.length());
            // A short bounded poll, not a fixed delay used to guess whether cat has opened.
            if (System.nanoTime() < deadline) Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Timed out waiting for the actual prefix in " + temporary);
    }
    private static final class RootWriter implements AutoCloseable {
        final Process process;
        private final FutureTask<String> drain;
        private String output;
        RootWriter(File target, File temporary, int size) throws IOException {
            this(FileStore.rootWriteCommand(InstrumentationRegistry.getInstrumentation().getTargetContext()
                    .getApplicationInfo().sourceDir, target.getAbsolutePath(), temporary.getAbsolutePath(), size, null));
        }
        RootWriter(String command) throws IOException {
            process = RootShell.command(command).redirectErrorStream(true).start();
            drain = new FutureTask<>(() -> {
                ByteArrayOutputStream retained = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096]; int count;
                try (InputStream input = process.getInputStream()) {
                    while ((count = input.read(buffer)) != -1) {
                        int keep = Math.min(count, 65536 - retained.size());
                        if (keep > 0) retained.write(buffer, 0, keep);
                    }
                }
                return new String(retained.toByteArray(), StandardCharsets.UTF_8);
            });
            Thread reader = new Thread(drain, "root-save-copy-output");
            reader.setDaemon(true); reader.start();
        }
        OutputStream input() { return process.getOutputStream(); }
        String awaitOutput() throws Exception { return awaitOutput(System.nanoTime() + TimeUnit.SECONDS.toNanos(15)); }
        String awaitOutput(long deadline) throws Exception {
            assertTrue("Root helper did not exit after stdin closed", process.waitFor(
                    Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
            output = drain.get(5, TimeUnit.SECONDS);
            return output;
        }
        int exitCode() { return process.exitValue(); }
        @Override public void close() throws Exception {
            try { input().close(); } catch (IOException ignored) { }
            try {
                if (process.isAlive()) {
                    process.destroy();
                    if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
                }
            } finally {
                try { process.getInputStream().close(); } catch (IOException ignored) { }
                drain.cancel(true);
            }
        }
    }
    private static String root(String command) throws IOException { return FileStore.shell(command, true).trim(); }
    private static String stat(String format, File file) throws IOException {
        return root("/system/bin/toybox stat -c '" + format + "' " + q(file));
    }
    private static String q(File file) { return FileStore.quote(file.getAbsolutePath()); }
    private File local(String name, String text) throws IOException { return local(work, name, text); }
    private static File local(File parent, String name, String text) throws IOException {
        File file = new File(parent, name); Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8)); return file;
    }
}
