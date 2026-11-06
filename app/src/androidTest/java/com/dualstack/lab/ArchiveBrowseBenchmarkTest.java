package com.dualstack.lab;

import android.content.Context;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

/** Times repeated folder listings inside a large ZIP, the way the panes browse one. */
@RunWith(AndroidJUnit4.class)
public class ArchiveBrowseBenchmarkTest {
    private File folder;
    @Before public void setup() {
        folder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "archive-bench-" + UUID.randomUUID());
        assertTrue(folder.mkdirs());
    }
    @After public void cleanup() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        new FileStore(context).delete(FileStore.local(folder));
    }
    @Test public void measureRepeatedFolderListings() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File zip = new File(folder, "big.apk");
        byte[] payload = new byte[256];
        try (ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zip)))) {
            out.setLevel(Deflater.BEST_SPEED);
            for (int d = 0; d < 60; d++) for (int f = 0; f < 500; f++) {
                out.putNextEntry(new ZipEntry("res/dir" + d + "/file" + f + ".xml")); out.write(payload); out.closeEntry();
            }
            for (int f = 0; f < 200; f++) {
                out.putNextEntry(new ZipEntry("assets/item" + f + ".txt"));
                out.write(("entry " + f).getBytes(StandardCharsets.UTF_8)); out.closeEntry();
            }
        }
        FileStore store = new FileStore(context);
        FileStore.Node archive = FileStore.local(zip);
        List<Long> times = new ArrayList<>();
        String[] prefixes = {"", "res/", "res/dir7/", "res/", "", "assets/", "res/dir41/", "res/", ""};
        for (String prefix : prefixes) {
            long start = SystemClock.elapsedRealtimeNanos();
            List<FileStore.Node> nodes = store.list(FileStore.archiveNode(archive, prefix, true, 0, 0));
            times.add((SystemClock.elapsedRealtimeNanos() - start) / 1_000_000);
            assertFalse(prefix, nodes.isEmpty());
        }
        long first = times.get(0), rest = 0;
        for (int i = 1; i < times.size(); i++) rest += times.get(i);
        String report = "entries=30200 size=" + zip.length() + " root=" + RootAccess.available()
                + " firstMs=" + first + " laterAvgMs=" + rest / (times.size() - 1) + " all=" + times;
        Log.i("ArchiveBench", report);
        File out = new File(context.getExternalFilesDir(null), "archive-bench.txt");
        try (FileOutputStream stream = new FileOutputStream(out)) { stream.write(report.getBytes(StandardCharsets.UTF_8)); }
    }
}
