package com.agustin.sumi.data.local.document;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

// This provider runs in the separate test APK process using only Android and Java APIs.
public final class SafTestProvider extends ContentProvider {
    static final String AUTHORITY = "com.agustin.sumi.test.documents";
    static final byte[] BYTES = {1, 2, 3, 4};
    private static final byte[] CBZ = createCbz();
    private final ConcurrentHashMap<String, AtomicInteger> counts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PipeGate> gates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, byte[]> covers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> coverRevisions = new ConcurrentHashMap<>();
    private volatile boolean regularMissing;
    private volatile boolean regularTemporarilyUnavailable;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        String caller = getCallingPackage();
        if (!"com.agustin.sumi".equals(caller)) {
            throw new SecurityException("Unexpected fixture caller");
        }
        if ("picker-grant".equals(method) || "picker-revoke".equals(method) || "picker-transient".equals(method)) {
            return pickerPermission(caller, method, arg);
        }
        if (!Arrays.asList("regular", "pipe", "missing", "transient",
                "cbz-regular", "cbz-pipe", "cbz-corrupt", "cbz-gated", "cbz-blocked", "cover-direct", "cover-pipe").contains(arg)) {
            throw new IllegalArgumentException("Unknown fixture");
        }
        Uri uri = Uri.parse("content://" + AUTHORITY + "/" + arg);
        int read = Intent.FLAG_GRANT_READ_URI_PERMISSION;
        Context context = Objects.requireNonNull(getContext());
        switch (method) {
            case "set-cover":
                covers.put(arg, Objects.requireNonNull(Objects.requireNonNull(extras).getByteArray("archive")));
                coverRevisions.computeIfAbsent(arg, ignored -> new AtomicInteger()).incrementAndGet();
                break;
            case "persistable":
                context.grantUriPermission(caller, uri, read | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                break;
            case "transient":
                context.grantUriPermission(caller, uri, read);
                break;
            case "revoke":
                context.revokeUriPermission(uri, read);
                break;
            case "reset":
                counts.remove(arg);
                gates.put(arg, new PipeGate());
                regularMissing = false;
                regularTemporarilyUnavailable = false;
                break;
            case "count":
                Bundle count = new Bundle();
                AtomicInteger opens = counts.get(arg);
                count.putInt("count", opens == null ? 0 : opens.get());
                return count;
            case "release":
                Objects.requireNonNull(gates.get(arg)).release.countDown();
                break;
            case "closed":
                Bundle status = new Bundle();
                status.putBoolean("closed", Objects.requireNonNull(gates.get(arg)).readerClosed);
                return status;
            case "missing":
                regularMissing = true;
                break;
            case "restore":
                regularMissing = false;
                regularTemporarilyUnavailable = false;
                break;
            case "temporary-failure":
                regularTemporarilyUnavailable = true;
                break;
            default:
                throw new IllegalArgumentException("Unknown fixture command");
        }
        return Bundle.EMPTY;
    }

