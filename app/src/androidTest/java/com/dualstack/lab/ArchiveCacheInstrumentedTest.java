package com.dualstack.lab;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ArchiveCacheInstrumentedTest {
    private Context context;
    private File folder;

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        folder = Files.createTempDirectory(context.getCacheDir().toPath(), "archive-cache-test-").toFile();
    }
    @After public void cleanup() throws Exception {
        try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(folder.toPath())) {
            for (java.nio.file.Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                ArchiveOps.forget(path.toFile());
                Files.deleteIfExists(path);
            }
        }
    }
    @Test public void repeatedListsReuseIndexWithoutSharingMutableResults() throws Exception {
        File file = zip("repeat.zip", "dir/one.txt", "dir/two.txt");
        List<ArchiveOps.Item> first = ArchiveOps.list(file, "dir/");
        Object index = cache("indexes").get(file.getCanonicalPath());
        assertNotNull(index);
        first.clear();
        assertEquals(2, ArchiveOps.list(file, "dir/").size());
        assertSame(index, cache("indexes").get(file.getCanonicalPath()));
        FileStore store = new FileStore(context);
        FileStore.Node source = FileStore.local(file);
        assertEquals(file, store.archiveView(source));
        store.release(source, file);
        assertTrue(file.isFile());
    }
    @Test public void sameSizeTimestampReplacementDoesNotReuseOldInodeIndex() throws Exception {
        File original = zip("identity.zip", "old.txt");
        File replacement = zip("replacement.zip", "new.txt");
        long time = original.lastModified();
        assertEquals(original.length(), replacement.length());
        assertEquals("old.txt", ArchiveOps.list(original, "").get(0).name());
        Object before = cache("indexes").get(original.getCanonicalPath());
        assertTrue(replacement.setLastModified(time));
        Files.move(replacement.toPath(), original.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        assertEquals(time, original.lastModified());
        assertEquals("new.txt", ArchiveOps.list(original, "").get(0).name());
        assertNotSame(before, cache("indexes").get(original.getCanonicalPath()));
    }
    @Test public void fileStoreWriteExplicitlyInvalidatesListing() throws Exception {
        File original = zip("saved.zip", "old.txt");
        File replacement = zip("payload.zip", "new.txt");
        ArchiveOps.list(original, "");
        assertTrue(cache("indexes").containsKey(original.getCanonicalPath()));
        new FileStore(context).write(FileStore.local(original), replacement);
        assertFalse(cache("indexes").containsKey(original.getCanonicalPath()));
        assertEquals("new.txt", ArchiveOps.list(original, "").get(0).name());
    }
    @Test public void oversizedZipRemembersFallbackAndDoesNotCacheOversizedFolder() throws Exception {
        File file = new File(folder, "oversized.zip");
        try (ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            out.setLevel(Deflater.NO_COMPRESSION);
            for (int i = 0; i < 60001; i++) entry(out, "dir/" + i + ".txt");
        }
        String key = file.getCanonicalPath();
        assertEquals(1, ArchiveOps.list(file, "").size());
        assertFalse(cache("indexes").containsKey(key));
        Object fallback = cache("fallbacks").get(key);
        assertNotNull(fallback);
        assertEquals(60001, ArchiveOps.list(file, "dir/").size());
        assertSame(fallback, cache("fallbacks").get(key));
        Object folders = cache("folderCache").get(key);
        Method lists = folders.getClass().getDeclaredMethod("lists"); lists.setAccessible(true);
        Map<?, ?> cachedLists = (Map<?, ?>) lists.invoke(folders);
        assertFalse(cachedLists.containsKey("dir/"));
        assertEquals(1, ArchiveOps.list(file, "").size());
        assertSame(fallback, cache("fallbacks").get(key));
        ArchiveOps.forget(file);
        assertFalse(cache("fallbacks").containsKey(key));
        assertFalse(cache("folderCache").containsKey(key));
    }
    @Test public void emptyArchiveIndexesAreCountBounded() throws Exception {
        for (int i = 0; i < 12; i++) assertTrue(ArchiveOps.list(zip("empty-" + i + ".zip"), "").isEmpty());
        assertTrue(cache("indexes").size() <= 8);
        assertTrue(cache("indexItems").size() <= 8);
    }
    private File zip(String filename, String... names) throws IOException {
        File file = new File(folder, filename);
        try (ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            for (String name : names) entry(out, name);
        }
        return file;
    }
    private static void entry(ZipOutputStream out, String name) throws IOException {
        ZipEntry entry = new ZipEntry(name); entry.setTime(1700000000000L);
        out.putNextEntry(entry); out.closeEntry();
    }
    private static Map<?, ?> cache(String name) throws Exception {
        Field field = ArchiveOps.class.getDeclaredField(name); field.setAccessible(true);
        return (Map<?, ?>) field.get(null);
    }
}
