package com.mavinci.yoloseg.util;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Utility for saving Bitmaps to the public Pictures/YoloSeg directory.
 * Uses MediaStore on API 29+ (scoped storage), direct file I/O on older APIs.
 */
public class ImageSaver {

    private static final String TAG    = "ImageSaver";
    private static final String SUBDIR = "YoloSeg";

    /**
     * Save bitmap as JPEG to Pictures/YoloSeg/<filename>.jpg.
     *
     * @param context  Application context
     * @param bitmap   Bitmap to save (ARGB_8888)
     * @param filename Base filename without extension
     * @return Absolute path of saved file, or null on failure
     */
    public static String saveBitmap(Context context, Bitmap bitmap, String filename) {
        String fullName = filename + ".jpg";
        OutputStream os = null;
        String savedPath = null;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Scoped storage (API 29+)
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, fullName);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + File.separator + SUBDIR);
                values.put(MediaStore.Images.Media.IS_PENDING, 1);

                Uri uri = context.getContentResolver()
                    .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) return null;

                os = context.getContentResolver().openOutputStream(uri);
                if (os != null) bitmap.compress(Bitmap.CompressFormat.JPEG, 95, os);

                values.clear();
                values.put(MediaStore.Images.Media.IS_PENDING, 0);
                context.getContentResolver().update(uri, values, null, null);
                savedPath = uri.toString();

            } else {
                // Legacy (API < 29)
                File dir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    SUBDIR);
                if (!dir.exists()) dir.mkdirs();

                File file = new File(dir, fullName);
                os = new FileOutputStream(file);
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, os);
                savedPath = file.getAbsolutePath();
            }
        } catch (IOException e) {
            Log.e(TAG, "Save failed: " + e.getMessage());
        } finally {
            if (os != null) {
                try { os.close(); } catch (IOException ignored) {}
            }
        }
        return savedPath;
    }
}
