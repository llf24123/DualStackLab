package com.dualstack.lab;

import android.content.Context;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

/** All mutations, including controlled races, are confined to one UUID cache fixture. */
@RunWith(AndroidJUnit4.class)
public class RootMutationInstrumentedTest {
    private Context context;
    private File fixture;
    private final List<RootMutations.Session> sessions = new ArrayList<>();

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        fixture = new File(context.getCacheDir(), "dualstack-mutations-" + UUID.randomUUID());
        assertTrue(fixture.mkdir());
    }

    @After public void cleanup() throws Exception {
        for (RootMutations.Session session : sessions) session.close();
        RootMutations.close();
        if (fixture != null) erase(fixture);
    }

    @Test public void ownUidCreateChmodRenameNoOverwriteAndSymlinkNoFollow() throws Exception {
        // app_process inherits the application's UID; no su or privileged run-as caller is needed.
        try (RootMutations.Session session = session(false)) {
            RootFileOps.Result created = session.execute(RootFileOps.Request.create(spec(fixture), "file", false), null);
            File file = new File(fixture, "file");
            assertEquals(id(Os.lstat(file.getPath())), created.identity);
            assertEquals(id(Os.stat(fixture.getPath())), created.parentIdentity);
            assertEquals(Os.stat(fixture.getPath()).st_uid, created.uid);
            assertEquals(Os.stat(fixture.getPath()).st_gid, created.gid);
            RootFileOps.Result directory = session.execute(RootFileOps.Request.create(spec(fixture), "dir", true), null);
            assertEquals(directory.identity, directory.directoryIdentity);
            RootFileOps.Result changed = session.execute(RootFileOps.Request.chmod(spec(file), 0600), null);
            assertEquals(0600, changed.mode & 07777);
            RootFileOps.Result moved = session.execute(RootFileOps.Request.rename(spec(file), spec(fixture), "renamed", false), null);
            assertEquals(created.identity, moved.identity);
            assertFalse(file.exists());
            File renamed = new File(fixture, "renamed"), occupied = text(fixture, "occupied", "keep");
            expectError(() -> session.execute(RootFileOps.Request.rename(spec(renamed), spec(fixture), "occupied", false), null));
            assertEquals("keep", read(occupied)); assertEquals(created.identity, id(Os.lstat(renamed.getPath())));
            File link = new File(fixture, "link"); Os.symlink(occupied.getPath(), link.getPath());
            expectError(() -> session.execute(RootFileOps.Request.chmod(spec(link), 0777), null));
            assertEquals("keep", read(occupied));
            String targetId = id(Os.stat(occupied.getPath()));
            RootFileOps.Spec followed = new RootFileOps.Spec(link.getPath(), targetId, id(Os.stat(fixture.getPath())), null, true);
            RootFileOps.Result linkMove = session.execute(RootFileOps.Request.rename(followed, spec(fixture), "link-moved", false), null);
            assertTrue(OsConstants.S_ISLNK(linkMove.mode));
            assertEquals(targetId, id(Os.stat(occupied.getPath())));
            session.execute(RootFileOps.Request.delete(List.of(spec(new File(fixture, "link-moved")))), null);
            assertEquals("keep", read(occupied));
            assertTrue(session.isAlive());
        }
    }

    @Test public void ownUidHelperCanBeLaunchedByRunAsWhenPlatformAllowsIt() throws Exception {
        RootMutations.Session session;
        try { session = RootMutations.Session.runAsForTest(context); }
        catch (IOException unavailable) { Assume.assumeNoException("run-as caller/SELinux restriction", unavailable); return; }
        sessions.add(session);
        RootFileOps.Result result = session.execute(RootFileOps.Request.create(spec(fixture), "run-as", false), null);
        assertEquals(android.os.Process.myUid(), result.uid);
    }

    @Test public void staleGuardsAndLateDestinationConflictDoNotMutate() throws Exception {
        try (RootMutations.Session session = session(false)) {
            File source = text(fixture, "source", "source");
            RootFileOps.Spec stale = new RootFileOps.Spec(source.getPath(), "0:0", id(Os.stat(fixture.getPath())), null, false);
            expectError(() -> session.execute(RootFileOps.Request.chmod(stale, 0777), null));
            File destination = new File(fixture, "destination");
            expectError(() -> session.execute(RootFileOps.Request.rename(spec(source), spec(fixture), destination.getName(), false),
                    (event, name) -> { if (event == RootFileOps.STEP) text(fixture, destination.getName(), "late"); return true; }));
            assertEquals("source", read(source)); assertEquals("late", read(destination));
            RootFileOps.Spec wrongParent = new RootFileOps.Spec(source.getPath(), null, "0:0", null, true);
            expectError(() -> session.execute(RootFileOps.Request.delete(List.of(wrongParent)), null));
            RootFileOps.Spec wrongDirectory = new RootFileOps.Spec(fixture.getPath(), null, null, "0:0", true);
            expectError(() -> session.execute(RootFileOps.Request.create(wrongDirectory, "never", false), null));
            assertFalse(new File(fixture, "never").exists());
        }
    }

    @Test public void batchPreflightAndCancellationLeaveLaterSources() throws Exception {
        try (RootMutations.Session session = session(false)) {
            File first = text(fixture, "first", "first"), second = text(fixture, "second", "second");
            expectError(() -> session.execute(RootFileOps.Request.delete(List.of(spec(first),
                    new RootFileOps.Spec("/data", null, null, null, true))), null));
            assertTrue(first.exists());
            try {
                session.execute(RootFileOps.Request.delete(List.of(spec(first), spec(second))), (event, name) -> false);
                fail("Expected cancellation");
            } catch (Tasks.CancelledException expected) { }
            assertTrue(first.exists()); assertTrue(second.exists());
            int[] steps = {0};
            try {
                session.execute(RootFileOps.Request.delete(List.of(spec(first), spec(second))),
                        (event, name) -> event != RootFileOps.STEP || ++steps[0] < 2);
                fail("Expected mid-batch cancellation");
            } catch (Tasks.CancelledException expected) { }
            assertFalse(first.exists()); assertTrue(second.exists());
            assertTrue(session.isAlive());
            session.execute(RootFileOps.Request.create(spec(fixture), "after-cancel", false), null);
        }
    }

    @Test public void removeEmptyPreflightsAndNeverDeletesForeignChildren() throws Exception {
        try (RootMutations.Session session = session(false)) {
            File directory = new File(fixture, "owned-dir"); assertTrue(directory.mkdir());
            File sentinel = text(directory, "foreign-child", "keep");
            expectError(() -> session.execute(RootFileOps.Request.deleteEmpty(List.of(spec(directory))), null));
            assertEquals("keep", read(sentinel));
            File empty = new File(fixture, "empty"); assertTrue(empty.mkdir());
            expectError(() -> session.execute(RootFileOps.Request.deleteEmpty(List.of(spec(empty),
                    new RootFileOps.Spec("/data", null, null, null, false))), null));
            assertTrue(empty.exists());
            RootFileOps.Spec stale = new RootFileOps.Spec(empty.getPath(), "0:0", id(Os.stat(fixture.getPath())), null, false);
            expectError(() -> session.execute(RootFileOps.Request.deleteEmpty(List.of(stale)), null));
            assertTrue(empty.exists());
            expectError(() -> session.execute(RootFileOps.Request.deleteEmpty(List.of(spec(empty))), (event, name) -> {
                if (event == RootFileOps.STEP) text(empty, "late-foreign-child", "late");
                return true;
            }));
            assertEquals("late", read(new File(empty, "late-foreign-child")));
            Os.remove(new File(empty, "late-foreign-child").getPath());
            try {
                session.execute(RootFileOps.Request.deleteEmpty(List.of(spec(empty))), (event, name) -> event != RootFileOps.STEP);
                fail("Expected removeEmpty cancellation");
            } catch (Tasks.CancelledException expected) { }
            assertTrue(empty.exists());
            session.execute(RootFileOps.Request.deleteEmpty(List.of(spec(empty))), null); assertFalse(empty.exists());
            File link = new File(fixture, "link-to-owned"); Os.symlink(directory.getPath(), link.getPath());
            session.execute(RootFileOps.Request.deleteEmpty(List.of(spec(link))), null);
            assertFalse(Files.isSymbolicLink(link.toPath())); assertEquals("keep", read(sentinel));
        }
    }

    @Test public void cancelledOrFailingCreateCleansOnlyMatchingEmptyCreatedLeaf() throws Exception {
        try (RootMutations.Session session = session(false)) {
            File empty = new File(fixture, "cancel-empty");
            try {
                session.execute(RootFileOps.Request.create(spec(fixture), empty.getName(), false),
                        (event, name) -> event != RootFileOps.STEP || !name.startsWith("created:"));
                fail("Expected post-create cancellation");
            } catch (Tasks.CancelledException expected) { }
            assertFalse(Files.exists(empty.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS));
            File sentinel = text(fixture, "sentinel", "keep"), swapped = new File(fixture, "cancel-swapped"), held = new File(fixture, "held");
            String before = metadata(sentinel);
            try {
                session.execute(RootFileOps.Request.create(spec(fixture), swapped.getName(), false), (event, name) -> {
                    if (event == RootFileOps.STEP && name.startsWith("created:")) {
                        move(swapped, held); link(sentinel, swapped); return false;
                    }
                    return true;
                });
                fail("Expected replacement cancellation");
            } catch (Tasks.CancelledException expected) { }
            assertTrue(Files.isSymbolicLink(swapped.toPath())); assertTrue(held.exists());
            assertEquals(before, metadata(sentinel)); assertEquals("keep", read(sentinel));
            File written = new File(fixture, "cancel-written");
            try {
                session.execute(RootFileOps.Request.create(spec(fixture), written.getName(), false), (event, name) -> {
                    if (event == RootFileOps.STEP && name.startsWith("created:")) { text(fixture, written.getName(), "external-content"); return false; }
                    return true;
                });
                fail("Expected populated-inode cancellation");
            } catch (Tasks.CancelledException expected) { }
            assertEquals("external-content", read(written));
            File callbackFailed = new File(fixture, "callback-failed");
            expectError(() -> session.execute(RootFileOps.Request.create(spec(fixture), callbackFailed.getName(), false), (event, name) -> {
                if (event == RootFileOps.STEP && name.startsWith("created:")) throw new IOException("fixture callback failure");
                return true;
            }));
            assertFalse(Files.exists(callbackFailed.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS));
        }
    }

    @Test public void publicRootRemoveEmptyPreservesUnrecordedChild() throws Exception {
        requireRoot();
        FileStore.Node parent = new FileStore.Node(fixture.getName(), fixture, null, true, true, 0, 0);
        FileStore.Node directory = RootMutations.create(context, parent, "rollback-directory", true);
        File child = text(directory.file, "unrecorded-child", "keep");
        expectError(() -> RootMutations.removeEmpty(context, directory));
        assertEquals("keep", read(child)); assertEquals(directory.identity, rootStat(directory.file)[0]);
        Os.remove(child.getPath());
        RootMutations.removeEmpty(context, directory); assertFalse(directory.file.exists());
        try (RootMutations.Session session = session(true)) {
            File pending = new File(fixture, "root-cancelled-before-ledger");
            try {
                session.execute(RootFileOps.Request.create(new RootFileOps.Spec(fixture.getPath(), null, null, null, true),
                        pending.getName(), false), (event, name) -> event != RootFileOps.STEP || !name.startsWith("created:"));
                fail("Expected root post-create cancellation");
            } catch (Tasks.CancelledException expected) { }
            assertFalse(Files.exists(pending.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS));
        }
    }

    @Test public void publicClientProgressCancellationRunsOnCallingThread() throws Exception {
        requireRoot();
        File first = text(fixture, "first", "first"), second = text(fixture, "second", "second");
        Tasks.Progress progress = new Tasks.Progress(); progress.cancelled.set(true);
        try {
            progress.run(() -> { RootMutations.delete(context, List.of(node(first), node(second))); return null; });
            fail("Expected Tasks cancellation");
        } catch (Tasks.CancelledException expected) { }
        assertTrue(first.exists()); assertTrue(second.exists());
        assertEquals(0, progress.done.get());
    }

    @Test public void recursiveDeleteNeverFollowsLinksAndCancelsAtDirectoryBoundary() throws Exception {
        try (RootMutations.Session session = session(false)) {
            File outside = new File(fixture, "outside"), tree = new File(fixture, "tree");
            assertTrue(outside.mkdir()); assertTrue(tree.mkdir());
            File sentinel = text(outside, "sentinel", "untouched");
            Os.symlink(outside.getPath(), new File(tree, "link").getPath());
            Os.symlink("missing", new File(tree, "broken").getPath());
            File nested = new File(tree, "nested"); assertTrue(nested.mkdir()); text(nested, "content", "delete");
            session.execute(RootFileOps.Request.delete(List.of(spec(tree))), null);
            assertFalse(tree.exists()); assertEquals("untouched", read(sentinel));
            assertTrue(tree.mkdir()); assertTrue(nested.mkdir()); text(nested, "keep", "keep");
            try {
                session.execute(RootFileOps.Request.delete(List.of(spec(tree))),
                        (event, name) -> event != RootFileOps.STEP || !name.equals("nested"));
                fail("Expected recursive boundary cancellation");
            } catch (Tasks.CancelledException expected) { }
            assertEquals("keep", read(new File(nested, "keep")));
        }
    }

    @Test public void rootCreateMetadataUsesOpenFdNotReplacedName() throws Exception {
        requireRoot();
        try (RootMutations.Session session = session(true)) {
            File sentinel = text(fixture, "sentinel", "sentinel"), created = new File(fixture, "created"), held = new File(fixture, "held");
            String before = metadata(sentinel);
            expectError(() -> session.execute(RootFileOps.Request.create(spec(fixture), created.getName(), false), (event, name) -> {
                if (event == RootFileOps.STEP && name.startsWith("created:")) {
                    move(created, held); link(sentinel, created);
                }
                return true;
            }));
            assertEquals(before, metadata(sentinel)); assertEquals("sentinel", read(sentinel));
            assertTrue(Files.isSymbolicLink(created.toPath()));
            assertEquals(Os.stat(fixture.getPath()).st_uid, Os.stat(held.getPath()).st_uid);
            assertEquals(Os.stat(fixture.getPath()).st_gid, Os.stat(held.getPath()).st_gid);
            assertEquals(0644, Os.stat(held.getPath()).st_mode & 07777);
        }
    }

    @Test public void rootChmodPinsFdAcrossLeafSymlinkReplacement() throws Exception {
        requireRoot();
        try (RootMutations.Session session = session(true)) {
            File source = text(fixture, "source", "source"), held = new File(fixture, "held"), sentinel = text(fixture, "sentinel", "sentinel");
            String before = metadata(sentinel);
            expectError(() -> session.execute(RootFileOps.Request.chmod(spec(source), 0600), (event, name) -> {
                if (event == RootFileOps.STEP) { move(source, held); link(sentinel, source); }
                return true;
            }));
            assertEquals(before, metadata(sentinel)); assertEquals("sentinel", read(sentinel));
        }
    }

    @Test public void rootCreateDirectoryOwnerAndPinnedParentSurviveAncestorReplacement() throws Exception {
        requireRoot();
        try (RootMutations.Session session = session(true)) {
            File parent = new File(fixture, "parent"), held = new File(fixture, "held"), outside = new File(fixture, "outside");
            assertTrue(parent.mkdir()); assertTrue(outside.mkdir());
            File sentinel = text(outside, "sentinel", "outside"); String before = metadata(sentinel);
            RootFileOps.Result result = session.execute(RootFileOps.Request.create(spec(parent), "directory", true), (event, name) -> {
                if (event == RootFileOps.STEP && !name.startsWith("created:")) {
                    move(parent, held); link(outside, parent);
                }
                return true;
            });
            StructStat actual = Os.lstat(new File(held, "directory").getPath()), owner = Os.stat(held.getPath());
            assertEquals(owner.st_uid, actual.st_uid); assertEquals(owner.st_gid, actual.st_gid);
            assertEquals(id(actual), result.directoryIdentity);
            assertFalse(new File(outside, "directory").exists());
            assertEquals(before, metadata(sentinel)); assertEquals("outside", read(sentinel));
        }
    }

    @Test public void rootRenameAndDeleteKeepFixedParentsAfterPrepare() throws Exception {
        requireRoot();
        try (RootMutations.Session session = session(true)) {
            File sourceParent = new File(fixture, "src"), targetParent = new File(fixture, "dst"), outside = new File(fixture, "outside");
            File heldSource = new File(fixture, "src-held"), heldTarget = new File(fixture, "dst-held");
            assertTrue(sourceParent.mkdir()); assertTrue(targetParent.mkdir()); assertTrue(outside.mkdir());
            File source = text(sourceParent, "source", "source"), sentinel = text(outside, "sentinel", "outside");
            String inode = id(Os.lstat(source.getPath())), before = metadata(sentinel);
            RootFileOps.Result moved = session.execute(RootFileOps.Request.rename(spec(source), spec(targetParent), "moved", true), (event, name) -> {
                if (event == RootFileOps.PREPARED) {
                    move(sourceParent, heldSource); link(outside, sourceParent);
                    move(targetParent, heldTarget); link(outside, targetParent);
                }
                return true;
            });
            assertEquals(inode, moved.identity); assertEquals(inode, id(Os.lstat(new File(heldTarget, "moved").getPath())));
            assertFalse(new File(outside, "moved").exists());
            File tree = new File(heldSource, "tree"); assertTrue(tree.mkdir()); text(tree, "delete", "delete");
            session.execute(RootFileOps.Request.delete(List.of(spec(tree))), (event, name) -> {
                if (event == RootFileOps.PREPARED) {
                    // Replace an ancestor, not the final directory entry. The open parent stays authoritative.
                    File relocated = new File(fixture, "src-held-again");
                    move(heldSource, relocated); link(outside, heldSource);
                }
                return true;
            });
            assertFalse(new File(fixture, "src-held-again/tree").exists());
            assertEquals(before, metadata(sentinel)); assertEquals("outside", read(sentinel));
        }
    }

    @Test public void rootDirectoryMovePreservesInodeAndRefusesOccupiedOrSubtreeDestination() throws Exception {
        requireRoot();
        try (RootMutations.Session session = session(true)) {
            File source = new File(fixture, "source"), destination = new File(fixture, "destination");
            assertTrue(source.mkdir()); text(source, "content", "data");
            File child = new File(source, "child"); assertTrue(child.mkdir());
            expectError(() -> session.execute(RootFileOps.Request.rename(spec(source), spec(child), "bad", false), null));
            assertTrue(destination.mkdir()); text(destination, "sentinel", "keep");
            expectError(() -> session.execute(RootFileOps.Request.rename(spec(source), spec(fixture), destination.getName(), false), null));
            assertEquals("keep", read(new File(destination, "sentinel")));
            String before = id(Os.stat(source.getPath()));
            RootFileOps.Result moved = session.execute(RootFileOps.Request.rename(spec(source), spec(fixture), "moved", true), null);
            assertEquals(before, moved.identity); assertFalse(source.exists());
        }
    }

    @Test public void rootCrossDeviceFastPathReturnsNullWithoutCopyOrDelete() throws Exception {
        requireRoot();
        // Both fixtures remain in the explicitly allowed cache / data-local-tmp locations.
        // Devices which put these on the same filesystem skip rather than use a real system target.
        String path = "/data/local/tmp/dualstack-mutations-" + UUID.randomUUID();
        try (RootMutations.Session session = session(true)) {
            RootFileOps.Result dir;
            try { dir = session.execute(RootFileOps.Request.create(new RootFileOps.Spec("/data/local/tmp", null, null, null, true),
                    new File(path).getName(), true), null); }
            catch (IOException restricted) { Assume.assumeNoException("Requires writable root fixture location", restricted); return; }
            try {
                File source = text(fixture, "source", "source");
                Assume.assumeTrue("Requires two devices", !id(Os.stat(fixture.getPath())).split(":")[0].equals(dir.identity.split(":")[0]));
                RootFileOps.Result result = session.execute(RootFileOps.Request.rename(spec(source),
                        new RootFileOps.Spec(path, dir.identity, dir.parentIdentity, dir.directoryIdentity, false), "never", true), null);
                assertNull(result); assertEquals("source", read(source));
                // An absent destination is also checked through the helper, without probing the app's mount view.
                expectError(() -> session.execute(RootFileOps.Request.chmod(new RootFileOps.Spec(path + "/never", null, null, null, false), 0600), null));
            } finally { session.execute(RootFileOps.Request.delete(List.of(new RootFileOps.Spec(path, dir.identity, dir.parentIdentity, null, false))), null); }
        }
    }

    @Test public void directoryOnlyGuardAndNonAdjacentOverlapsRejectBeforeAnyMutation() throws Exception {
        try (RootMutations.Session session = session(false)) {
            File a = new File(fixture, "a"), dash = new File(fixture, "a-"), b = new File(a, "b");
            assertTrue(a.mkdir()); assertTrue(dash.mkdir()); assertTrue(b.mkdir()); text(b, "keep", "keep");
            expectError(() -> session.execute(RootFileOps.Request.delete(List.of(spec(a), spec(dash), spec(b))), null));
            assertEquals("keep", read(new File(b, "keep"))); assertTrue(dash.exists());
            File alias = new File(fixture, "alias"); link(a, alias);
            expectError(() -> session.execute(RootFileOps.Request.delete(List.of(spec(a), spec(new File(alias, "b")))), null));
            assertEquals("keep", read(new File(b, "keep")));
            RootFileOps.Spec stale = new RootFileOps.Spec(a.getPath(), null, id(Os.stat(fixture.getPath())), "0:0", false);
            expectError(() -> session.execute(RootFileOps.Request.delete(List.of(stale)), null));
            expectError(() -> session.execute(RootFileOps.Request.chmod(stale, 0700), null));
            expectError(() -> session.execute(RootFileOps.Request.rename(stale, spec(fixture), "never", false), null));
            assertTrue(a.exists()); assertFalse(new File(fixture, "never").exists());
        }
    }

    @Test public void fifoIsRejectedWithoutBlockingAndEarlyErrorConsumesLargeRequest() throws Exception {
        try (RootMutations.Session session = session(false)) {
            File fifo = new File(fixture, "fifo"); Os.mkfifo(fifo.getPath(), 0600);
            expectError(() -> session.execute(RootFileOps.Request.chmod(spec(fifo), 0777), null));
            assertTrue(OsConstants.S_ISFIFO(Os.lstat(fifo.getPath()).st_mode));
            List<RootFileOps.Spec> many = new ArrayList<>();
            many.add(new RootFileOps.Spec("/data", null, null, null, false));
            for (int i = 0; i < 2048; i++) many.add(new RootFileOps.Spec(new File(fixture,
                    "not-opened-" + i + "-" + UUID.randomUUID()).getPath(), null, null, null, false));
            expectError(() -> session.execute(RootFileOps.Request.delete(many), null));
            session.execute(RootFileOps.Request.create(spec(fixture), "after-large-error", false), null);
            assertTrue(session.isAlive());
        }
    }

    @Test public void rootDirectoryWithoutOwnerCreatePermissionIsRejectedWithoutChangingParent() throws Exception {
        requireRoot();
        try (RootMutations.Session session = session(true)) {
            File directory = new File(fixture, "no-write"); assertTrue(directory.mkdir()); Os.chmod(directory.getPath(), 0500);
            try {
                String before = metadata(directory);
                expectError(() -> session.execute(RootFileOps.Request.create(spec(directory), "never", true), null));
                assertEquals(before, metadata(directory)); assertFalse(new File(directory, "never").exists());
            } finally { Os.chmod(directory.getPath(), 0700); }
        }
    }

    @Test public void rootSharedCacheCreateReturnsMappedModesAndDirectoryMovePreservesInode() throws Exception {
        requireRoot();
        File cache = context.getExternalCacheDir(); Assume.assumeNotNull(cache);
        File external = new File(cache, "dualstack-mutations-" + UUID.randomUUID());
        Assume.assumeTrue("Requires writable shared cache", external.mkdir());
        try (RootMutations.Session session = session(true)) {
            RootFileOps.Spec parent = new RootFileOps.Spec(external.getPath(), null, null, null, true);
            String[] parentStat = rootStat(external);
            RootFileOps.Result file = session.execute(RootFileOps.Request.create(parent, "file", false), null);
            assertTrue(OsConstants.S_ISREG(file.mode));
            assertActualRootResult(new File(external, "file"), file);
            assertEquals(Integer.parseInt(parentStat[2]), file.uid); assertEquals(Integer.parseInt(parentStat[3]), file.gid);
            // On emulated shared storage these may be 0660/2770, not the requested 0644/0755.
            // Creation reports the mapping; explicit chmod still requires an exact read-back match.
            RootFileOps.Result directory = session.execute(RootFileOps.Request.create(parent, "directory", true), null);
            File original = new File(external, "directory");
            assertTrue(OsConstants.S_ISDIR(directory.mode)); assertActualRootResult(original, directory);
            assertEquals(Integer.parseInt(parentStat[2]), directory.uid); assertEquals(Integer.parseInt(parentStat[3]), directory.gid);
            assertEquals(directory.identity, directory.directoryIdentity);
            RootFileOps.Spec source = new RootFileOps.Spec(original.getPath(), directory.identity,
                    directory.parentIdentity, directory.directoryIdentity, false);
            RootFileOps.Result child = session.execute(RootFileOps.Request.create(source, "child", false), null);
            RootFileOps.Result moved = session.execute(RootFileOps.Request.rename(source, parent, "moved", true), null);
            assertNotNull("Shared-cache directory move must use the same-volume fast path", moved);
            File destination = new File(external, "moved");
            assertEquals(directory.identity, moved.identity); assertActualRootResult(destination, moved);
            assertEquals(child.identity, rootStat(new File(destination, "child"))[0]);
            assertFalse(original.exists());
            RootFileOps.Result occupied = session.execute(RootFileOps.Request.create(parent, "occupied", true), null);
            File sentinel = text(new File(external, "occupied"), "sentinel", "keep");
            expectError(() -> session.execute(RootFileOps.Request.rename(new RootFileOps.Spec(destination.getPath(), moved.identity,
                    moved.parentIdentity, moved.directoryIdentity, false), parent, "occupied", true), null));
            assertEquals(occupied.identity, rootStat(new File(external, "occupied"))[0]);
            assertEquals("keep", read(sentinel)); assertEquals(moved.identity, rootStat(destination)[0]);
        } finally { erase(external); }
    }

    @Test public void publicRenameAndMovePreserveSymlinkNavigationWithoutStatOfNewTarget() throws Exception {
        requireRoot();
        File from = new File(fixture, "from"), to = new File(fixture, "to");
        assertTrue(from.mkdir()); assertTrue(to.mkdir());
        FileStore.Node fromNode = new FileStore.Node("from", from, null, true, true, 0, 0);
        FileStore.Node toNode = new FileStore.Node("to", to, null, true, true, 0, 0);
        FileStore.Node target = RootMutations.create(context, fromNode, "target", true);
        text(target.file, "sentinel", "keep");
        File linkFile = new File(from, "link"); Os.symlink("target", linkFile.getPath());
        FileStore.Node source = new FileStore.Node("link", linkFile, null, true, true, 0, 0);
        source.identity = rootStat(linkFile)[0]; source.parentIdentity = target.parentIdentity;
        source.directoryIdentity = target.directoryIdentity; source.identityFollowsLinks = false;
        FileStore.Node renamed = RootMutations.rename(context, source, toNode, "renamed-link");
        // Its relative target is now absent. A path-following stat here would fail or lose the type.
        assertTrue(Files.isSymbolicLink(renamed.file.toPath())); assertFalse(renamed.file.exists());
        assertTrue(renamed.directory); assertEquals(source.directoryIdentity, renamed.directoryIdentity);
        assertEquals(source.identity, renamed.identity); assertFalse(renamed.identityFollowsLinks);
        // Restore the source link's observed target before exercising the move API independently.
        FileStore.Node targetMoved = RootMutations.moveIfSameFileSystem(context, target, toNode, "target");
        assertNotNull(targetMoved); assertEquals(target.identity, targetMoved.identity);
        FileStore.Node moved = RootMutations.moveIfSameFileSystem(context, renamed, fromNode, "moved-link");
        assertNotNull(moved); assertTrue(moved.directory); assertEquals(source.directoryIdentity, moved.directoryIdentity);
        assertEquals(source.identity, moved.identity); assertFalse(moved.identityFollowsLinks);
        assertTrue(Files.isSymbolicLink(moved.file.toPath())); assertFalse(moved.file.exists());
        assertEquals("keep", read(new File(targetMoved.file, "sentinel")));
    }

    @Test public void rootExternalCacheChmodNeverReportsAnUnappliedFuseMode() throws Exception {
        requireRoot();
        File cache = context.getExternalCacheDir(); Assume.assumeNotNull(cache);
        File external = new File(cache, "dualstack-mutations-" + UUID.randomUUID());
        Assume.assumeTrue("Requires writable external cache", external.mkdir());
        try (RootMutations.Session session = session(true)) {
            File target = text(external, "mode", "sentinel");
            StructStat initial = Os.lstat(target.getPath()); int requested = (initial.st_mode & 0777) ^ 0111;
            RootFileOps.Result result = null;
            try { result = session.execute(RootFileOps.Request.chmod(new RootFileOps.Spec(target.getPath(), null, null, null, false), requested), null); }
            catch (IOException unsupported) { assertNotNull(unsupported.getMessage()); }
            StructStat actual = Os.lstat(target.getPath());
            if (result != null) {
                assertEquals(requested, result.mode & 07777);
                assertEquals(requested, actual.st_mode & 07777);
                assertEquals(result.identity, id(actual));
            }
            assertEquals(initial.st_uid, actual.st_uid); assertEquals(initial.st_gid, actual.st_gid);
            assertEquals("sentinel", read(target));
        } finally { erase(external); }
    }

    @Test public void repeatedRequestsCloseAndEofExit() throws Exception {
        RootMutations.Session session = session(false);
        for (int i = 0; i < 24; i++) {
            RootFileOps.Result result = session.execute(RootFileOps.Request.create(spec(fixture), "file-" + i, false), null);
            assertNotNull(result.identity);
        }
        assertTrue(session.isAlive());
        session.closeGracefullyForTest();
        assertFalse(session.isAlive());
    }

    private RootMutations.Session session(boolean root) throws IOException {
        RootMutations.Session session = new RootMutations.Session(context, root); sessions.add(session); return session;
    }
    private void requireRoot() {
        // Assume only the su capability, not helper correctness: root-helper failures must fail a test.
        Assume.assumeTrue("Requires granted su", RootAccess.available());
    }
    private static RootFileOps.Spec spec(File file) throws Exception {
        StructStat stat = Os.lstat(file.getPath());
        return new RootFileOps.Spec(file.getPath(), id(stat), file.getParentFile() == null ? null : id(Os.stat(file.getParent())),
                OsConstants.S_ISDIR(stat.st_mode) ? id(stat) : null, false);
    }
    private static FileStore.Node node(File file) { return FileStore.local(file, true); }
    private static String id(StructStat stat) { return stat.st_dev + ":" + stat.st_ino; }
    private static String[] rootStat(File file) throws IOException {
        // Read-only observation in the helper's global root view, not an app-namespace inode guess.
        String[] fields = FileStore.shell("/system/bin/toybox stat -c '%d:%i|%f|%u|%g' -- "
                + FileStore.quote(file.getAbsolutePath()), true).trim().split("\\|");
        if (fields.length != 4) throw new IOException("Cannot parse root fixture metadata");
        return fields;
    }
    private static void assertActualRootResult(File file, RootFileOps.Result result) throws IOException {
        String[] actual = rootStat(file);
        assertEquals(actual[0], result.identity); assertEquals(Integer.parseInt(actual[1], 16), result.mode);
        assertEquals(Integer.parseInt(actual[2]), result.uid); assertEquals(Integer.parseInt(actual[3]), result.gid);
    }
    private static String metadata(File file) throws Exception {
        StructStat stat = Os.lstat(file.getPath()); return id(stat) + ":" + stat.st_uid + ":" + stat.st_gid + ":" + stat.st_mode;
    }
    private static File text(File parent, String name, String text) throws IOException {
        File file = new File(parent, name); Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8)); return file;
    }
    private static String read(File file) throws IOException { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
    private static void move(File from, File to) throws IOException {
        try { Os.rename(from.getPath(), to.getPath()); }
        catch (android.system.ErrnoException error) { throw new IOException(error); }
    }
    private static void link(File target, File name) throws IOException {
        try { Os.symlink(target.getPath(), name.getPath()); }
        catch (android.system.ErrnoException error) { throw new IOException(error); }
    }
    private interface Attempt { void run() throws Exception; }
    private static void expectError(Attempt attempt) throws Exception {
        try { attempt.run(); fail("Expected safe rejection"); } catch (IOException expected) { assertFalse(expected instanceof Tasks.CancelledException); }
    }
    private static void erase(File file) throws Exception {
        StructStat stat;
        try { stat = Os.lstat(file.getPath()); }
        catch (android.system.ErrnoException error) { if (error.errno == OsConstants.ENOENT) return; throw error; }
        if (OsConstants.S_ISDIR(stat.st_mode)) {
            File[] children = file.listFiles(); if (children == null) throw new IOException("Cannot list fixture " + file);
            for (File child : children) erase(child);
        }
        Os.remove(file.getPath());
    }
}
