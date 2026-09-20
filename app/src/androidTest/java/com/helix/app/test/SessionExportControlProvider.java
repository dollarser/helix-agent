package com.helix.app.test;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

/** Test-APK-only owner process grants synthetic documents. Production does not package this provider. */
public final class SessionExportControlProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!method.startsWith("fixture-")) throw new IllegalArgumentException("Fixture operations only");
        long identity = Binder.clearCallingIdentity();
        try {
            return getContext().getContentResolver().call(getContext().getPackageName() + ".sessionexport", method, arg, extras);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        throw new UnsupportedOperationException();
    }
    @Override public String getType(Uri uri) { throw new UnsupportedOperationException(); }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
}
