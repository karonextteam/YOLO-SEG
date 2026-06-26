package com.mavinci.yoloseg.ui;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.mavinci.yoloseg.ModelManager;
import com.mavinci.yoloseg.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Main hub — model selection, GPU toggle, threshold sliders, mode selection.
 */
public class MainActivity extends AppCompatActivity {

    // ── Prefs keys ────────────────────────────────────────────────────────
    private static final String PREFS         = "yoloseg_prefs";
    private static final String KEY_MODEL     = "model_index";
    private static final String KEY_VULKAN    = "use_vulkan";
    private static final String KEY_CONF      = "conf_thresh";
    private static final String KEY_NMS       = "nms_thresh";
    private static final String KEY_ALPHA     = "mask_alpha";

    // ── UI ────────────────────────────────────────────────────────────────
    private Spinner    spinnerModel;
    private Switch     switchVulkan;
    private SeekBar    seekConf, seekNms, seekAlpha;
    private TextView   tvConf, tvNms, tvAlpha;
    private RadioGroup radioGroup;
    private Button     btnGo;

    // ── State ─────────────────────────────────────────────────────────────
    private SharedPreferences prefs;

    // ── Permission launcher ───────────────────────────────────────────────
    private final ActivityResultLauncher<String[]> permLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
            result -> {
                boolean allGranted = true;
                for (Boolean v : result.values()) if (!v) { allGranted = false; break; }
                if (allGranted) launchSelectedMode();
                else Toast.makeText(this, "İzinler gerekli!", Toast.LENGTH_SHORT).show();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        bindViews();
        setupModelSpinner();
        setupSliders();
        setupGoButton();
    }

    private void bindViews() {
        spinnerModel = findViewById(R.id.spinner_model);
        switchVulkan = findViewById(R.id.switch_vulkan);
        seekConf     = findViewById(R.id.seek_conf);
        seekNms      = findViewById(R.id.seek_nms);
        seekAlpha    = findViewById(R.id.seek_alpha);
        tvConf       = findViewById(R.id.tv_conf_val);
        tvNms        = findViewById(R.id.tv_nms_val);
        tvAlpha      = findViewById(R.id.tv_alpha_val);
        radioGroup   = findViewById(R.id.radio_group_mode);
        btnGo        = findViewById(R.id.btn_go);

        switchVulkan.setChecked(prefs.getBoolean(KEY_VULKAN, false));
        switchVulkan.setOnCheckedChangeListener((btn, checked) ->
            prefs.edit().putBoolean(KEY_VULKAN, checked).apply());
    }

    private void setupModelSpinner() {
        List<String> modelNames = ModelManager.getAvailableModelNames(getAssets());
        if (modelNames.isEmpty()) {
            modelNames.add("yolo11n-seg (not found in assets)");
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
            this, android.R.layout.simple_spinner_item, modelNames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerModel.setAdapter(adapter);
        int saved = prefs.getInt(KEY_MODEL, 0);
        spinnerModel.setSelection(Math.min(saved, modelNames.size() - 1));
        spinnerModel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                prefs.edit().putInt(KEY_MODEL, pos).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
    }

    private void setupSliders() {
        // Conf 0–100 → 0.00–1.00
        int savedConf  = prefs.getInt(KEY_CONF, 25);
        int savedNms   = prefs.getInt(KEY_NMS,  45);
        int savedAlpha = prefs.getInt(KEY_ALPHA, 50);

        seekConf.setProgress(savedConf);
        seekNms.setProgress(savedNms);
        seekAlpha.setProgress(savedAlpha);

        updateSliderLabel(tvConf,   savedConf,  "Conf");
        updateSliderLabel(tvNms,    savedNms,   "NMS");
        updateSliderLabel(tvAlpha,  savedAlpha, "Alpha");

        seekConf.setOnSeekBarChangeListener(makeListener(KEY_CONF, tvConf, "Conf"));
        seekNms.setOnSeekBarChangeListener(makeListener(KEY_NMS,   tvNms,  "NMS"));
        seekAlpha.setOnSeekBarChangeListener(makeListener(KEY_ALPHA, tvAlpha, "Alpha"));
    }

    private SeekBar.OnSeekBarChangeListener makeListener(String key, TextView tv, String label) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int prog, boolean fromUser) {
                updateSliderLabel(tv, prog, label);
                prefs.edit().putInt(key, prog).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        };
    }

    private void updateSliderLabel(TextView tv, int prog, String label) {
        tv.setText(String.format("%s: %.2f", label, prog / 100.0f));
    }

    private void setupGoButton() {
        btnGo.setOnClickListener(v -> requestPermissionsAndGo());
    }

    private void requestPermissionsAndGo() {
        List<String> needed = new ArrayList<>();
        int checked = radioGroup.getCheckedRadioButtonId();

        // Camera permission always needed for camera mode
        if (checked == R.id.rb_camera) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.CAMERA);
            }
        } else {
            // Media read permissions for gallery / video
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                    != PackageManager.PERMISSION_GRANTED) {
                    needed.add(Manifest.permission.READ_MEDIA_IMAGES);
                }
                if (checked == R.id.rb_video &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                    != PackageManager.PERMISSION_GRANTED) {
                    needed.add(Manifest.permission.READ_MEDIA_VIDEO);
                }
            } else {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                    needed.add(Manifest.permission.READ_EXTERNAL_STORAGE);
                }
            }
        }

        if (needed.isEmpty()) launchSelectedMode();
        else permLauncher.launch(needed.toArray(new String[0]));
    }

    private void launchSelectedMode() {
        int checked = radioGroup.getCheckedRadioButtonId();
        Intent intent;

        if (checked == R.id.rb_image) {
            intent = new Intent(this, ImageActivity.class);
        } else if (checked == R.id.rb_video) {
            intent = new Intent(this, VideoActivity.class);
        } else {
            intent = new Intent(this, CameraActivity.class);
        }

        // Pass settings to the next activity
        int modelIdx = spinnerModel.getSelectedItemPosition();
        intent.putExtra("model_index",  modelIdx);
        intent.putExtra("use_vulkan",   switchVulkan.isChecked());
        intent.putExtra("conf_thresh",  seekConf.getProgress() / 100.0f);
        intent.putExtra("nms_thresh",   seekNms.getProgress()  / 100.0f);
        intent.putExtra("mask_alpha",   seekAlpha.getProgress() / 100.0f);

        startActivity(intent);
    }
}
