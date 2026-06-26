package com.mavinci.yoloseg;

import android.content.res.AssetManager;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * ModelManager — scans the app's assets/ folder for NCNN model pairs.
 *
 * Convention: model files must be named as pairs:
 *   <name>.ncnn.param
 *   <name>.ncnn.bin
 *
 * E.g.:
 *   yolo11n-seg.ncnn.param  /  yolo11n-seg.ncnn.bin
 *   yolo11s-seg.ncnn.param  /  yolo11s-seg.ncnn.bin
 *   yolo26n-seg.ncnn.param  /  yolo26n-seg.ncnn.bin
 */
public class ModelManager {

    private static final String TAG    = "ModelManager";
    private static final String SUFFIX = ".ncnn.param";

    /**
     * Returns display names of all detected model pairs in assets.
     */
    public static List<String> getAvailableModelNames(AssetManager mgr) {
        List<String> names = new ArrayList<>();
        try {
            String[] files = mgr.list("");
            if (files == null) return names;
            for (String f : files) {
                if (f.endsWith(SUFFIX)) {
                    // Derive display name: strip .ncnn.param
                    String baseName = f.replace(SUFFIX, "");
                    // Verify matching .bin exists
                    String binFile = baseName + ".ncnn.bin";
                    for (String g : files) {
                        if (g.equals(binFile)) {
                            names.add(baseName);
                            break;
                        }
                    }
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to list assets: " + e.getMessage());
        }
        // Fallback so spinner always has an entry
        if (names.isEmpty()) {
            names.add("yolo11n-seg");
        }
        return names;
    }

    /**
     * Returns [paramPath, binPath] for the model at the given index,
     * or null if index is out of range or files are missing.
     */
    public static String[] getModelPaths(AssetManager mgr, int index) {
        List<String> names = getAvailableModelNames(mgr);
        if (index < 0 || index >= names.size()) {
            Log.e(TAG, "Invalid model index: " + index);
            return null;
        }
        String base  = names.get(index);
        String param = base + ".ncnn.param";
        String bin   = base + ".ncnn.bin";
        // Verify files exist
        try {
            mgr.open(param).close();
            mgr.open(bin).close();
        } catch (IOException e) {
            Log.e(TAG, "Model files not found: " + param + " / " + bin);
            return null;
        }
        return new String[]{ param, bin };
    }
}
