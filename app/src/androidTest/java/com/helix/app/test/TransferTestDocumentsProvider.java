package com.helix.app.test;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HXA-058 (device gate): the document fixtures for the file manager's import/export entries —
 * driven through the REAL {@link android.content.ContentResolver} adapters (the same code path a
 * host provider takes). Declared {@code exported=true} in the androidTest manifest (required:
 * the app module's instrumentation runs in the APP process, a DIFFERENT uid from this test
 * package, so a non-exported provider there is unreachable from the app process —
 * device-verified; the test APK is never shipped, so the export has no production surface).
 *
 * <p><b>This class is deliberately written in Java.</b> A provider declared in the test manifest
 * is hosted in the TEST package's own process, whose classpath is ONLY the test APK. The app
 * module's androidTest APK does not contain the Kotlin standard library (AGP does not dex it
 * into the test APK even when declared explicitly on {@code androidTestImplementation};
 * device-verified: {@code NoClassDefFoundError: kotlin/jvm/internal/Intrinsics} at
 * {@code ContentProvider.attachInfo} in the provider process) — a Kotlin provider crashes the
 * host process before it can answer a single query. The test CLASSES run in the app process
 * (whose APK carries the stdlib), so only this provider must stay Kotlin-free.
 *
 * <p>Import sources (document URIs {@code content://<auth>/document/<id>}, OpenableColumns
 * metadata):
 * <ul>
 *   <li>{@code honest} — 6 bytes "hello\n", honest size (the clean import);
 *   <li>{@code under}  — 10 real bytes but a REPORTED size of 4 (the lying-provider size case);
 *   <li>{@code denied} — metadata answers, {@link #openFile} throws SecurityException (a
 *       revoked grant);
 * </ul>
 *
 * <p>Export destinations:
 * <ul>
 *   <li>{@code sink} — mutable, file-backed, HONEST post-write size (the verified export);
 *   <li>{@code liar} — mutable, file-backed, post-write size + 3 (the size re-check refusal);
 * </ul>
 *
 * <p>Export-into-tree (the {@code content://<auth>/tree/tr/...} section): a mutable tree with
 * {@code note.txt} (a same-name conflict target) and an ambiguous {@code same.txt} pair,
 * supporting the create-document insert + read/write {@link #openFile} (the post-export size
 * re-check and re-read see exactly what was written).
 *
 * <p>A {@code content://} URI never reaches a model-visible or diagnostic surface (doc 10).
 */
public class TransferTestDocumentsProvider extends ContentProvider {

    public static final String AUTHORITY = "com.helix.app.transfersink";

    public static final String TREE_URI = "content://" + AUTHORITY + "/tree/tr";

    /** The document rows carry the column NAMES the OpenableColumns readers look up. */
    /**
     * The document rows carry the column NAMES the OpenableColumns readers look up. NOTE the
     * underscore prefixes: {@code OpenableColumns.SIZE} is {@code "_size"} and
     * {@code OpenableColumns.DISPLAY_NAME} is {@code "_display_name"} — a plain "size" /
     * "display_name" cursor column is invisible to the by-name readers (device-verified: the
     * import silently degrades to the fallback name and the post-write size re-check reads -1).
     */
    public static final String[] OPENABLE_COLUMNS = {
        OpenableColumns.SIZE,
        OpenableColumns.DISPLAY_NAME,
        "mime_type",
        "document_id",
    };

    /** The tree child rows are read by POSITION: id, name, mime, size. */
    public static final String[] TREE_COLUMNS = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
    };

    /** The document record. */
    private static final class Doc {
        final String id;
        final String name;
        final boolean isDir;
        final String mime;
        final File file;
        final Long reportedSizeOverride;

        Doc(String id, String name, boolean isDir, String mime, File file, Long reportedSizeOverride) {
            this.id = id;
            this.name = name;
            this.isDir = isDir;
            this.mime = mime;
            this.file = file;
            this.reportedSizeOverride = reportedSizeOverride;
        }

        Doc(String id, String name, boolean isDir, String mime, File file) {
            this(id, name, isDir, mime, file, null);
        }
    }

    private Context appContext;
    private final Map<String, Doc> docs = new LinkedHashMap<>();
    private final Map<String, List<String>> children = new LinkedHashMap<>();
    private int nextId = 0;
    private static long token = 0L;

    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        // Capture the context here (not the deprecated `context` field): `onCreate` runs inside
        // super.attachInfo and already needs it.
        appContext = context;
        super.attachInfo(context, info);
    }

    @Override
    public boolean onCreate() {
        // `pm install -r` keeps app data: clear previous runs' backing files so the in-memory
        // state and the on-disk bytes can never disagree.
        File dir = sinkDir();
        dir.mkdirs();
        File[] existing = dir.listFiles();
        if (existing != null) {
            for (File f : existing) {
                f.delete();
            }
        }
        seed();
        return true;
    }

    @Override
    public String getType(Uri uri) {
        String id = docIdOf(uri);
        if (id == null) {
            return null;
        }
        if (id.equals("honest") || id.equals("under") || id.equals("denied")) {
            return "text/plain";
        }
        return "application/octet-stream";
    }

    @Override
    public Cursor query(
        Uri uri,
        String[] projection,
        String selection,
        String[] selectionArgs,
        String sortOrder
    ) {
        List<String> segments = uri.getPathSegments();
        if (
            segments.size() == 3 &&
            "document".equals(segments.get(0)) &&
            "children".equals(segments.get(2))
        ) {
            // `content://<auth>/document/<parentId>/children` — the tree child listing, in the
            // fixed positional order the governed tree queries read (id, name, mime, size).
            MatrixCursor cursor = new MatrixCursor(TREE_COLUMNS);
            for (Doc child : childrenOf(segments.get(1))) {
                cursor.addRow(treeRow(child));
            }
            return cursor;
        }
        if (segments.size() == 2 && "document".equals(segments.get(0))) {
            // `content://<auth>/document/<id>` — a single document. The reader/verifier read
            // `size` / `display_name` BY NAME, so the cursor carries those exact column names.
            Doc doc = docs.get(segments.get(1));
            if (doc == null) {
                return emptyLive();
            }
            MatrixCursor cursor = new MatrixCursor(OPENABLE_COLUMNS);
            cursor.addRow(new Object[] {reportedSizeFor(doc), doc.name, doc.mime, doc.id});
            return cursor;
        }
        // Any other shape (e.g. the tree-root liveness query) answers an empty-but-live cursor.
        return emptyLive();
    }

    /**
     * The create-document insert: {@code content://<auth>/tree/<rootDocId>/document/<parentDocId>}
     * — the EXACT shape {@code DocumentsContract.buildDocumentUriUsingTree} builds (the
     * tree-scoped document URI, logcat-verified on API 29/36) — the operation
     * {@code DocumentsContract.createDocument} performs internally. The framework's
     * {@code DocumentsProvider} UriMatcher matches this as {@code tree/#/document/#}
     * (CREATE_DOCUMENT_IN_TREE); a plain fixture provider must match the same 4-segment shape.
     */
    @Override
    public Uri insert(Uri uri, ContentValues values) {
        List<String> segments = uri.getPathSegments();
        if (segments.size() != 4 || !"tree".equals(segments.get(0)) || !"document".equals(segments.get(2))) {
            return null;
        }
        Doc parent = docs.get(segments.get(3));
        if (parent == null) {
            // The SDK `insert` surface cannot declare the checked FileNotFoundException;
            // the production destination converts ANY insert failure into one stable refusal
            // (ContentResolverSafTreeDestination's catch-all), so a runtime exception is
            // behaviorally identical for the device tests.
            throw new RuntimeException("no such parent");
        }
        if (!parent.isDir) {
            throw new RuntimeException("parent is not a directory");
        }
        String id = "d" + nextId++;
        String name =
            values != null && values.getAsString(DocumentsContract.Document.COLUMN_DISPLAY_NAME) != null
                ? values.getAsString(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                : "new-document";
        String mime =
            values != null && values.getAsString(DocumentsContract.Document.COLUMN_MIME_TYPE) != null
                ? values.getAsString(DocumentsContract.Document.COLUMN_MIME_TYPE)
                : "application/octet-stream";
        File file = backingFile(id);
        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        }
        docs.put(id, new Doc(id, name, false, mime, file));
        List<String> siblings = children.get(segments.get(3));
        if (siblings == null) {
            siblings = new ArrayList<>();
            children.put(segments.get(3), siblings);
        }
        siblings.add(id);
        return Uri.parse("content://" + AUTHORITY + "/document/" + id);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String id = docIdOf(uri);
        if (id == null) {
            throw new FileNotFoundException("no such document");
        }
        Doc doc = docs.get(id);
        if (doc == null) {
            throw new FileNotFoundException("no such document: " + id);
        }
        if (id.equals("denied")) {
            throw new SecurityException("grant revoked");
        }
        if (id.equals("honest")) {
            return readOnly("hello\n".getBytes(StandardCharsets.UTF_8));
        }
        if (id.equals("under")) {
            return readOnly("0123456789".getBytes(StandardCharsets.UTF_8));
        }
        // 10 real bytes vs the reported 4 for `under` (handled above).
        File file = doc.file;
        if (file == null) {
            throw new FileNotFoundException("not a file document: " + id);
        }
        if ("r".equals(mode)) {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        return ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_READ_WRITE |
            ParcelFileDescriptor.MODE_CREATE |
            ParcelFileDescriptor.MODE_TRUNCATE
        );
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    // ── The fixtures ──────────────────────────────────────────────────────────────────────

    private File sinkDir() {
        return new File(appContext.getFilesDir(), "transfersink");
    }

    private File backingFile(String id) {
        return new File(sinkDir(), id + ".bin");
    }

    private void seed() {
        if (!docs.isEmpty()) {
            return;
        }
        docs.put("honest", new Doc("honest", "honest.txt", false, "text/plain", null));
        docs.put("under", new Doc("under", "under.txt", false, "text/plain", null, 4L));
        docs.put("denied", new Doc("denied", "denied.txt", false, "text/plain", null));
        File sink = backingFile("sink");
        ensureFile(sink);
        docs.put("sink", new Doc("sink", "sink.txt", false, "text/plain", sink));
        File liar = backingFile("liar");
        ensureFile(liar);
        docs.put("liar", new Doc("liar", "liar.txt", false, "text/plain", liar));
        // The export tree: content://<auth>/tree/tr
        docs.put("tr", new Doc("tr", "tr", true, DocumentsContract.Document.MIME_TYPE_DIR, null));
        File tnote = backingFile("tnote");
        writeBytes(tnote, "v1".getBytes(StandardCharsets.UTF_8));
        docs.put("tnote", new Doc("tnote", "note.txt", false, "text/plain", tnote));
        docs.put("tdup", new Doc("tdup", "tdup", true, DocumentsContract.Document.MIME_TYPE_DIR, null));
        File t1 = backingFile("t1");
        writeBytes(t1, "1".getBytes(StandardCharsets.UTF_8));
        docs.put("t1", new Doc("t1", "same.txt", false, "text/plain", t1));
        File t2 = backingFile("t2");
        writeBytes(t2, "2".getBytes(StandardCharsets.UTF_8));
        docs.put("t2", new Doc("t2", "same.txt", false, "text/plain", t2));
        List<String> trChildren = new ArrayList<>();
        trChildren.add("tnote");
        trChildren.add("tdup");
        children.put("tr", trChildren);
        List<String> dupChildren = new ArrayList<>();
        dupChildren.add("t1");
        dupChildren.add("t2");
        children.put("tdup", dupChildren);
    }

    private static void ensureFile(File f) {
        if (!f.exists()) {
            try {
                f.createNewFile();
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static void writeBytes(File f, byte[] bytes) {
        try {
            Files.write(f.toPath(), bytes);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String docIdOf(Uri uri) {
        List<String> segments = uri.getPathSegments();
        if (segments.size() == 2 && "document".equals(segments.get(0))) {
            return segments.get(1);
        }
        return null;
    }

    private List<Doc> childrenOf(String parentId) {
        List<Doc> result = new ArrayList<>();
        List<String> ids = children.get(parentId);
        if (ids != null) {
            for (String id : ids) {
                Doc d = docs.get(id);
                if (d != null) {
                    result.add(d);
                }
            }
        }
        return result;
    }

    /** The size the provider REPORTS (the honest file size, or the fixture's lie). */
    private long reportedSizeFor(Doc doc) {
        if (doc.reportedSizeOverride != null) {
            return doc.reportedSizeOverride;
        }
        long fileLen = doc.file != null && doc.file.exists() ? doc.file.length() : -1L;
        if (doc.id.equals("liar")) {
            return (doc.file != null && doc.file.exists() ? doc.file.length() : 0L) + 3L;
        }
        return fileLen;
    }

    private Object[] treeRow(Doc doc) {
        return new Object[] {
            doc.id,
            doc.name,
            doc.mime,
            doc.isDir ? -1L : reportedSizeFor(doc),
        };
    }

    private Cursor emptyLive() {
        return new MatrixCursor(OPENABLE_COLUMNS);
    }

    private ParcelFileDescriptor readOnly(byte[] bytes) throws FileNotFoundException {
        File file = new File(sinkDir(), "ro-" + (++token) + ".bin");
        writeBytes(file, bytes);
        // The temp file lives only for the open; the stream is read synchronously by the caller.
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }
}
