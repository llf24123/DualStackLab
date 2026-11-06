package com.dualstack.lab;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SearchJobInstrumentedTest {
    private Context context;
    private File work;

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        work = Files.createTempDirectory(context.getExternalFilesDir(null).toPath(), "search-job-").toFile();
    }
    @After public void cleanup() throws Exception { new FileStore(context).delete(FileStore.local(work)); }

    @Test public void searchPublishesIncrementalMatchesAndCompletes() throws Exception {
        File nested = new File(work, "nested"); assertTrue(nested.mkdir());
        for (int i = 0; i < 40; i++) {
            Files.write(new File(work, "target-a-" + i + ".txt").toPath(), "a".getBytes(StandardCharsets.UTF_8));
            Files.write(new File(nested, "target-b-" + i + ".txt").toPath(), "b".getBytes(StandardCharsets.UTF_8));
        }
        Files.write(new File(work, "other.txt").toPath(), "other".getBytes(StandardCharsets.UTF_8));
        SearchJob job = SearchJob.start(context, FileStore.directory(work, false), "target",
                Pattern.compile("target", Pattern.CASE_INSENSITIVE), false);
        AtomicInteger updates = new AtomicInteger();
        CountDownLatch firstResult = new CountDownLatch(1), finished = new CountDownLatch(1);
        job.listen(() -> {
            updates.incrementAndGet();
            SearchJob.Snapshot state = job.snapshot();
            if (!state.matches().isEmpty()) firstResult.countDown();
            if (state.finished()) finished.countDown();
        });
        assertTrue("No incremental result was published", firstResult.await(10, TimeUnit.SECONDS));
        assertTrue("Search never completed", finished.await(10, TimeUnit.SECONDS));
        assertTrue(updates.get() > 0);
        SearchJob.Snapshot state = job.snapshot();
        assertEquals(80, state.matches().size());
        assertNull(state.error());
    }

    @Test public void contentSnippetIsBoundedAndHighlightsTheMatchingLine() {
        SearchJob.Hit hit = SearchJob.contentHit(Pattern.compile("needle"), "first\n" + "x".repeat(80) + "needle\tend\nlast");
        assertNotNull(hit); assertEquals(2, hit.line());
        assertTrue(hit.text().startsWith("…")); assertFalse(hit.text().contains("\t"));
        assertEquals("needle", hit.text().substring(hit.start(), hit.end()));
        SearchJob.Hit longHit = SearchJob.contentHit(Pattern.compile("x+"), "x".repeat(10000));
        assertTrue(longHit.text().length() <= 162); assertTrue(longHit.end() <= longHit.text().length());
    }
    @Test public void snippetsHandleZeroLengthAndMultilineMatches() {
        for (String pattern : new String[]{"^", "$", "\\n", "first\\nsecond", "(?m)^second"}) {
            SearchJob.Hit hit = SearchJob.contentHit(Pattern.compile(pattern), "first\nsecond");
            assertNotNull(pattern, hit); assertTrue(hit.start() >= 0);
            assertTrue(pattern, hit.end() >= hit.start()); assertTrue(hit.end() <= hit.text().length());
        }
        assertEquals(2, SearchJob.contentHit(Pattern.compile("second"), "first\nsecond").line());
        assertNull(SearchJob.contentHit(Pattern.compile("missing"), "first"));
    }
    @Test public void hiddenDirectoriesAreSkippedAndContentMatchesCarryLineNumbers() throws Exception {
        File hidden = new File(work, ".secret"); assertTrue(hidden.mkdir());
        Files.write(new File(hidden, "inside.txt").toPath(), "needle".getBytes(StandardCharsets.UTF_8));
        File visible = new File(work, "visible.txt"); Files.write(visible.toPath(), "first\nneedle\n".getBytes(StandardCharsets.UTF_8));
        SearchJob job = SearchJob.start(context, FileStore.directory(work, false), "needle", Pattern.compile("needle"), true, false);
        long deadline = System.currentTimeMillis() + 10000;
        while (!job.snapshot().finished() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertTrue(job.snapshot().finished()); assertNull(job.snapshot().error());
        assertEquals(1, job.snapshot().matches().size());
        assertEquals("visible.txt", job.snapshot().matches().get(0).name);
        assertEquals(2, job.hit(job.snapshot().matches().get(0)).line());
    }

    @Test public void cancelStopsTheSearchAndIsObservable() throws Exception {
        for (int i = 0; i < 120; i++) {
            File folder = new File(work, "folder-" + i); assertTrue(folder.mkdir());
            for (int j = 0; j < 20; j++) Files.write(new File(folder, "target-" + j + ".txt").toPath(), new byte[]{65});
        }
        SearchJob job = SearchJob.start(context, FileStore.directory(work, false), "target",
                Pattern.compile("target", Pattern.CASE_INSENSITIVE), false);
        job.cancel();
        long deadline = System.currentTimeMillis() + 10000;
        while (!job.snapshot().finished() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertTrue(job.snapshot().finished());
        assertTrue(job.snapshot().cancelled());
    }
}
