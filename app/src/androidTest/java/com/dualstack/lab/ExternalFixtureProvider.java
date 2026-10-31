package com.dualstack.lab;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;
import java.util.zip.*;

/** Separate test APK provider: both a disk descriptor and a pathless stream. */
public class ExternalFixtureProvider extends ContentProvider {
    static final String AUTHORITY = "com.dualstack.lab.test.external";
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "application/zip"; }
    static byte[] zipBytes() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            ZipEntry entry = new ZipEntry("provider.txt");
            entry.setTime(946684800000L);
            zip.putNextEntry(entry);
            zip.write("PROVIDER_OK".getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
        }
        return bytes.toByteArray();
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] arguments, String order) {
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        cursor.addRow(new Object[]{"provider.zip", null});
        return cursor;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only fixture");
        try {
            if ("/private".equals(uri.getPath())) {
                File file = new File(getContext().getCacheDir(), "private-original.zip");
                java.nio.file.Files.write(file.toPath(), zipBytes());
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            }
            if ("/disk".equals(uri.getPath())) {
                File file = new File(uri.getQueryParameter("path")).getCanonicalFile();
                File base = new File("/storage/emulated/0/Download/dualstack-external-069").getCanonicalFile();
                if (!file.toPath().startsWith(base.toPath())) throw new FileNotFoundException("Outside fixture");
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            }
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            new Thread(() -> {
                try (OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    output.write(zipBytes());
                } catch (IOException ignored) { }
            }, "external-fixture-pipe").start();
            return pipe[0];
        } catch (IOException | RuntimeException e) { throw new FileNotFoundException(e.getMessage()); }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] arguments) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] arguments) { throw new UnsupportedOperationException(); }
}
