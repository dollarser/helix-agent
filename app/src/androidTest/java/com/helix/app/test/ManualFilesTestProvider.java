package com.helix.app.test;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import java.io.File;
import java.io.FileNotFoundException;

/** Synthetic DocumentsContract fixture; only installed in the test APK. */
public class ManualFilesTestProvider extends ContentProvider {
    private File root() {
        File root = new File(getContext().getFilesDir(), "manual-files-fixture");
        root.mkdirs();
        return root;
    }
    @Override public boolean onCreate() { root(); return true; }
    private File file(Uri uri) {
        String id = DocumentsContract.getDocumentId(uri);
        if (id.equals("root")) return root();
        if (!id.startsWith("root/") || id.contains("..")) throw new IllegalArgumentException("Bad fixture id");
        return new File(root(), id.substring(5));
    }
    private String id(File file) {
        if (file.equals(root())) return "root";
        return "root/" + root().toPath().relativize(file.toPath()).toString();
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        MatrixCursor result = new MatrixCursor(projection);
        File selected = file(uri);
        if (uri.getLastPathSegment().equals("children")) {
            File[] files = selected.listFiles();
            if (files == null) throw new IllegalStateException("Not a directory");
            for (File item : files) add(result, projection, item);
        } else if (selected.exists()) add(result, projection, selected);
        return result;
    }
    private void add(MatrixCursor cursor, String[] columns, File item) {
        Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            switch (columns[i]) {
                case Document.COLUMN_DOCUMENT_ID: values[i] = id(item); break;
                case Document.COLUMN_DISPLAY_NAME: values[i] = item.getName(); break;
                case Document.COLUMN_MIME_TYPE: values[i] = item.isDirectory() ? Document.MIME_TYPE_DIR : "text/plain"; break;
                case Document.COLUMN_SIZE: values[i] = item.length(); break;
                case Document.COLUMN_LAST_MODIFIED: values[i] = item.lastModified(); break;
                case Document.COLUMN_FLAGS: values[i] = Document.FLAG_SUPPORTS_WRITE | (item.getName().startsWith("no-rename") ? 0 : Document.FLAG_SUPPORTS_RENAME) |
                    Document.FLAG_SUPPORTS_DELETE | (item.isDirectory() ? Document.FLAG_DIR_SUPPORTS_CREATE : 0); break;
                default: values[i] = null;
            }
        }
        cursor.addRow(values);
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        Uri uri = extras.getParcelable("uri");
        File target = file(uri);
        Bundle result = new Bundle();
        if (method.equals("android:createDocument")) {
            String name = extras.getString("_display_name");
            File created = new File(target, name);
            try {
                boolean success = Document.MIME_TYPE_DIR.equals(extras.getString("mime_type")) ? created.mkdir() : created.createNewFile();
                if (!success) throw new IllegalStateException("Create failed");
            } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
            result.putParcelable("uri", DocumentsContract.buildDocumentUriUsingTree(uri, id(created)));
        } else if (method.equals("android:renameDocument")) {
            File renamed = new File(target.getParentFile(), extras.getString("_display_name"));
            if (renamed.exists() || !target.renameTo(renamed)) throw new IllegalStateException("Rename failed");
            result.putParcelable("uri", DocumentsContract.buildDocumentUriUsingTree(uri, id(renamed)));
        } else if (method.equals("android:deleteDocument")) {
            if (!target.delete()) throw new IllegalStateException("Delete failed");
        } else throw new UnsupportedOperationException(method);
        return result;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.parseMode(mode));
    }
    @Override public String getType(Uri uri) { return file(uri).isDirectory() ? Document.MIME_TYPE_DIR : "text/plain"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
