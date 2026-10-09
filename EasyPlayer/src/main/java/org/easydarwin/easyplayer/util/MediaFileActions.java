package org.easydarwin.easyplayer.util;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.support.v4.content.FileProvider;
import android.util.Log;
import android.widget.Toast;

import org.easydarwin.easyplayer.R;

import java.io.File;

/** Grants a receiving app temporary access to a private media file. */
public final class MediaFileActions {
    private MediaFileActions() {}

    public static Intent createIntent(Context context, File file, boolean share) {
        if (!file.isFile() || file.length() == 0) {
            throw new IllegalArgumentException("Missing or empty media file: " + file);
        }
        Uri uri = getContentUri(context, file);
        String type = file.getName().endsWith(".mp4") ? "video/mp4" : "image/jpeg";
        Intent intent = new Intent(share ? Intent.ACTION_SEND : Intent.ACTION_VIEW);
        if (share) {
            intent.setType(type);
            intent.putExtra(Intent.EXTRA_STREAM, uri);
        } else {
            intent.setDataAndType(uri, type);
        }
        intent.setClipData(ClipData.newUri(context.getContentResolver(), file.getName(), uri));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return intent;
    }

    public static Uri getContentUri(Context context, File file) {
        if (!file.isFile() || file.length() == 0) {
            throw new IllegalArgumentException("Missing or empty media file: " + file);
        }
        return FileProvider.getUriForFile(context,
                context.getString(R.string.org_easydarwin_update_authorities), file);
    }

    public static void launch(Context context, File file, boolean share) {
        try {
            context.startActivity(Intent.createChooser(createIntent(context, file, share),
                    share ? "分享文件" : "选择应用打开"));
        } catch (ActivityNotFoundException | IllegalArgumentException e) {
            Log.e("MediaFileActions", "Cannot open media file: " + file, e);
            Toast.makeText(context, "无法打开文件或没有可用的应用", Toast.LENGTH_LONG).show();
        }
    }
}