    private Bundle pickerPermission(String caller, String method, String id) {
        boolean tree = PickerFixtureProvider.isFixtureTree(id);
        if (!tree && !Arrays.asList(PickerFixtureProvider.TREE_CBZ, PickerFixtureProvider.TREE_ZIP,
                PickerFixtureProvider.OUTSIDE, PickerFixtureProvider.READER_CBZ).contains(id)) {
            throw new IllegalArgumentException("Unknown picker fixture");
        }
        Uri uri = tree
                ? DocumentsContract.buildTreeDocumentUri(PickerFixtureProvider.AUTHORITY, id)
                : DocumentsContract.buildDocumentUri(PickerFixtureProvider.AUTHORITY, id);
        Context context = Objects.requireNonNull(getContext());
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
        if ("picker-revoke".equals(method)) {
            context.revokeUriPermission(uri, flags);
        } else {
            if (tree) flags |= Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
            if ("picker-grant".equals(method)) flags |= Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
            context.grantUriPermission(caller, uri, flags);
        }
        return Bundle.EMPTY;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new SecurityException("Expected read-only access");
        }
        try {
            String name = uri.getLastPathSegment();
            if ("cover-direct".equals(name) || "cover-pipe".equals(name)) {
                byte[] bytes = covers.get(name);
                if (bytes == null) throw new FileNotFoundException("Missing cover fixture");
                if ("cover-direct".equals(name)) return openFixture(name, bytes);
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
                new Thread(() -> {
                    try (ParcelFileDescriptor.AutoCloseOutputStream output =
                            new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                        output.write(bytes);
                    } catch (IOException ignored) {
                        // Cancellation may close the reader before the fixture finishes.
                    }
                }).start();
                return pipe[0];
            }
            if (name != null && name.startsWith("cbz-")) {
                int opened = counts.computeIfAbsent(name, ignored -> new AtomicInteger()).incrementAndGet();
                if ("cbz-regular".equals(name) && regularMissing) {
                    throw new FileNotFoundException("Missing CBZ fixture");
                }
                if ("cbz-regular".equals(name) && regularTemporarilyUnavailable) {
                    return null;
                }
                if ("cbz-regular".equals(name) || "cbz-corrupt".equals(name)) {
                    return openFixture(name, "cbz-regular".equals(name) ? CBZ : BYTES);
                }
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
                PipeGate gate = opened == 1 && ("cbz-gated".equals(name) || "cbz-blocked".equals(name))
                        ? gates.get(name) : null;
                new Thread(() -> writeCbzPipe(pipe[1], gate, "cbz-blocked".equals(name))).start();
                return pipe[0];
            }
            if ("regular".equals(name)) {
                return openFixture("saf-fixture", BYTES);
            }
            if ("pipe".equals(name)) {
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
                new Thread(() -> writePipe(pipe[1])).start();
                return pipe[0];
            }
        } catch (IOException error) {
            FileNotFoundException failure = new FileNotFoundException("Cannot open fixture");
            failure.initCause(error);
            throw failure;
        }
        throw new FileNotFoundException("Missing fixture");
    }

    private ParcelFileDescriptor openFixture(String name, byte[] bytes) throws IOException {
        File file = new File(Objects.requireNonNull(getContext()).getCacheDir(), name);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private void writeCbzPipe(ParcelFileDescriptor writer, PipeGate gate, boolean watchForClose) {
        try (ParcelFileDescriptor.AutoCloseOutputStream output =
                new ParcelFileDescriptor.AutoCloseOutputStream(writer)) {
            if (gate == null) {
                output.write(CBZ);
                return;
            }
            output.write(CBZ, 0, 12);
            if (!gate.release.await(15, TimeUnit.SECONDS)) return;
            if (!watchForClose) {
                output.write(CBZ, 12, CBZ.length - 12);
                return;
            }
            output.write(CBZ[12]);
            StructPollfd descriptor = new StructPollfd();
            descriptor.fd = writer.getFileDescriptor();
            Os.poll(new StructPollfd[] {descriptor}, 10000);
            gate.readerClosed = (descriptor.revents & OsConstants.POLLERR) != 0;
        } catch (Exception error) {
            if (gate != null && watchForClose && error instanceof IOException) {
                gate.readerClosed = error.getCause() instanceof android.system.ErrnoException
                        && ((android.system.ErrnoException) error.getCause()).errno == OsConstants.EPIPE;
            }
        }
    }

    private static byte[] createCbz() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (String path : Arrays.asList("b/page1.jpg", "a/page1.jpg")) {
                zip.putNextEntry(new ZipEntry(path));
                zip.write(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1});
                zip.closeEntry();
            }
        } catch (IOException error) {
            throw new IllegalStateException("Cannot build CBZ fixture", error);
        }
        return bytes.toByteArray();
    }

    private static final class PipeGate {
        final CountDownLatch release = new CountDownLatch(1);
        volatile boolean readerClosed;
    }

    private void writePipe(ParcelFileDescriptor writer) {
        try (ParcelFileDescriptor.AutoCloseOutputStream output =
                new ParcelFileDescriptor.AutoCloseOutputStream(writer)) {
            output.write(BYTES);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot write fixture", error);
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sortOrder) {
        String name = uri.getLastPathSegment();
        if (name == null || projection == null) return null;
        byte[] archive = covers.get(name);
        AtomicInteger revision = coverRevisions.get(name);
        if (archive == null || revision == null) return null;
        MatrixCursor cursor = new MatrixCursor(projection);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : projection) {
            if (DocumentsContract.Document.COLUMN_SIZE.equals(column)) {
                row.add((long) archive.length);
            } else if (DocumentsContract.Document.COLUMN_LAST_MODIFIED.equals(column)) {
                row.add((long) revision.get());
            } else {
                row.add(null);
            }
        }
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "application/octet-stream";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
}
