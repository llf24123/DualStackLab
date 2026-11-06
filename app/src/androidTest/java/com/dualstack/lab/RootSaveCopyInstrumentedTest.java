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
