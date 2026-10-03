package com.documentscanner.scanner.export;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.core.content.FileProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** 通过 FileProvider 把产物文件交给其它应用，避免暴露 file:// 路径。 */
public final class ShareFiles {

    /**
     * provider 的 authority 后缀。完整值是「宿主包名 + 这一串」：模块在
     * {@code scanner/src/main/AndroidManifest.xml} 里用 {@code ${applicationId}} 占位符声明
     * provider，合并进宿主时才展开，所以运行期也只能按宿主包名算。
     * 两处必须成对改，对不上就是运行期 "Failed to find provider"。
     */
    private static final String AUTHORITY_SUFFIX = ".scanner.fileprovider";

    /** 当前宿主下 provider 的 authority。 */
    public static String authority(Context context) {
        return context.getPackageName() + AUTHORITY_SUFFIX;
    }

    private ShareFiles() {
    }

    public static Uri uriForFile(Context context, File file) {
        return FileProvider.getUriForFile(context, authority(context), file);
    }

    public static Intent single(Context context, File file, String mime, String subject) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType(mime);
        intent.putExtra(Intent.EXTRA_STREAM, uriForFile(context, file));
        intent.putExtra(Intent.EXTRA_SUBJECT, subject);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return intent;
    }

    public static Intent multiple(Context context, List<File> files, String mime, String subject) {
        ArrayList<Uri> uris = new ArrayList<>(files.size());
        for (File file : files) {
            if (file.exists()) uris.add(uriForFile(context, file));
        }
        Intent intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
        intent.setType(mime);
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        intent.putExtra(Intent.EXTRA_SUBJECT, subject);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return intent;
    }
}
