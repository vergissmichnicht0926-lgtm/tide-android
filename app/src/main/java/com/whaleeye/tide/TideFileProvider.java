package com.whaleeye.tide;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

/**
 * 只读文件出口：只暴露 cacheDir/shared，供系统查看器通过 content:// 读取。
 * query 里的 name/mime 只用于展示和 MIME，永远不参与路径解析。
 */
public final class TideFileProvider extends ContentProvider {
    public static final String AUTHORITY = BuildConfig.APPLICATION_ID + ".files";
    private static final String PATH_SHARED = "shared";

    public static File sharedDir(Context context) {
        File dir = new File(context.getCacheDir(), PATH_SHARED);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static Uri uriFor(Context context, File file, String displayName, String mimeType)
            throws IOException {
        File root = sharedDir(context);
        if (!file.isFile() || !FileSafety.isWithin(root, file)) {
            throw new IOException("下载文件不在共享缓存目录");
        }
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath(PATH_SHARED)
                .appendPath(file.getName())
                .appendQueryParameter("name", FileSafety.sanitizeFileName(displayName))
                .appendQueryParameter("mime", FileSafety.normalizeMimeType(mimeType))
                .build();
    }

    @Override
    public boolean onCreate() {
        Context context = getContext();
        return context != null && sharedDir(context).isDirectory();
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File file = resolve(uri);
        String name = uri.getQueryParameter("name");
        if (name == null || name.isEmpty()) name = file.getName();
        name = FileSafety.sanitizeFileName(name);
        String[] columns = projection == null
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                : projection;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = name;
            else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = file.length();
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        try {
            resolve(uri);
        } catch (IllegalArgumentException e) {
            return FileSafety.DEFAULT_MIME_TYPE;
        }
        return FileSafety.normalizeMimeType(uri.getQueryParameter("mime"));
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode == null || !mode.equals("r")) {
            throw new FileNotFoundException("只允许只读打开");
        }
        File file = resolve(uri);
        if (!file.isFile()) throw new FileNotFoundException("文件不存在");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("TideFileProvider is read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("TideFileProvider is read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("TideFileProvider is read-only");
    }

    private File resolve(Uri uri) {
        if (uri == null || !AUTHORITY.equals(uri.getAuthority())) {
            throw new IllegalArgumentException("未知文件 URI");
        }
        List<String> segments = uri.getPathSegments();
        if (segments.size() != 2 || !PATH_SHARED.equals(segments.get(0))) {
            throw new IllegalArgumentException("文件 URI 路径不合法");
        }
        String name = segments.get(1);
        if (name.isEmpty() || !name.equals(FileSafety.sanitizeFileName(name))) {
            throw new IllegalArgumentException("文件 URI 名称不合法");
        }
        try {
            File root = sharedDir(getContext());
            File file = new File(root, name).getCanonicalFile();
            if (!FileSafety.isWithin(root, file)) {
                throw new IllegalArgumentException("文件 URI 越界");
            }
            return file;
        } catch (IOException e) {
            throw new IllegalArgumentException("文件 URI 越界", e);
        }
    }
}
