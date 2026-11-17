package com.agustin.sumi.data.local.document;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class PickerFixtureProvider extends DocumentsProvider {
    static final String TITLE = "Sumi test files";
    static final String CBZ = "one.cbz";
    static final String ZIP = "two.zip";
    static final String GENERIC = "generic.cbz";
    static final String UNAVAILABLE = "unavailable.zip";
    static final String AUTHORITY = "com.agustin.sumi.test.picker";
    private static final String ROOT = "fixture-root";
    static final String TREE_ROOT = "fixture-tree";
    static final String TREE_CBZ = "opaque-a.cbz";
    static final String TREE_ZIP = "opaque-b.zip";
    static final String OUTSIDE = "fixture-tree-lookalike.cbz";
    static final String SCAN_ROOT = "scan-tree";
    static final String RECOVERY_ROOT = "recovery-tree";
    static final String NODE_ROOT = "node-limit-tree";
    static final String CANDIDATE_ROOT = "candidate-limit-tree";
    static final String NESTED = "opaque-nested.ZIP";
    private static final String SUBFOLDER = "subfolder";
    private static final String HIDDEN = ".hidden-folder";
    private static final String DENIED = "denied-folder";
    private static final String INVALID = "invalid.cbz";
    private static final String DEPTH_PREFIX = "depth-";
    private static final String[] FILES = {CBZ, ZIP, GENERIC, UNAVAILABLE};
    private static final String[] ROOT_COLUMNS = {
        Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_FLAGS,
        Root.COLUMN_TITLE, Root.COLUMN_MIME_TYPES
    };
    private static final String[] DOCUMENT_COLUMNS = {
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED
    };

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        if (TREE_ROOT.equals(parentDocumentId)) return TREE_CBZ.equals(documentId) || TREE_ZIP.equals(documentId);
        if (RECOVERY_ROOT.equals(parentDocumentId)) return TREE_CBZ.equals(documentId) || NESTED.equals(documentId);
        if (SCAN_ROOT.equals(parentDocumentId)) {
            return TREE_CBZ.equals(documentId) || NESTED.equals(documentId) || INVALID.equals(documentId)
                    || SUBFOLDER.equals(documentId) || HIDDEN.equals(documentId) || DENIED.equals(documentId)
                    || documentId.startsWith(DEPTH_PREFIX);
        }
        return false;
    }

    static boolean isFixtureTree(String id) {
        return TREE_ROOT.equals(id) || SCAN_ROOT.equals(id) || RECOVERY_ROOT.equals(id)
                || NODE_ROOT.equals(id) || CANDIDATE_ROOT.equals(id);
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection == null ? ROOT_COLUMNS : projection);
        cursor.newRow()
                .add(Root.COLUMN_ROOT_ID, ROOT)
                .add(Root.COLUMN_DOCUMENT_ID, ROOT)
                .add(Root.COLUMN_TITLE, TITLE)
                .add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY)
                .add(Root.COLUMN_MIME_TYPES, "application/*");
        return cursor;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection);
        addDocument(cursor, documentId);
        return cursor;
    }

    @Override
    public Cursor queryChildDocuments(String parentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection);
        String[] files;
        switch (parentId) {
            case ROOT: files = FILES; break;
            case TREE_ROOT: files = new String[] {TREE_CBZ, TREE_ZIP}; break;
            case SCAN_ROOT:
                files = new String[] {TREE_CBZ, SUBFOLDER, HIDDEN, DENIED, "notes.txt", ".hidden.cbz", "depth-1"};
                break;
            case SUBFOLDER: files = new String[] {NESTED, INVALID, SCAN_ROOT, OUTSIDE}; break;
            case HIDDEN: throw new AssertionError("Hidden folder must not be queried");
            case DENIED: throw new SecurityException("Fixture branch revoked");
            case RECOVERY_ROOT: files = new String[] {TREE_CBZ, NESTED}; break;
            case NODE_ROOT:
                for (int i = 0; i < 9_999; i++) addDocument(cursor, "notes.txt");
                addDocument(cursor, TREE_CBZ);
                addDocument(cursor, TREE_ZIP);
                return cursor;
            case CANDIDATE_ROOT:
                for (int i = 0; i < 1_000; i++) addDocument(cursor, TREE_CBZ);
                addDocument(cursor, TREE_ZIP);
                return cursor;
            default:
                if (!parentId.startsWith(DEPTH_PREFIX)) throw new FileNotFoundException("Unknown fixture directory");
                int depth = Integer.parseInt(parentId.substring(DEPTH_PREFIX.length()));
                files = depth == 10 ? new String[] {TREE_CBZ, "depth-11"} : new String[] {"depth-" + (depth + 1)};
        }
        for (String id : files) addDocument(cursor, id);
        return cursor;
    }

    private void addDocument(MatrixCursor cursor, String id) throws FileNotFoundException {
        String mime;
        switch (id) {
            case ROOT:
            case TREE_ROOT:
            case SCAN_ROOT:
            case SUBFOLDER:
            case HIDDEN:
            case DENIED: mime = Document.MIME_TYPE_DIR; break;
            case CBZ:
            case TREE_CBZ:
            case INVALID:
            case ".hidden.cbz":
            case OUTSIDE: mime = "application/vnd.comicbook+zip"; break;
            case ZIP:
            case TREE_ZIP:
            case UNAVAILABLE: mime = "application/zip"; break;
            case GENERIC:
            case NESTED: mime = "application/octet-stream"; break;
            case "notes.txt": mime = "text/plain"; break;
            default:
                if (!id.startsWith(DEPTH_PREFIX)) throw new FileNotFoundException("Unknown fixture document");
                mime = Document.MIME_TYPE_DIR;
        }
        cursor.newRow()
                .add(Document.COLUMN_DOCUMENT_ID, id)
                .add(Document.COLUMN_DISPLAY_NAME, ROOT.equals(id) ? TITLE : id)
                .add(Document.COLUMN_MIME_TYPE, mime)
                .add(Document.COLUMN_FLAGS, 0);
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        if (!"r".equals(mode)) throw new SecurityException("Expected read-only access");
        int pages;
        switch (documentId) {
            case CBZ:
            case TREE_CBZ:
            case OUTSIDE:
            case GENERIC: pages = 1; break;
            case ZIP:
            case NESTED:
            case TREE_ZIP: pages = 2; break;
            case INVALID: pages = 0; break;
            default: throw new FileNotFoundException("Fixture unavailable");
        }
        File file = new File(Objects.requireNonNull(getContext()).getCacheDir(), documentId);
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            for (int i = 1; i <= pages; i++) {
                zip.putNextEntry(new ZipEntry("page" + i + ".jpg"));
                zip.write(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1});
                zip.closeEntry();
            }
        } catch (IOException error) {
            FileNotFoundException failure = new FileNotFoundException("Cannot write fixture");
            failure.initCause(error);
            throw failure;
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }
}
