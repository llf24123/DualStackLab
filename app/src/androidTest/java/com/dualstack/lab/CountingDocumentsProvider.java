package com.dualstack.lab;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class CountingDocumentsProvider extends DocumentsProvider {
    static final String AUTHORITY = "com.dualstack.lab.test.documents";
    private static final String[] DOCUMENT_COLUMNS = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS};
    public static class GrantReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            if (!(AUTHORITY + ".GRANT").equals(intent.getAction())) return;
            try {
                Bundle result = context.getContentResolver().call(Uri.parse("content://" + AUTHORITY), "reset", null, null);
                setResultExtras(result); setResultCode(android.app.Activity.RESULT_OK);
            } catch (RuntimeException error) {
                Bundle result = new Bundle(); result.putString("error", error.toString());
                setResultExtras(result); setResultCode(android.app.Activity.RESULT_CANCELED);
            }
        }
    }
    private File root;
    private int childQueries, createCalls, deleteCalls;
    private String failCreateName, failReadName, renameName, collideName, blockReadName, failDeleteName;
    private ReadGate readGate;
    private static final class ReadGate {
        final CountDownLatch released = new CountDownLatch(1);
        volatile boolean prefixWritten;
        volatile String error;
    }

    @Override public boolean onCreate() {
        try {
            root = new File(getContext().getFilesDir(), "counting-documents-fixture").getCanonicalFile();
            return root.isDirectory() || root.mkdir();
        } catch (IOException error) { return false; }
    }
    @Override public synchronized Cursor queryRoots(String[] projection) {
        String[] columns = projection != null ? projection : new String[]{DocumentsContract.Root.COLUMN_ROOT_ID,
                DocumentsContract.Root.COLUMN_DOCUMENT_ID, DocumentsContract.Root.COLUMN_TITLE,
                DocumentsContract.Root.COLUMN_FLAGS};
        MatrixCursor cursor = new MatrixCursor(columns);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) row.add(switch (column) {
            case DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID -> "root";
            case DocumentsContract.Root.COLUMN_TITLE -> "Batch copy test fixture";
            case DocumentsContract.Root.COLUMN_FLAGS -> DocumentsContract.Root.FLAG_SUPPORTS_CREATE
                    | DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD;
            default -> null;
        });
        return cursor;
    }
    @Override public synchronized Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOCUMENT_COLUMNS);
        append(cursor, resolve(documentId)); return cursor;
    }
    @Override public synchronized Cursor queryChildDocuments(String parentDocumentId, String[] projection,
                                                            String sortOrder) throws FileNotFoundException {
        childQueries++;
        File parent = resolve(parentDocumentId);
        File[] files = parent.listFiles();
        if (files == null) throw new FileNotFoundException("Not a readable fixture directory");
        Arrays.sort(files, Comparator.comparing(File::getName));
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOCUMENT_COLUMNS);
        for (File file : files) append(cursor, file);
        return cursor;
    }
    @Override public synchronized String createDocument(String parentDocumentId, String mimeType,
                                                       String displayName) throws FileNotFoundException {
        createCalls++; checkName(displayName);
        File parent = resolve(parentDocumentId);
        if (!parent.isDirectory()) throw new FileNotFoundException("Parent is not a directory");
        if (displayName.equals(failCreateName)) throw new FileNotFoundException("Injected create failure");
        if (displayName.equals(collideName)) {
            collideName = null;
            create(parent, displayName, false, "concurrent-owner");
        }
        String requested = displayName.equals(renameName) ? "provider-" + displayName : displayName;
        return id(create(parent, available(parent, requested), DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType), ""));
    }
    @Override public synchronized ParcelFileDescriptor openDocument(String documentId, String mode,
                                                                    CancellationSignal signal) throws FileNotFoundException {
        File file = resolve(documentId);
        if (mode.equals("r") && file.getName().equals(failReadName))
            throw new FileNotFoundException("Injected read failure");
        if (mode.equals("r") && file.getName().equals(blockReadName)) {
            blockReadName = null;
            try {
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                ReadGate gate = new ReadGate(); readGate = gate;
                Thread writer = new Thread(() -> {
                    try (InputStream input = new FileInputStream(file);
                         OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                        byte[] bytes = new byte[128 * 1024];
                        int count = input.read(bytes);
                        if (count > 0) output.write(bytes, 0, count);
                        gate.prefixWritten = true;
                        if (!gate.released.await(60, TimeUnit.SECONDS)) throw new IOException("Blocked fixture read timed out");
                        while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
                    } catch (IOException error) { gate.error = error.toString(); }
                    catch (InterruptedException error) { gate.error = error.toString(); Thread.currentThread().interrupt(); }
                }, "batch-copy-fixture-pipe");
                writer.setDaemon(true); writer.start(); return pipe[0];
            } catch (IOException error) { throw failure(error); }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
    }
    @Override public synchronized void deleteDocument(String documentId) throws FileNotFoundException {
        File file = resolve(documentId); deleteCalls++;
        if (file.getName().equals(failDeleteName)) throw new SecurityException("Injected delete failure");
        if (file.equals(root)) throw new FileNotFoundException("Cannot delete fixture root");
        try { remove(file); } catch (IOException error) { throw failure(error); }
    }
    @Override public synchronized boolean isChildDocument(String parentDocumentId, String documentId) {
        try {
            File parent = resolve(parentDocumentId), child = resolve(documentId);
            return !parent.equals(child) && child.toPath().startsWith(parent.toPath());
        } catch (FileNotFoundException error) { return false; }
    }
    @Override public synchronized Bundle call(String method, String argument, Bundle extras) {
        if (method.startsWith("android:")) return super.call(method, argument, extras);
        Bundle result = new Bundle();
        try {
            switch (method) {
                case "reset" -> {
                    if (readGate != null) readGate.released.countDown();
                    readGate = null;
                    File[] files = root.listFiles();
                    if (files == null) throw new IOException("Fixture root is unavailable");
                    for (File file : files) remove(file);
                    childQueries = createCalls = deleteCalls = 0;
                    failCreateName = failReadName = renameName = collideName = blockReadName = failDeleteName = null;
                    Uri tree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root");
                    getContext().grantUriPermission("com.dualstack.lab", tree,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                    result.putString("uri", DocumentsContract.buildDocumentUriUsingTree(tree, "root").toString());
                }
                case "clearCounters" -> { childQueries = createCalls = deleteCalls = 0; }
                case "readState" -> {
                    result.putBoolean("opened", readGate != null);
                    result.putBoolean("prefixWritten", readGate != null && readGate.prefixWritten);
                    result.putString("error", readGate == null ? null : readGate.error);
                }
                case "releaseRead" -> { if (readGate != null) readGate.released.countDown(); }
                case "configure" -> {
                    failCreateName = extras.getString("failCreateName");
                    failReadName = extras.getString("failReadName");
                    renameName = extras.getString("renameName"); collideName = extras.getString("collideName");
                    blockReadName = extras.getString("blockReadName"); failDeleteName = extras.getString("failDeleteName");
                }
                case "seed" -> {
                    File parent = resolve(extras.getString("parent", "root"));
                    String name = extras.getString("name"); checkName(name);
                    result.putString("id", id(create(parent, name, extras.getBoolean("directory"), extras.getString("text", ""))));
                }
                case "stats" -> {
                    result.putInt("childQueries", childQueries); result.putInt("createCalls", createCalls);
                    result.putInt("deleteCalls", deleteCalls);
                    ArrayList<String> paths = new ArrayList<>(); collect(root, paths);
                    Collections.sort(paths); result.putStringArrayList("paths", paths);
                }
                default -> throw new IllegalArgumentException("Unknown fixture method: " + method);
            }
        } catch (IOException error) { throw new IllegalStateException(error); }
        return result;
    }
    private void collect(File parent, List<String> paths) throws IOException {
        File[] files = parent.listFiles(); if (files == null) throw new IOException("Cannot list fixture");
        for (File file : files) {
            paths.add(id(file).substring(5) + (file.isDirectory() ? "/" : ""));
            if (file.isDirectory()) collect(file, paths);
        }
    }
    private File resolve(String documentId) throws FileNotFoundException {
        if ("root".equals(documentId)) return root;
        if (documentId == null || !documentId.startsWith("root/")) throw new FileNotFoundException("Invalid fixture ID");
        try {
            File file = new File(root, documentId.substring(5)).getCanonicalFile();
            if (file.equals(root) || !file.toPath().startsWith(root.getCanonicalFile().toPath()) || !file.exists())
                throw new FileNotFoundException("Outside fixture or missing document");
            return file;
        } catch (IOException error) { throw failure(error); }
    }
    private String id(File file) { return file.equals(root) ? "root" : "root/" + root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/'); }
    private void append(MatrixCursor cursor, File file) throws FileNotFoundException {
        if (!file.exists()) throw new FileNotFoundException("Missing fixture document");
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) row.add(switch (column) {
            case DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id(file);
            case DocumentsContract.Document.COLUMN_DISPLAY_NAME -> file.equals(root) ? "fixture" : file.getName();
            case DocumentsContract.Document.COLUMN_MIME_TYPE -> file.isDirectory() ? DocumentsContract.Document.MIME_TYPE_DIR : "application/octet-stream";
            case DocumentsContract.Document.COLUMN_SIZE -> file.isDirectory() ? 0L : file.length();
            case DocumentsContract.Document.COLUMN_LAST_MODIFIED -> file.lastModified();
            case DocumentsContract.Document.COLUMN_FLAGS -> file.isDirectory() ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                    | DocumentsContract.Document.FLAG_SUPPORTS_DELETE : DocumentsContract.Document.FLAG_SUPPORTS_WRITE
                    | DocumentsContract.Document.FLAG_SUPPORTS_DELETE;
            default -> null;
        });
    }
    private static void checkName(String name) throws FileNotFoundException {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0)
            throw new FileNotFoundException("Invalid fixture name");
    }
    private File create(File parent, String name, boolean directory, String text) throws FileNotFoundException {
        checkName(name);
        File file = new File(parent, name);
        try {
            if (!(directory ? file.mkdir() : file.createNewFile())) throw new IOException("Fixture collision: " + name);
            if (!directory) Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
            return file;
        } catch (IOException error) { throw failure(error); }
    }
    private static String available(File parent, String original) {
        if (!new File(parent, original).exists()) return original;
        int dot = original.lastIndexOf('.'); String base = dot > 0 ? original.substring(0, dot) : original;
        String extension = dot > 0 ? original.substring(dot) : "";
        for (int i = 1;; i++) {
            String next = base + " (" + i + ")" + extension;
            if (!new File(parent, next).exists()) return next;
        }
    }
    private static void remove(File file) throws IOException {
        Files.walkFileTree(file.toPath(), new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path path, java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                Files.delete(path); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path path, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(path); return FileVisitResult.CONTINUE;
            }
        });
    }
    private static FileNotFoundException failure(IOException error) {
        FileNotFoundException result = new FileNotFoundException(error.getMessage()); result.initCause(error); return result;
    }
}
