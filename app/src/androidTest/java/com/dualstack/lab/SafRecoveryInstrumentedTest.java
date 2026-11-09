package com.dualstack.lab;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.system.Os;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SafRecoveryInstrumentedTest {
    private static final Uri CONTROL = Uri.parse("content://" + CountingDocumentsProvider.AUTHORITY);
    private static final String SOURCE = "source.bin";
    private Context context;
    private ContentResolver resolver;
    private File work, payload;
    private Uri rootUri, sourceUri;
    private FileStore.Node node;
    private byte[] original, replacement;
    private ActivityScenario<MainActivity> recoveryScenario;
    private File promotedRecord;
    private Map<String, ?> mainPreferences, recoveryPreferences;

    @Before public void setup() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        resolver = target.getContentResolver();
        recoveryPreferences = new HashMap<>(target.getSharedPreferences("SafRecovery", 0).getAll());
        // ContextWrapper intentionally only overrides files: preferences are shared with the real UI.
        SafRecovery.setEnabled(target, true);
        SafRecovery.setBackupDirectory(target, null);
        // Exercise the real private-files protocol without touching a user's or another test's recovery records.
        work = Files.createTempDirectory(target.getFilesDir().getCanonicalFile().toPath(), "saf-recovery-test-").toFile();
        File privateFiles = new File(work, "files"); assertTrue(privateFiles.mkdir());
        context = new ContextWrapper(target) {
            @Override public File getFilesDir() { return privateFiles; }
        };
        rootUri = Uri.parse(grantFixture(target).getString("uri"));
        sourceUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, "root/" + SOURCE);
        original = bytes(256 * 1024 + 37, 0x728aL); replacement = bytes(193 * 1024 + 19, 0x1234L);
        Bundle seed = new Bundle(); seed.putString("name", SOURCE); seed.putByteArray("bytes", original);
        call("seed", seed);
        node = new FileStore(context).document(sourceUri);
        payload = new File(work, "payload.bin"); Files.write(payload.toPath(), replacement);
        call("clearCounters", null);
    }
    @After public void cleanup() throws Exception {
        if (recoveryScenario != null) recoveryScenario.close();
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        if (mainPreferences != null) restorePreferences(target, "MainActivity", mainPreferences);
        if (recoveryPreferences != null) restorePreferences(target, "SafRecovery", recoveryPreferences);
        if (promotedRecord != null && promotedRecord.exists()) remove(promotedRecord);
        if (rootUri != null) call("reset", null);
        if (work != null) remove(work);
    }

    @Test public void verifiedSuccessCleansIndependentPrivateBackup() throws Exception {
        String expected = new FileStore(context).fingerprint(node);
        call("clearCounters", null);
        SafRecovery.write(context, node, payload, expected);
        assertArrayEquals(replacement, sourceBytes());
        assertTrue(SafRecovery.pending(context).isEmpty());
        assertEquals(0, Objects.requireNonNull(SafRecovery.directory(context).list()).length);
        Bundle stats = call("stats", null);
        assertEquals(1, stats.getInt("writeOpens"));
        assertEquals("Backup, final revision check, read-back, and this assertion read the document", 4, stats.getInt("readOpens"));
    }

    @Test public void synchronousTruncateFailureKeepsEveryOriginalByteAndRestartRecord() throws Exception {
        configure("truncateWriteName", SOURCE);
        SafRecovery.RecoveryException error = failedWrite();
        assertTrue(error.getMessage().contains("原件可能已截断或不完整"));
        assertTrue(error.getMessage().contains(error.record.backup.getAbsolutePath()));
        assertArrayEquals(Arrays.copyOf(original, 3), sourceBytes());
        assertBackup(error.record);
        assertEquals(0600, Os.stat(error.record.backup.getPath()).st_mode & 0777);
        assertEquals(0700, Os.stat(error.record.directory.getPath()).st_mode & 0777);
        List<SafRecovery.Record> restarted = SafRecovery.pending(context);
        assertEquals(1, restarted.size());
        assertNotSame("Scan must load metadata, not an in-memory registry", error.record, restarted.get(0));
        assertEquals(sourceUri, restarted.get(0).uri); assertEquals(SOURCE, restarted.get(0).name);
        assertEquals(error.record.directory.getCanonicalFile(), restarted.get(0).directory);
        assertBackup(restarted.get(0));
        assertSame(error, SafRecovery.findRecovery(new IOException("outer", error)));
    }

    @Test public void repeatedFailedWritesNeverOverwriteAnEarlierOriginalBackup() throws Exception {
        configure("truncateWriteName", SOURCE);
        SafRecovery.Record first = failedWrite().record;
        SafRecovery.Record second = failedWrite().record;
        assertNotEquals(first.directory, second.directory);
        assertBackup(first);
        assertArrayEquals(Arrays.copyOf(original, 3), Files.readAllBytes(second.backup.toPath()));
        assertEquals(2, SafRecovery.pending(context).size());
    }

    @Test public void readBackMismatchIsNotSuccessfulAndNeverDeletesBackup() throws Exception {
        configure("corruptWriteName", SOURCE);
        SafRecovery.RecoveryException error = failedWrite();
        assertTrue(causeContains(error, "read-back SHA-256"));
        assertFalse(Arrays.equals(replacement, sourceBytes()));
        assertBackup(error.record); assertEquals(1, SafRecovery.pending(context).size());
    }

    @Test public void reliableMidReadFailureNeverOpensWtOrPublishesShortRecoveryRecord() throws Exception {
        configure("failReadAfterPrefixName", SOURCE);
        try { SafRecovery.write(context, node, payload); fail("A reliable-pipe read failure was treated as EOF"); }
        catch (IOException error) {
            assertNull("The source was never opened for writing", SafRecovery.findRecovery(error));
            assertTrue("Provider error must be observed, not silently accepted", causeContains(error, "Injected mid-read failure"));
        }
        assertEquals(0, call("stats", null).getInt("writeOpens"));
        assertTrue(SafRecovery.pending(context).isEmpty());
        configure(null, null);
        assertArrayEquals(original, sourceBytes());
    }

    @Test public void failedBackupDirectoryCannotTruncateSource() throws Exception {
        assertTrue(SafRecovery.directory(context).createNewFile());
        try { SafRecovery.write(context, node, payload); fail("Backup failure was ignored"); }
        catch (IOException error) { assertNull(SafRecovery.findRecovery(error)); }
        assertEquals(0, call("stats", null).getInt("writeOpens"));
        assertArrayEquals(original, sourceBytes());
    }

    @Test public void metadataFailureAfterFullBackupNeverOpensWtAndRetainsFullOriginal() throws Exception {
        configure("blockReadName", SOURCE);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Future<?> saving = worker.submit(() -> { SafRecovery.write(context, node, payload); return null; });
        try {
            awaitBlockedRead();
            File[] operations = SafRecovery.directory(context).listFiles();
            assertNotNull(operations); assertEquals(1, operations.length);
            // A directory at the atomic-metadata staging path deterministically makes FileOutputStream fail.
            assertTrue(new File(operations[0], SafRecovery.METADATA_TEMP).mkdir());
            call("releaseRead", null);
            IOException error = failure(saving);
            assertNull(SafRecovery.findRecovery(error));
            assertEquals(0, call("stats", null).getInt("writeOpens"));
            assertArrayEquals(original, Files.readAllBytes(new File(operations[0], SafRecovery.BACKUP).toPath()));
            assertTrue(SafRecovery.pending(context).isEmpty());
            assertArrayEquals(original, sourceBytes());
        } finally {
            call("releaseRead", null); saving.cancel(true); worker.shutdownNow();
            assertTrue(worker.awaitTermination(20, TimeUnit.SECONDS));
        }
    }

    @Test public void explicitRestoreUsesOnDiskRecordAndVerifiesOriginalBeforeCleanup() throws Exception {
        configure("truncateWriteName", SOURCE); failedWrite();
        SafRecovery.Record record = SafRecovery.pending(context).get(0);
        configure(null, null);
        byte[] externalVersion = bytes(47, 0x8765L);
        try (OutputStream output = resolver.openOutputStream(sourceUri, "wt")) {
            assertNotNull(output); output.write(externalVersion);
        }
        assertArrayEquals("No automatic rollback may replace this external edit", externalVersion, sourceBytes());
        // Production UI requires an explicit overwrite confirmation before calling this API.
        SafRecovery.restore(context, record);
        assertArrayEquals(original, sourceBytes());
        assertFalse(record.backup.exists()); assertFalse(record.metadata.exists());
        assertTrue(SafRecovery.pending(context).isEmpty());
    }

    @Test public void failedRestoreKeepsSameBackupForAnotherExplicitAttempt() throws Exception {
        configure("truncateWriteName", SOURCE); failedWrite();
        SafRecovery.Record record = SafRecovery.pending(context).get(0);
        try { SafRecovery.restore(context, record); fail("Restore failure was ignored"); }
        catch (SafRecovery.RecoveryException error) {
            assertEquals(record.directory, error.record.directory); assertBackup(error.record);
        }
        assertEquals(1, SafRecovery.pending(context).size()); assertBackup(record);
        configure(null, null);
        SafRecovery.restore(context, SafRecovery.pending(context).get(0));
        assertArrayEquals(original, sourceBytes()); assertTrue(SafRecovery.pending(context).isEmpty());
    }

    @Test public void restoreReadBackMismatchAlsoRetainsOriginalBackup() throws Exception {
        configure("truncateWriteName", SOURCE); failedWrite();
        SafRecovery.Record record = SafRecovery.pending(context).get(0);
        configure("corruptWriteName", SOURCE);
        try { SafRecovery.restore(context, record); fail("Restore read-back mismatch was ignored"); }
        catch (SafRecovery.RecoveryException error) { assertTrue(causeContains(error, "read-back SHA-256")); }
        assertBackup(record); assertEquals(1, SafRecovery.pending(context).size());
    }

    @Test public void corruptBackupCannotBeRestoredAndDoesNotOpenWt() throws Exception {
        configure("truncateWriteName", SOURCE); failedWrite();
        SafRecovery.Record record = SafRecovery.pending(context).get(0);
        Files.write(record.backup.toPath(), new byte[]{1, 2, 3});
        call("clearCounters", null); configure(null, null);
        try { SafRecovery.restore(context, record); fail("Corrupt backup was accepted"); }
        catch (SafRecovery.RecoveryException error) { assertTrue(causeContains(error, "恢复备份校验失败")); }
        assertEquals(0, call("stats", null).getInt("writeOpens"));
        assertTrue(record.backup.exists()); assertTrue(record.metadata.exists());
        assertArrayEquals(Arrays.copyOf(original, 3), sourceBytes());
    }

    @Test public void staleExpectedFingerprintAfterBackupDoesNotWriteOrOfferRollback() throws Exception {
        FileStore store = new FileStore(context);
        String expected = store.fingerprint(node);
        byte[] externalVersion = bytes(657, 0x4321L);
        try (OutputStream output = resolver.openOutputStream(sourceUri, "wt")) {
            assertNotNull(output); output.write(externalVersion);
        }
        call("clearCounters", null);
        try { SafRecovery.write(context, node, payload, expected); fail("Revision conflict was ignored"); }
        catch (IOException error) {
            assertNull("Conflict is not a partially written source", SafRecovery.findRecovery(error));
            assertTrue(error.getMessage().contains("外部改变"));
        }
        assertEquals(0, call("stats", null).getInt("writeOpens"));
        assertArrayEquals(externalVersion, sourceBytes());
        assertTrue("Prepared-only backups must not offer recovery over the newer revision", SafRecovery.pending(context).isEmpty());
        File[] operations = SafRecovery.directory(context).listFiles(); assertNotNull(operations); assertEquals(1, operations.length);
        assertArrayEquals(externalVersion, Files.readAllBytes(new File(operations[0], SafRecovery.BACKUP).toPath()));
        SafRecovery.Entry prepared = SafRecovery.entries(context).get(0);
        assertFalse(prepared.recoverable()); assertNotNull(prepared.problem()); assertNotNull(prepared.record());
    }

    @Test public void startupRecoveryRequiresConfirmationAndCanRestoreThroughTheUi() throws Exception {
        configure("truncateWriteName", SOURCE);
        SafRecovery.Record record = failedWrite().record;
        configure(null, null);
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File realRoot = SafRecovery.directory(target);
        assertTrue(realRoot.isDirectory() || realRoot.mkdir());
        promotedRecord = new File(realRoot, record.directory.getName());
        assertFalse(promotedRecord.exists());
        Files.move(record.directory.toPath(), promotedRecord.toPath(), StandardCopyOption.ATOMIC_MOVE);
        byte[] external = bytes(47, 0x9988L);
        try (OutputStream output = resolver.openOutputStream(sourceUri, "wt")) {
            assertNotNull(output); output.write(external);
        }
        mainPreferences = new HashMap<>(target.getSharedPreferences("MainActivity", 0).getAll());
        target.getSharedPreferences("MainActivity", 0).edit().clear()
                .putString("left", work.getPath()).putString("right", work.getPath()).commit();
        recoveryScenario = ActivityScenario.launch(MainActivity.class);
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        selectRecovery(device);
        assertArrayEquals(external, sourceBytes());
        assertTrue(device.takeScreenshot(new File(target.getExternalFilesDir(null), "review-saf-recovery.png")));
        click(device, "恢复原件");
        assertNotNull(device.wait(Until.findObject(By.text("确认覆盖来源当前内容？")), 15000));
        assertArrayEquals(external, sourceBytes());
        click(device, "取消");
        assertArrayEquals(external, sourceBytes());
        assertTrue(new File(promotedRecord, SafRecovery.BACKUP).isFile());
        recoveryScenario.onActivity(SafRecovery::offerPending);
        selectRecovery(device);
        click(device, "恢复原件"); click(device, "确认覆盖并恢复");
        long deadline = SystemClock.uptimeMillis() + 15000;
        while (promotedRecord.exists() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(40);
        assertFalse("Verified UI restore must remove its own recovery record", promotedRecord.exists());
        assertArrayEquals(original, sourceBytes());
    }
    @Test public void disabledWritesVerifyWithoutBackupsOrRecoveryExceptions() throws Exception {
        SafRecovery.setEnabled(context, false);
        assertFalse(SafRecovery.enabled(context));
        SafRecovery.write(context, node, payload, new FileStore(context).fingerprint(node));
        assertArrayEquals(replacement, sourceBytes());
        assertFalse(SafRecovery.directory(context).exists());
        configure("truncateWriteName", SOURCE);
        try { SafRecovery.write(context, node, payload); fail("Disabled backup must not hide provider errors"); }
        catch (IOException error) { assertNull(SafRecovery.findRecovery(error)); }
        assertFalse(SafRecovery.directory(context).exists());
        configure(null, null);
        call("clearCounters", null);
        try { SafRecovery.write(context, node, payload, "stale"); fail("Disabled backup must still check revision"); }
        catch (IOException error) { assertNull(SafRecovery.findRecovery(error)); }
        assertEquals(0, call("stats", null).getInt("writeOpens"));
    }

    @Test public void customPayloadKeepsPrivateIndexAndSurvivesDirectoryAndEnabledChanges() throws Exception {
        File firstRoot = new File(work, "custom-first"), nextRoot = new File(work, "custom-next");
        SafRecovery.setBackupDirectory(context, firstRoot);
        assertTrue(firstRoot.isDirectory());
        assertEquals(firstRoot.getCanonicalFile(), SafRecovery.backupDirectory(context));
        configure("truncateWriteName", SOURCE);
        SafRecovery.Record record = failedWrite().record;
        assertEquals(SafRecovery.directory(context).getCanonicalFile(), record.directory.getParentFile());
        assertEquals(new File(new File(firstRoot, record.directory.getName()), SafRecovery.BACKUP), record.backup);
        assertBackup(record);
        SafRecovery.setBackupDirectory(context, nextRoot);
        SafRecovery.setEnabled(context, false);
        assertEquals(1, SafRecovery.entries(context).size());
        SafRecovery.Entry entry = SafRecovery.entries(context).get(0);
        assertTrue(entry.recoverable()); assertNull(entry.problem());
        assertEquals(record.backup, entry.backup());
        configure(null, null);
        SafRecovery.restore(context, entry.record());
        assertArrayEquals(original, sourceBytes());
        assertTrue(firstRoot.isDirectory()); assertTrue(nextRoot.isDirectory());
        assertTrue(SafRecovery.entries(context).isEmpty());
        SafRecovery.setBackupDirectory(context, null);
        assertEquals(SafRecovery.directory(context), SafRecovery.backupDirectory(context));
    }

    @Test public void customVerifiedSaveCleansOnlyItsTemporaryBackupWithoutChmoddingRoot() throws Exception {
        File root = new File(work, "custom-success"); assertTrue(root.mkdir());
        Os.chmod(root.getPath(), 0755);
        File sentinel = new File(root, "keep.txt"); Files.write(sentinel.toPath(), new byte[]{7});
        SafRecovery.setBackupDirectory(context, root);
        SafRecovery.write(context, node, payload);
        assertArrayEquals(replacement, sourceBytes());
        assertEquals(0755, Os.stat(root.getPath()).st_mode & 0777);
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(sentinel.toPath()));
        assertEquals(1, Objects.requireNonNull(root.list()).length);
        assertTrue(SafRecovery.entries(context).isEmpty());
    }

    @Test public void deleteIsScopedToRecordedUuidNotSourceOtherBackupOrCustomRoot() throws Exception {
        File root = new File(work, "custom-delete"); SafRecovery.setBackupDirectory(context, root);
        File sentinel = new File(root, "keep.bin"); Files.write(sentinel.toPath(), new byte[]{9});
        configure("truncateWriteName", SOURCE);
        SafRecovery.Record first = failedWrite().record, second = failedWrite().record;
        File extra = new File(first.backup.getParentFile(), "unrecorded-user-file.bin");
        Files.write(extra.toPath(), new byte[]{1, 7});
        byte[] source = sourceBytes(), other = Files.readAllBytes(second.backup.toPath());
        SafRecovery.setBackupDirectory(context, null);
        SafRecovery.delete(context, first.directory.getName());
        assertFalse(first.directory.exists()); assertFalse(first.backup.exists());
        assertArrayEquals(source, sourceBytes());
        assertArrayEquals(other, Files.readAllBytes(second.backup.toPath()));
        assertArrayEquals(new byte[]{1, 7}, Files.readAllBytes(extra.toPath()));
        assertTrue(second.metadata.exists()); assertTrue(root.isDirectory());
        assertArrayEquals(new byte[]{9}, Files.readAllBytes(sentinel.toPath()));
        assertEquals(1, SafRecovery.entries(context).size());
    }

    @Test public void deleteRejectsIdsAndLinksWithoutFollowingOrDeletingTheirTargets() throws Exception {
        configure("truncateWriteName", SOURCE);
        SafRecovery.Record record = failedWrite().record;
        for (String id : new String[]{"", "../" + record.directory.getName(), record.directory.getPath(), "1-1-1-1-1", ".", null}) {
            try { SafRecovery.delete(context, id); fail("Invalid id accepted: " + id); }
            catch (IOException expected) { assertTrue(record.backup.isFile()); }
        }
        File outside = new File(work, "outside"); assertTrue(outside.mkdir());
        File sentinel = new File(outside, SafRecovery.BACKUP); Files.write(sentinel.toPath(), original);
        File linked = new File(SafRecovery.directory(context), UUID.randomUUID().toString());
        Os.symlink(outside.getPath(), linked.getPath());
        try { SafRecovery.delete(context, linked.getName()); fail("Linked operation accepted"); }
        catch (IOException expected) { assertTrue(sentinel.isFile()); }
        assertEquals(2, SafRecovery.entries(context).size());
        Files.delete(record.backup.toPath()); Os.symlink(sentinel.getPath(), record.backup.getPath());
        try { SafRecovery.delete(context, record.directory.getName()); fail("Linked payload accepted"); }
        catch (IOException expected) { assertTrue(record.metadata.isFile()); }
        assertArrayEquals(original, Files.readAllBytes(sentinel.toPath()));
        call("clearCounters", null); configure(null, null);
        try { SafRecovery.restore(context, record); fail("Linked payload restored"); }
        catch (IOException expected) { assertEquals(0, call("stats", null).getInt("writeOpens")); }
    }

    @Test public void directoryValidationRejectsRelativeFileLinkAndRootOnlyLocationsWithoutFallback() throws Exception {
        File good = new File(work, "good-root"); SafRecovery.setBackupDirectory(context, good);
        File plain = new File(work, "plain"); assertTrue(plain.createNewFile());
        File link = new File(work, "linked-root"); Os.symlink(good.getPath(), link.getPath());
        for (File invalid : new File[]{new File("relative"), plain, link, new File("/proc/dualstack-backup")}) {
            try { SafRecovery.setBackupDirectory(context, invalid); fail("Invalid directory accepted: " + invalid); }
            catch (IOException expected) { assertEquals(good.getCanonicalFile(), SafRecovery.backupDirectory(context)); }
        }
        assertTrue(good.delete()); assertTrue(good.createNewFile());
        try { SafRecovery.write(context, node, payload); fail("Unavailable configured root silently fell back"); }
        catch (IOException error) { assertNull(SafRecovery.findRecovery(error)); }
        assertEquals(0, call("stats", null).getInt("writeOpens"));
        assertArrayEquals(original, sourceBytes());
    }

    @Test public void customRootCannotNestInsideCatalogOrAnExistingPayloadOperation() throws Exception {
        File root = new File(work, "nesting-root"); SafRecovery.setBackupDirectory(context, root);
        configure("truncateWriteName", SOURCE); SafRecovery.Record record = failedWrite().record;
        for (File rejected : new File[]{new File(SafRecovery.directory(context), "nested-root"),
                record.backup.getParentFile(), new File(record.backup.getParentFile(), "nested-root")}) {
            try { SafRecovery.setBackupDirectory(context, rejected); fail("Nested root accepted: " + rejected); }
            catch (IOException expected) { assertEquals(root.getCanonicalFile(), SafRecovery.backupDirectory(context)); }
        }
        assertFalse(new File(record.backup.getParentFile(), "nested-root").exists());
        // A UUID-shaped ordinary sibling and an ancestor root are legitimate user directories.
        File sibling = new File(root, UUID.randomUUID().toString());
        SafRecovery.setBackupDirectory(context, sibling);
        assertEquals(sibling.getCanonicalFile(), SafRecovery.backupDirectory(context));
        SafRecovery.setBackupDirectory(context, root.getParentFile());
        assertEquals(root.getParentFile().getCanonicalFile(), SafRecovery.backupDirectory(context));
        SafRecovery.setBackupDirectory(context, SafRecovery.directory(context));
        assertEquals(SafRecovery.directory(context), SafRecovery.backupDirectory(context));
        assertFalse(context.getSharedPreferences("SafRecovery", 0).contains("backupDirectory"));
        assertBackup(record);
    }

    @Test public void badMissingAndOrphanRecordsDoNotHideGoodWritingRecord() throws Exception {
        configure("truncateWriteName", SOURCE);
        SafRecovery.Record good = failedWrite().record;
        SafRecovery.Record corruptPayload = failedWrite().record;
        Files.write(corruptPayload.backup.toPath(), new byte[]{4});
        SafRecovery.Record missing = failedWrite().record; Files.delete(missing.backup.toPath());
        File corrupt = new File(SafRecovery.directory(context), UUID.randomUUID().toString()); assertTrue(corrupt.mkdir());
        Files.write(new File(corrupt, SafRecovery.METADATA).toPath(), new byte[]{0, 1});
        File orphan = new File(SafRecovery.directory(context), UUID.randomUUID().toString()); assertTrue(orphan.mkdir());
        File unknown = new File(SafRecovery.directory(context), "user-not-a-recovery-record"); assertTrue(unknown.mkdir());
        File unknownFile = new File(unknown, "keep.bin"); Files.write(unknownFile.toPath(), new byte[]{5});
        List<SafRecovery.Entry> entries = SafRecovery.entries(context);
        assertEquals(5, entries.size());
        long previous = Long.MAX_VALUE; int usable = 0;
        for (SafRecovery.Entry entry : entries) {
            assertTrue(entry.createdAt() <= previous); previous = entry.createdAt();
            if (entry.recoverable()) { usable++; assertEquals(good.directory.getName(), entry.id()); }
            else assertNotNull(entry.problem());
        }
        assertEquals(1, usable); assertEquals(1, SafRecovery.pending(context).size());
        SafRecovery.delete(context, corrupt.getName()); SafRecovery.delete(context, orphan.getName());
        SafRecovery.delete(context, missing.directory.getName());
        assertEquals(2, SafRecovery.entries(context).size()); assertBackup(good);
        assertArrayEquals(new byte[]{5}, Files.readAllBytes(unknownFile.toPath()));
    }

    @Test public void listingDoesNotHashEqualLengthTamperingButRestoreRejectsItBeforeWt() throws Exception {
        configure("truncateWriteName", SOURCE); SafRecovery.Record record = failedWrite().record;
        byte[] tampered = original.clone(); tampered[tampered.length / 2] ^= 0x5a;
        Files.write(record.backup.toPath(), tampered);
        configure(null, null); call("clearCounters", null);
        List<SafRecovery.Entry> entries = SafRecovery.entries(context);
        assertEquals(1, entries.size());
        SafRecovery.Entry candidate = entries.get(0);
        assertTrue("Listing performs structural checks only, not a full payload hash", candidate.recoverable());
        assertNull(candidate.problem()); assertNotNull(candidate.record());
        assertEquals(original.length, candidate.size());
        assertEquals(1, SafRecovery.pending(context).size());
        try { SafRecovery.restore(context, candidate.record()); fail("Equal-length corrupt payload was restored"); }
        catch (SafRecovery.RecoveryException error) { assertTrue(causeContains(error, "恢复备份校验失败")); }
        assertEquals(0, call("stats", null).getInt("writeOpens"));
        assertArrayEquals(Arrays.copyOf(original, 3), sourceBytes());
        assertArrayEquals(tampered, Files.readAllBytes(record.backup.toPath()));
        assertTrue(record.metadata.isFile());
    }

    @Test public void incompleteCustomCopyHasDurableLocatorAndCanBeDeleted() throws Exception {
        File root = new File(work, "partial-root"); SafRecovery.setBackupDirectory(context, root);
        configure("failReadAfterPrefixName", SOURCE);
        try { SafRecovery.write(context, node, payload); fail("Injected backup read failure ignored"); }
        catch (IOException error) { assertNull(SafRecovery.findRecovery(error)); }
        List<SafRecovery.Entry> entries = SafRecovery.entries(context);
        assertEquals(1, entries.size());
        SafRecovery.Entry entry = entries.get(0);
        assertNotNull(entry.record()); assertFalse(entry.recoverable()); assertNotNull(entry.problem());
        assertEquals(new File(new File(root, entry.id()), SafRecovery.BACKUP), entry.backup());
        assertTrue(entry.backup().exists());
        assertEquals(0, call("stats", null).getInt("writeOpens"));
        SafRecovery.setBackupDirectory(context, null);
        SafRecovery.delete(context, entry.id());
        assertTrue(root.isDirectory()); assertFalse(entry.backup().exists());
        assertTrue(SafRecovery.entries(context).isEmpty());
    }

    @Test public void sharedStorageFusePayloadRemainsManageableAcrossRootChanges() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        String app = target.getPackageName();
        String oldMode = "default";
        boolean changed = android.os.Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager();
        if (changed) {
            String state = device.executeShellCommand("appops get --uid " + app + " MANAGE_EXTERNAL_STORAGE");
            java.util.regex.Matcher mode = java.util.regex.Pattern.compile(
                    "MANAGE_EXTERNAL_STORAGE:\\s*(allow|ignore|deny|default|foreground)").matcher(state);
            if (mode.find()) oldMode = mode.group(1);
            else assertTrue("Cannot preserve app-op: " + state, state.contains("No operations"));
        }
        File shared = new File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS),
                "DualStackLab-saf-recovery-test-" + UUID.randomUUID());
        try {
            if (changed) device.executeShellCommand("appops set --uid " + app + " MANAGE_EXTERNAL_STORAGE allow");
            SafRecovery.setBackupDirectory(context, shared);
            File sentinel = new File(shared, "keep.bin"); Files.write(sentinel.toPath(), new byte[]{6, 7});
            configure("truncateWriteName", SOURCE);
            SafRecovery.Record saved = failedWrite().record;
            assertTrue(saved.backup.getCanonicalPath().startsWith(shared.getCanonicalPath() + File.separator));
            assertBackup(saved);
            SafRecovery.setBackupDirectory(context, new File(work, "after-shared"));
            SafRecovery.Record loaded = SafRecovery.entries(context).get(0).record();
            configure(null, null); SafRecovery.restore(context, loaded);
            assertArrayEquals(original, sourceBytes());
            assertFalse(saved.backup.exists()); assertTrue(shared.isDirectory());
            assertArrayEquals(new byte[]{6, 7}, Files.readAllBytes(sentinel.toPath()));
            SafRecovery.setBackupDirectory(context, shared);
            configure("truncateWriteName", SOURCE); SafRecovery.Record deleted = failedWrite().record;
            SafRecovery.setBackupDirectory(context, null);
            SafRecovery.delete(context, deleted.directory.getName());
            assertFalse(deleted.backup.exists()); assertTrue(shared.isDirectory());
            assertArrayEquals(new byte[]{6, 7}, Files.readAllBytes(sentinel.toPath()));
            assertTrue(SafRecovery.entries(context).isEmpty());
        } finally {
            try { if (shared.exists()) remove(shared); }
            finally {
                if (changed) device.executeShellCommand("appops set --uid " + app + " MANAGE_EXTERNAL_STORAGE " + oldMode);
            }
        }
    }

    @Test public void customOperationSwapCannotRedirectTheOpenPayloadOrTruncateAnOutsideFile() throws Exception {
        File root = new File(work, "swap-root"); SafRecovery.setBackupDirectory(context, root);
        File outside = new File(work, "swap-outside"); assertTrue(outside.mkdir());
        File sentinel = new File(outside, SafRecovery.BACKUP); Files.write(sentinel.toPath(), new byte[]{8, 9});
        configure("blockReadName", SOURCE);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Future<?> saving = worker.submit(() -> { SafRecovery.write(context, node, payload); return null; });
        try {
            awaitBlockedRead();
            File[] operations = Objects.requireNonNull(SafRecovery.directory(context).listFiles());
            assertEquals(1, operations.length);
            Properties metadata = new Properties();
            try (InputStream input = new FileInputStream(new File(operations[0], SafRecovery.METADATA))) { metadata.load(input); }
            assertEquals("copying", metadata.getProperty("state"));
            assertEquals(root.getCanonicalPath(), metadata.getProperty("storageRoot"));
            File operation = new File(root, operations[0].getName()), moved = new File(root, "moved-original");
            Files.move(operation.toPath(), moved.toPath()); Os.symlink(outside.getPath(), operation.getPath());
            call("releaseRead", null);
            IOException error = failure(saving);
            assertNull(SafRecovery.findRecovery(error));
            assertEquals(0, call("stats", null).getInt("writeOpens"));
            assertArrayEquals(new byte[]{8, 9}, Files.readAllBytes(sentinel.toPath()));
            assertArrayEquals(original, Files.readAllBytes(new File(moved, SafRecovery.BACKUP).toPath()));
            assertEquals(1, SafRecovery.entries(context).size());
            assertFalse(SafRecovery.entries(context).get(0).recoverable());
        } finally {
            call("releaseRead", null); saving.cancel(true); worker.shutdownNow();
            assertTrue(worker.awaitTermination(20, TimeUnit.SECONDS));
        }
    }

    @Test public void enabledPreferenceDoesNotWaitForTheBackupIoLock() throws Exception {
        configure("blockReadName", SOURCE);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        Future<?> saving = workers.submit(() -> { SafRecovery.write(context, node, payload); return null; });
        try {
            awaitBlockedRead();
            Future<?> toggle = workers.submit(() -> InstrumentationRegistry.getInstrumentation().runOnMainSync(
                    () -> SafRecovery.setEnabled(context, false)));
            toggle.get(3, TimeUnit.SECONDS);
            assertFalse(SafRecovery.enabled(context));
            call("releaseRead", null); saving.get(20, TimeUnit.SECONDS);
            assertArrayEquals(replacement, sourceBytes());
            assertTrue(SafRecovery.entries(context).isEmpty());
        } finally {
            call("releaseRead", null); saving.cancel(true); workers.shutdownNow();
            assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS));
        }
    }

    @Test public void legacyV1RecordIsStillRecoverableAfterDefaultCatalogRelocation() throws Exception {
        configure("truncateWriteName", SOURCE); SafRecovery.Record record = failedWrite().record;
        Properties metadata = new Properties();
        try (InputStream input = new FileInputStream(record.metadata)) { metadata.load(input); }
        assertFalse("Default payload location must stay relative to its catalog", metadata.containsKey("storageRoot"));
        metadata.setProperty("version", "1"); metadata.remove("storageRoot");
        try (OutputStream output = new FileOutputStream(record.metadata)) { metadata.store(output, "legacy fixture"); }
        assertEquals(1, SafRecovery.pending(context).size());
        File relocatedFiles = new File(work, "relocated-files"); assertTrue(relocatedFiles.mkdir());
        Context relocated = new ContextWrapper(context) {
            @Override public File getFilesDir() { return relocatedFiles; }
        };
        assertTrue(SafRecovery.directory(relocated).mkdir());
        Files.move(record.directory.toPath(), new File(SafRecovery.directory(relocated), record.directory.getName()).toPath());
        SafRecovery.Record loaded = SafRecovery.pending(relocated).get(0);
        assertTrue(loaded.backup.getPath().startsWith(relocatedFiles.getPath()));
        configure(null, null); SafRecovery.restore(relocated, loaded);
        assertArrayEquals(original, sourceBytes());
    }

    private static void restorePreferences(Context context, String name, Map<String, ?> saved) {
        android.content.SharedPreferences.Editor editor = context.getSharedPreferences(name, 0).edit().clear();
        for (Map.Entry<String, ?> entry : saved.entrySet()) {
            Object value = entry.getValue(); String key = entry.getKey();
            if (value instanceof String text) editor.putString(key, text);
            else if (value instanceof Boolean flag) editor.putBoolean(key, flag);
            else if (value instanceof Integer number) editor.putInt(key, number);
            else if (value instanceof Long number) editor.putLong(key, number);
            else if (value instanceof Float number) editor.putFloat(key, number);
            else if (value instanceof Set<?> set) {
                Set<String> strings = new HashSet<>(); for (Object item : set) strings.add((String) item);
                editor.putStringSet(key, strings);
            }
        }
        assertTrue(editor.commit());
    }

    private void selectRecovery(UiDevice device) {
        assertNotNull(device.wait(Until.findObject(By.textContains("份 SAF 恢复备份")), 15000));
        androidx.test.uiautomator.UiObject2 item = device.wait(Until.findObject(By.textStartsWith(SOURCE + " · ")), 15000);
        assertNotNull(item); item.click();
        assertNotNull(device.wait(Until.findObject(By.text("SAF 持久恢复备份")), 15000));
    }
    private void click(UiDevice device, String text) {
        androidx.test.uiautomator.UiObject2 button = device.wait(Until.findObject(By.text(text)), 15000);
        assertNotNull(text, button); button.click();
    }
    @Test public void unavailableCustomRootKeepsIndexUntilTheBackupCanActuallyBeDeleted() throws Exception {
        File selected = new File(work, "offline-backups");
        SafRecovery.setBackupDirectory(context, selected);
        configure("truncateWriteName", SOURCE);
        SafRecovery.Record record = failedWrite().record;
        File offline = new File(work, "temporarily-offline");
        Files.move(selected.toPath(), offline.toPath());
        try {
            IOException failure = assertThrows(IOException.class,
                    () -> SafRecovery.delete(context, record.directory.getName()));
            assertTrue(failure.getMessage(), failure.getMessage().contains("暂不可用"));
            assertTrue("Unavailable storage must not silently remove its private locator", record.metadata.isFile());
            assertArrayEquals(original, Files.readAllBytes(new File(
                    new File(offline, record.directory.getName()), SafRecovery.BACKUP).toPath()));
            SafRecovery.Entry entry = SafRecovery.entries(context).stream()
                    .filter(item -> item.id().equals(record.directory.getName())).findFirst().orElseThrow();
            assertNotNull(entry.record()); assertFalse(entry.recoverable());
        } finally { Files.move(offline.toPath(), selected.toPath()); }
        SafRecovery.delete(context, record.directory.getName());
        assertFalse(record.backup.exists()); assertFalse(record.metadata.exists());
        assertTrue(selected.isDirectory());
        assertArrayEquals(Arrays.copyOf(original, 3), sourceBytes());
    }
    private SafRecovery.RecoveryException failedWrite() throws Exception {
        try { SafRecovery.write(context, node, payload); fail("Injected write failure was ignored"); return null; }
        catch (SafRecovery.RecoveryException error) { return error; }
    }
    private void assertBackup(SafRecovery.Record record) throws Exception {
        assertTrue(record.metadata.isFile()); assertEquals(original.length, record.originalSize);
        assertArrayEquals(original, Files.readAllBytes(record.backup.toPath()));
        assertEquals(FileStore.digest(new ByteArrayInputStream(original)), record.originalSha256);
        assertEquals(FileStore.digest(new ByteArrayInputStream(replacement)), record.payloadSha256);
    }
    private byte[] sourceBytes() throws Exception {
        try (InputStream input = resolver.openInputStream(sourceUri)) {
            assertNotNull(input); ByteArrayOutputStream output = new ByteArrayOutputStream(); FileStore.pump(input, output);
            return output.toByteArray();
        }
    }
    private void configure(String key, String value) {
        Bundle extras = new Bundle(); if (key != null) extras.putString(key, value); call("configure", extras);
    }
    private Bundle call(String method, Bundle extras) {
        Bundle result = resolver.call(CONTROL, method, null, extras); assertNotNull(result); return result;
    }
    private void awaitBlockedRead() throws Exception {
        long deadline = SystemClock.uptimeMillis() + 15000;
        while (SystemClock.uptimeMillis() < deadline) {
            Bundle state = call("readState", null); assertNull(state.getString("error"));
            if (state.getBoolean("opened") && state.getBoolean("prefixWritten")) return;
            Thread.sleep(10);
        }
        fail("Fixture never blocked the backup read");
    }
    private static IOException failure(Future<?> future) throws Exception {
        try { future.get(20, TimeUnit.SECONDS); fail("Write unexpectedly succeeded"); return null; }
        catch (ExecutionException error) {
            assertTrue(String.valueOf(error.getCause()), error.getCause() instanceof IOException);
            return (IOException) error.getCause();
        }
    }
    private static boolean causeContains(Throwable error, String expected) {
        for (Throwable cause = error; cause != null; cause = cause.getCause())
            if (String.valueOf(cause.getMessage()).contains(expected)) return true;
        return false;
    }
    private static byte[] bytes(int length, long seed) { byte[] bytes = new byte[length]; new Random(seed).nextBytes(bytes); return bytes; }
    private Bundle grantFixture(Context target) throws Exception {
        // Same ordered broadcast as BatchCopyInstrumentedTest: only the test APK can grant its provider tree.
        CountDownLatch finished = new CountDownLatch(1); AtomicReference<Bundle> result = new AtomicReference<>();
        AtomicInteger code = new AtomicInteger(Activity.RESULT_CANCELED);
        String testPackage = InstrumentationRegistry.getInstrumentation().getContext().getPackageName();
        Intent intent = new Intent(CountingDocumentsProvider.AUTHORITY + ".GRANT")
                .setComponent(new ComponentName(testPackage, CountingDocumentsProvider.GrantReceiver.class.getName()))
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        target.sendOrderedBroadcast(intent, null, new BroadcastReceiver() {
            @Override public void onReceive(Context receiverContext, Intent received) {
                code.set(getResultCode()); result.set(getResultExtras(false)); finished.countDown();
            }
        }, null, Activity.RESULT_CANCELED, null, null);
        assertTrue("Fixture grant did not finish", finished.await(30, TimeUnit.SECONDS));
        assertNotNull(result.get()); assertEquals(String.valueOf(result.get()), Activity.RESULT_OK, code.get());
        return result.get();
    }
    private static void remove(File root) throws IOException {
        Files.walkFileTree(root.toPath(), new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(directory); return FileVisitResult.CONTINUE;
            }
        });
    }
}
