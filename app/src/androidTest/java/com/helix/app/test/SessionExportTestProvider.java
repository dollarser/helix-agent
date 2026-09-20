package com.helix.app.test;

import android.database.Cursor;
import android.content.Intent;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Binder;
import android.os.Process;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.ProxyFileDescriptorCallback;
import android.os.storage.StorageManager;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.system.ErrnoException;
import android.system.OsConstants;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.UUID;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-APK-only real document provider with synthetic data and deterministic output faults. */
public final class SessionExportTestProvider extends DocumentsProvider {
    private final ConcurrentHashMap<String, ParcelFileDescriptor> readers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> opens = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> cancellations = new ConcurrentHashMap<>();
    private File root;
    private Handler handler;

    @Override public boolean onCreate() {
        root = new File(getContext().getFilesDir(), "session-export-documents");
        if (!root.isDirectory() && !root.mkdirs()) return false;
        HandlerThread thread = new HandlerThread("export-test-proxy");
        thread.start();
        handler = new Handler(thread.getLooper());
        return true;
    }

    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(new String[] {"root_id", "document_id", "title", "flags"});
        cursor.addRow(new Object[] {"root", "root", "Synthetic export", DocumentsContract.Root.FLAG_SUPPORTS_CREATE});
        return cursor;
    }

    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = documentCursor();
        append(cursor, id);
        return cursor;
    }

    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sort) throws FileNotFoundException {
        if (!"root".equals(parent)) throw new FileNotFoundException("Unknown parent");
        MatrixCursor cursor = documentCursor();
        File[] files = root.listFiles();
        if (files != null) for (File file : files) append(cursor, file.getName());
        return cursor;
    }

    private MatrixCursor documentCursor() {
        return new MatrixCursor(new String[] {"document_id", "_display_name", "mime_type", "flags", "_size"});
    }

    private void append(MatrixCursor cursor, String id) throws FileNotFoundException {
        boolean directory = "root".equals(id);
        File file = directory ? root : resolve(id);
        int flags = directory ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                : DocumentsContract.Document.FLAG_SUPPORTS_WRITE | DocumentsContract.Document.FLAG_SUPPORTS_DELETE;
        cursor.addRow(new Object[] {id, id, directory ? DocumentsContract.Document.MIME_TYPE_DIR
                : "application/x-ndjson", flags, directory ? 0 : file.length()});
    }

    @Override public String createDocument(String parent, String mime, String name) throws FileNotFoundException {
        if (!"root".equals(parent) || !name.matches("normal|denied|write-error|close-error|slow|slow-open|no-space")) {
            throw new FileNotFoundException("Unknown fixture");
        }
        String id = name + "-" + UUID.randomUUID();
        try {
            if (!new File(root, id).createNewFile()) throw new IOException("Duplicate fixture");
        } catch (IOException error) {
            throw new FileNotFoundException("Cannot create fixture");
        }
        return id;
    }

    @Override public void deleteDocument(String id) throws FileNotFoundException {
        File file = resolve(id);
        ParcelFileDescriptor reader = readers.remove(id);
        try {
            if (reader != null) reader.close();
        } catch (IOException ignored) { /* No reader remains exposed after this fixture is removed. */ }
        if (!file.delete()) throw new FileNotFoundException("Cannot delete fixture");
        opens.remove(id);
        cancellations.remove(id);
    }

    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        File file = resolve(id);
        if (mode.equals("r")) return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        opens.computeIfAbsent(id, key -> new AtomicInteger()).incrementAndGet();
        CountDownLatch cancelled = new CountDownLatch(1);
        if (signal != null) signal.setOnCancelListener(() -> {
            cancellations.computeIfAbsent(id, key -> new AtomicInteger()).incrementAndGet();
            cancelled.countDown();
        });
        if (id.startsWith("slow-open-")) {
            try {
                if (!cancelled.await(30, TimeUnit.SECONDS)) throw new FileNotFoundException("Open cancellation timed out");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new FileNotFoundException("Open interrupted");
            }
            if (signal != null) signal.throwIfCanceled();
            throw new FileNotFoundException("Missing cancellation signal");
        }
        if (id.startsWith("denied-")) throw new SecurityException("Synthetic revoked grant");
        try {
            if (id.startsWith("no-space-")) {
                StorageManager manager = getContext().getSystemService(StorageManager.class);
                return manager.openProxyFileDescriptor(ParcelFileDescriptor.MODE_WRITE_ONLY,
                        new ProxyFileDescriptorCallback() {
                            @Override public long onGetSize() { return 0L; }
                            @Override public int onWrite(long offset, int size, byte[] data) throws ErrnoException {
                                throw new ErrnoException("synthetic export write", OsConstants.ENOSPC);
                            }
                            @Override public void onFsync() { }
                            @Override public void onRelease() { }
                        }, handler);
            }
            if (id.startsWith("slow-") || id.startsWith("close-error-") || id.startsWith("write-error-")) {
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
                readers.put(id, pipe[0]);
                if (id.startsWith("write-error-")) pipe[0].closeWithError("Synthetic write failure");
                return pipe[1];
            }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
        } catch (IOException error) {
            throw new FileNotFoundException("Cannot open fixture");
        }
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if ("fixture-create".equals(method)) {
            if (Binder.getCallingUid() != Process.myUid()) throw new SecurityException("Fixture owner only");
            try {
                String id = createDocument("root", "application/x-ndjson", arg);
                Uri uri = DocumentsContract.buildDocumentUri(extras.getString("authority"), id);
                getContext().grantUriPermission(extras.getString("package"), uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                Bundle result = new Bundle();
                result.putString("uri", uri.toString());
                return result;
            } catch (FileNotFoundException error) { throw new IllegalStateException(error); }
        }
        if ("fixture-exists".equals(method)) {
            Bundle result = new Bundle();
            try { result.putBoolean("exists", resolve(arg).isFile()); }
            catch (FileNotFoundException error) { result.putBoolean("exists", false); }
            return result;
        }
        if ("fixture-tail".equals(method)) {
            try (RandomAccessFile file = new RandomAccessFile(resolve(arg), "r")) {
                int size = (int) Math.min(4096L, file.length());
                byte[] bytes = new byte[size];
                file.seek(file.length() - size);
                file.readFully(bytes);
                Bundle result = new Bundle();
                result.putBoolean("complete", new String(bytes, StandardCharsets.UTF_8).contains("\"type\":\"complete\""));
                result.putLong("size", file.length());
                return result;
            } catch (IOException error) { throw new IllegalStateException(error); }
        }
        if ("fixture-fail-close".equals(method)) {
            ParcelFileDescriptor reader = readers.get(arg);
            try {
                if (reader == null) throw new IOException("Missing pipe");
                reader.closeWithError("Synthetic peer failure before close");
            } catch (IOException error) { throw new IllegalStateException(error); }
            return Bundle.EMPTY;
        }
        if ("fixture-opens".equals(method)) {
            Bundle result = new Bundle();
            AtomicInteger count = opens.get(arg);
            result.putInt("count", count == null ? 0 : count.get());
            return result;
        }
        if ("fixture-cancellations".equals(method)) {
            Bundle result = new Bundle();
            AtomicInteger count = cancellations.get(arg);
            result.putInt("count", count == null ? 0 : count.get());
            return result;
        }
        return super.call(method, arg, extras);
    }

    private File resolve(String id) throws FileNotFoundException {
        if (id == null || !id.matches("[a-z-]+[0-9a-f-]{36}")) throw new FileNotFoundException("Invalid fixture id");
        File file = new File(root, id);
        if (!file.isFile()) throw new FileNotFoundException("Missing fixture");
        return file;
    }
}
