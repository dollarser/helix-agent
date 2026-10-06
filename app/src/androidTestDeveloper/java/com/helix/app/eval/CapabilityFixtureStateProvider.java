package com.helix.app.eval;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/** Test-only independent oracle. Java avoids depending on the target APK's Kotlin runtime. */
public final class CapabilityFixtureStateProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        if ("/touch".equals(uri.getPath())) {
            SharedPreferences prefs = getContext().getSharedPreferences("touch-review", 0);
            MatrixCursor cursor = new MatrixCursor(new String[] {"x", "y", "clicks", "event"});
            cursor.addRow(new Object[] {prefs.getInt("x", 0), prefs.getInt("y", 0),
                prefs.getInt("clicks", 0), prefs.getString("event", "")});
            return cursor;
        }
        if ("/install-ready".equals(uri.getPath())) {
            MatrixCursor cursor = new MatrixCursor(new String[] {"ready"});
            cursor.addRow(new Object[] {new java.io.File(getContext().getCacheDir(), "install-fixture.apk").isFile() ? 1 : 0});
            return cursor;
        }
        if (!"/result".equals(uri.getPath())) throw new IllegalArgumentException("Unknown fixture");
        SharedPreferences prefs = getContext().getSharedPreferences("capability-fixture", 0);
        MatrixCursor cursor = new MatrixCursor(new String[] {"result", "clicks", "notification"});
        cursor.addRow(new Object[] {prefs.getString("result", ""), prefs.getInt("clicks", 0), prefs.getInt("notification", -1)});
        return cursor;
    }
    @Override public String getType(Uri uri) { return "vnd.android.cursor.item/helix-eval"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
}
