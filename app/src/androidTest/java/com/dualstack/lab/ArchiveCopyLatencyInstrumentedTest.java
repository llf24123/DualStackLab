package com.dualstack.lab;

import android.content.Context;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ArchiveCopyLatencyInstrumentedTest {
    private Context context;
    private File work;
    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "archive-latency-").toFile();
    }
    @After public void cleanup() throws Exception { new FileStore(context).delete(FileStore.local(work)); }

    @Test public void largeZipSmallMemberCopyDoesNotParseAllHeadersForEncryption() throws Exception {
        File archive = new File(work, "many-entries.zip");
        byte[] payload = new byte[32768]; new Random(21).nextBytes(payload);
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(archive)))) {
            for (int i = 0; i < 30000; i++) {
                zip.putNextEntry(new ZipEntry("assets/item-" + i + ".txt")); zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("payload.bin")); zip.write(payload); zip.closeEntry();
        }
        ArchiveOps.forget(archive);
        long start = SystemClock.elapsedRealtime();
        try (ZipFile zip = new ZipFile(archive)) { assertNotNull(zip.getEntry("payload.bin")); }
        long directMs = SystemClock.elapsedRealtime() - start;
        start = SystemClock.elapsedRealtime();
        try (ArchiveOps.ReadSession reader = new ArchiveOps.ReadSession(archive);
                InputStream input = reader.open("payload.bin")) {
            assertArrayEquals(payload, FileStore.readBytes(input, payload.length + 1));
        }
        long readerMs = SystemClock.elapsedRealtime() - start;
        File destination = new File(work, "out"); assertTrue(destination.mkdir());
        ArchiveOps.forget(archive); start = SystemClock.elapsedRealtime();
        FileStore.Node output = new FileStore(context).copy(
                FileStore.archiveNode(FileStore.local(archive), "payload.bin", false, payload.length, 0), FileStore.local(destination), false);
        long copyMs = SystemClock.elapsedRealtime() - start;
        assertArrayEquals(payload, Files.readAllBytes(output.file.toPath()));
        String result = "entries=30001 bytes=" + payload.length + " directZipMs=" + directMs + " readSessionMs=" + readerMs + " copyMs=" + copyMs;
        android.util.Log.i("ArchiveCopyLatency", result);
        String run = InstrumentationRegistry.getArguments().getString("benchmarkRun", "candidate");
        Files.write(new File(context.getExternalFilesDir(null), "archive-latency-" + run + ".txt").toPath(), result.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (!run.equals("baseline")) {
            java.lang.reflect.Field field = ArchiveOps.class.getDeclaredField("encryptionChecks"); field.setAccessible(true);
            assertFalse("Ordinary ZIP copying must not run the whole-directory encryption detector",
                    ((Map<?, ?>) field.get(null)).containsKey(archive.getCanonicalPath()));
        }
    }
}
