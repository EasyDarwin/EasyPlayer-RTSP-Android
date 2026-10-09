package org.easydarwin.easyplayer.util;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

public class FileUtil {

    private static final String TAG = "FileUtil";
    private static final String ROOT_DIR = "EasyPlayerRTSP";

    public static String getPicturePath(Context context, String url) {
        return getMediaDirectory(context, Environment.DIRECTORY_PICTURES, url, "picture").getPath();
    }

    public static File getPictureName(Context context, String url) {
        File file = getMediaDirectory(context, Environment.DIRECTORY_PICTURES, url, "picture");
        File res = new File(file, new SimpleDateFormat("yy_MM_dd HH_mm_ss_SSS").format(new Date()) + ".jpg");
        return res;
    }

    public static String getMoviePath(Context context, String url) {
        return getMediaDirectory(context, Environment.DIRECTORY_MOVIES, url, "movie").getPath();
    }

    public static File getMovieName(Context context, String url) {
        File file = getMediaDirectory(context, Environment.DIRECTORY_MOVIES, url, "movie");
        File res = new File(file, new SimpleDateFormat("yy_MM_dd HH_mm_ss").format(new Date()) + ".mp4");
        return res;
    }

    private static File getMediaDirectory(Context context, String mediaType, String url, String kind) {
        File base = context.getExternalFilesDir(mediaType);
        if (base == null) {
            base = new File(context.getFilesDir(), mediaType);
        }

        File directory = new File(new File(new File(base, ROOT_DIR), urlDir(url)), kind);
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            Log.e(TAG, "cannot create app-private media directory: " + directory);
        }
        return directory;
    }

    private static String urlDir(String url) {
        url = url.replace("://", "");
        url = url.replace("/", "");
        url = url.replace(".", "");

        if (url.length() > 64) {
            url = url.substring(0, 63);
        }

        return url;
    }

    /*
     * 截屏
     * */
    public static File getSnapFile(Context context, String url) {
        File file = getMediaDirectory(context, Environment.DIRECTORY_PICTURES, url, "picture");
        File res = new File(file, "snap.jpg");
        return res;
    }
}
