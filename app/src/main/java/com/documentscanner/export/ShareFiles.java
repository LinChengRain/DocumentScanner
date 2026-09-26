package com.documentscanner.export;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.core.content.FileProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** 通过 FileProvider 把产物文件交给其它应用，避免暴露 file:// 路径。 */
public final class ShareFiles {

    private ShareFiles() {
    }

    public static Uri uriForFile(Context context, File file) {
        return FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
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
