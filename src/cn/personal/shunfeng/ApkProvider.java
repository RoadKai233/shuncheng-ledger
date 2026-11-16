package cn.personal.shunfeng;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Narrow read-only grant: the single validated installer file, never a ledger or arbitrary cache path. */
public final class ApkProvider extends ContentProvider {
    private static final String AUTHORITY = UpdateClient.APP_PACKAGE + ".updates";
    @Override public void attachInfo(Context context, ProviderInfo info) {
        if (info.exported || !info.grantUriPermissions || !AUTHORITY.equals(info.authority))
            throw new SecurityException("更新文件提供程序必须禁止导出并使用受限 URI 授权");
        super.attachInfo(context, info);
    }
    @Override public boolean onCreate() { return true; }
    private File file(Uri uri) throws FileNotFoundException {
        if (uri == null || !"content".equals(uri.getScheme()) || !AUTHORITY.equals(uri.getAuthority()) ||
                !("/" + UpdateClient.APK_FILENAME).equals(uri.getEncodedPath()) || uri.getQuery() != null || uri.getFragment() != null)
            throw new FileNotFoundException("不允许访问该更新文件");
        try {
            File apk = UpdateClient.verifiedFile(getContext());
            if (!apk.isFile()) throw new FileNotFoundException("更新安装包不存在");
            return apk;
        } catch (IOException e) {
            FileNotFoundException failure = new FileNotFoundException("无法读取更新安装包"); failure.initCause(e); throw failure;
        }
    }
    @Override public String getType(Uri uri) {
        try { file(uri); return UpdateClient.APK_MIME; }
        catch (FileNotFoundException e) { throw new IllegalArgumentException(e.getMessage(), e); }
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            File apk = file(uri);
            String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            if (columns.length > 16) throw new IllegalArgumentException("无效字段列表");
            MatrixCursor cursor = new MatrixCursor(columns, 1); Object[] values = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = UpdateClient.APK_FILENAME;
                else if (OpenableColumns.SIZE.equals(columns[i])) values[i] = apk.length();
            }
            cursor.addRow(values); return cursor;
        } catch (FileNotFoundException e) { throw new IllegalArgumentException(e.getMessage(), e); }
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new SecurityException("更新安装包仅允许读取");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new SecurityException("更新安装包仅允许读取"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new SecurityException("更新安装包仅允许读取"); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new SecurityException("更新安装包仅允许读取"); }
}
