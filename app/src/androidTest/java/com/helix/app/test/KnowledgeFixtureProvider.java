package com.helix.app.test;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;

/** Synthetic bytes only. Non-exported; the test sender grants each selected content URI. */
public class KnowledgeFixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) {
        return "/image".equals(uri.getPath()) ? "image/png" : "text/plain";
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME});
        cursor.addRow(new Object[]{"Synthetic " + uri.getLastPathSegment()});
        return cursor;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read only");
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            new Thread(() -> {
                try (ParcelFileDescriptor.AutoCloseOutputStream out =
                         new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    if ("/large".equals(uri.getPath())) {
                        byte[] block = new byte[8192];
                        for (int i = 0; i <= 2048; i++) out.write(block);
                    } else out.write("Synthetic material 知识引用".getBytes(StandardCharsets.UTF_8));
                } catch (IOException expectedClosedReader) { /* Receiver may reject an oversized fixture. */ }
            }, "knowledge-fixture").start();
            return pipe[0];
        } catch (IOException failure) { throw new FileNotFoundException("fixture pipe failed"); }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
