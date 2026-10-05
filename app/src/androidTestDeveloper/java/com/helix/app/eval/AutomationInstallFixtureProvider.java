package com.helix.app.eval;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** Framework-only provider: the standalone test APK process has no target Kotlin runtime. */
public final class AutomationInstallFixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    private File fixture(Uri uri) {
        if (!"/fixture.apk".equals(uri.getPath())) throw new IllegalArgumentException("Invalid fixture");
        return new File(getContext().getCacheDir(), "install-fixture.apk");
    }

    @Override public String getType(Uri uri) {
        fixture(uri);
        return "application/vnd.android.package-archive";
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new IllegalArgumentException("Read-only fixture");
        return ParcelFileDescriptor.open(fixture(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor result = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        result.addRow(new Object[]{"Helix Install Fixture.apk", fixture(uri).length()});
        return result;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read-only fixture"); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException("Read-only fixture"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException("Read-only fixture"); }
}
