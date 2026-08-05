package com.thorium.launchbridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Read-only, per-launch access to ROMs under the internal Games directory. */
public final class RomFileProvider extends ContentProvider {
    private static final File GAMES_ROOT = new File("/storage/emulated/0/Games");

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only provider");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/octet-stream";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File file;
        try {
            file = resolve(uri);
        } catch (FileNotFoundException error) {
            return new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        }
        String[] columns = projection == null
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                : projection;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            if (OpenableColumns.DISPLAY_NAME.equals(column)) {
                String uriName = uri.getLastPathSegment();
                row.add(uriName == null ? file.getName() : uriName);
            }
            else if (OpenableColumns.SIZE.equals(column)) row.add(file.length());
            else row.add(null);
        }
        return cursor;
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        String path = uri.getQueryParameter("path");
        if (path == null) throw new FileNotFoundException("Missing path");
        try {
            File root = GAMES_ROOT.getCanonicalFile();
            File file = new File(path).getCanonicalFile();
            if (!file.getPath().startsWith(root.getPath() + File.separator) || !file.isFile())
                throw new FileNotFoundException("Outside Games or not a file");
            return file;
        } catch (IOException error) {
            throw new FileNotFoundException(error.getMessage());
        }
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
