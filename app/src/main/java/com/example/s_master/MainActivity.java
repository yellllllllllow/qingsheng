package com.example.s_master;

import android.Manifest;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Base64;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "S_masterPrefs";
    private static final String KEY_API_URL = "api_url";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_MODEL = "model";
    private static final String KEY_CUSTOM_PROMPT = "custom_prompt";
    private static final String KEY_SILENT_MODE = "silent_mode";
    private static final String KEY_AUTO_START = "auto_start";
    private static final String KEY_MEDIA_PROJECTION_RESULT_CODE = "mp_result_code";
    private static final String KEY_MEDIA_PROJECTION_DATA = "mp_result_data";
    private static final String KEY_FLOATING_X = "floating_x";
    private static final String KEY_FLOATING_Y = "floating_y";

    private static final int REQUEST_OVERLAY_PERMISSION = 101;
    private static final int REQUEST_MEDIA_PROJECTION = 102;
    private static final int REQUEST_NOTIFICATION_PERMISSION = 103;

    private SharedPreferences prefs;
    private TextView statusText;
    private SwitchMaterial silentSwitch;
    private TextView promptPreview;

    private MediaProjectionManager mediaProjectionManager;

    public static String currentApiUrl;
    public static String currentApiKey;
    public static String currentModel;
    public static String currentPrompt;
    public static boolean isSilentMode;
    public static boolean isServiceRunning;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        mediaProjectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        initViews();
        loadSettings();
        updateServiceStatus();
        checkPermissions();

        handleCopyAction();
    }

    private void initViews() {
        statusText = findViewById(R.id.statusText);
        silentSwitch = findViewById(R.id.silentSwitch);
        promptPreview = findViewById(R.id.promptPreview);

        MaterialButton startBtn = findViewById(R.id.startBtn);
        MaterialButton stopBtn = findViewById(R.id.stopBtn);
        MaterialButton promptBtn = findViewById(R.id.promptBtn);
        MaterialButton moreBtn = findViewById(R.id.moreBtn);

        startBtn.setOnClickListener(v -> startFloatingService());
        stopBtn.setOnClickListener(v -> stopFloatingService());
        promptBtn.setOnClickListener(v -> showPromptDialog());
        moreBtn.setOnClickListener(v -> showMoreOptions());

        silentSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_SILENT_MODE, isChecked).apply();
            isSilentMode = isChecked;
            updateServiceStatus();
        });
    }

    private void showMoreOptions() {
        String[] options = {"API 设置", "清除授权缓存", "重置所有设置", "关于"};
        new AlertDialog.Builder(this)
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            showSettingsDialog();
                            break;
                        case 1:
                            clearMediaProjectionCache();
                            break;
                        case 2:
                            resetAllSettings();
                            break;
                        case 3:
                            showAboutDialog();
                            break;
                    }
                })
                .show();
    }

    private void loadSettings() {
        currentApiUrl = prefs.getString(KEY_API_URL, "");
        currentApiKey = prefs.getString(KEY_API_KEY, "");
        currentModel = prefs.getString(KEY_MODEL, "");
        currentPrompt = prefs.getString(KEY_CUSTOM_PROMPT, getDefaultPrompt());
        isSilentMode = prefs.getBoolean(KEY_SILENT_MODE, false);

        silentSwitch.setChecked(isSilentMode);

        String promptDisplay = currentPrompt.length() > 100
            ? currentPrompt.substring(0, 100) + "..."
            : currentPrompt;
        promptPreview.setText("当前 Prompt: " + promptDisplay);
    }

    private String getDefaultPrompt() {
        return "你是一个智能助手，可以分析截图中遇到的问题并给出建议。请仔细观察截图中\n" +
               "的内容，理解用户的需求或问题，并给出清晰、实用的回答。\n\n" +
               "分析要点：\n" +
               "1. 识别截图中显示的内容类型（界面、聊天、文档、错误等）\n" +
               "2. 理解用户的具体问题或需求\n" +
               "3. 提供针对性的解决方案或建议\n" +
               "4. 回答要简洁明了，便于理解";
    }

    private void checkPermissions() {
        if (!Settings.canDrawOverlays(this)) {
            showPermissionDialog("悬浮窗权限", "需要悬浮窗权限来显示快捷截屏按钮");
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATION_PERMISSION);
        }
    }

    private void showPermissionDialog(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("去授权", (dialog, which) -> {
                    if (title.contains("悬浮窗")) {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showSettingsDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_settings, null);

        TextInputEditText apiUrlInput = dialogView.findViewById(R.id.apiUrlInput);
        TextInputEditText apiKeyInput = dialogView.findViewById(R.id.apiKeyInput);
        TextInputEditText modelInput = dialogView.findViewById(R.id.modelInput);

        apiUrlInput.setText(prefs.getString(KEY_API_URL, ""));
        apiKeyInput.setText(prefs.getString(KEY_API_KEY, ""));
        modelInput.setText(prefs.getString(KEY_MODEL, "gpt-4o-mini"));

        new AlertDialog.Builder(this)
                .setTitle("API 设置")
                .setView(dialogView)
                .setPositiveButton("保存", (dialog, which) -> {
                    String url = apiUrlInput.getText().toString().trim();
                    String key = apiKeyInput.getText().toString().trim();
                    String model = modelInput.getText().toString().trim();

                    prefs.edit()
                            .putString(KEY_API_URL, url)
                            .putString(KEY_API_KEY, key)
                            .putString(KEY_MODEL, model.isEmpty() ? "gpt-4o-mini" : model)
                            .apply();

                    currentApiUrl = url;
                    currentApiKey = key;
                    currentModel = model.isEmpty() ? "gpt-4o-mini" : model;

                    Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showPromptDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_prompt, null);

        TextInputEditText promptInput = dialogView.findViewById(R.id.promptInput);
        Button resetBtn = dialogView.findViewById(R.id.resetPromptBtn);

        promptInput.setText(currentPrompt);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("自定义 Prompt")
                .setView(dialogView)
                .setPositiveButton("保存", (d, which) -> {
                    String newPrompt = promptInput.getText().toString().trim();
                    if (!newPrompt.isEmpty()) {
                        prefs.edit().putString(KEY_CUSTOM_PROMPT, newPrompt).apply();
                        currentPrompt = newPrompt;
                        String display = newPrompt.length() > 100
                            ? newPrompt.substring(0, 100) + "..."
                            : newPrompt;
                        promptPreview.setText("当前 Prompt: " + display);
                        Toast.makeText(this, "Prompt 已更新", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .create();

        resetBtn.setOnClickListener(v -> {
            promptInput.setText(getDefaultPrompt());
        });

        dialog.show();
    }

    private void clearMediaProjectionCache() {
        new AlertDialog.Builder(this)
                .setTitle("清除授权缓存")
                .setMessage("确定要清除屏幕录制授权缓存吗？下次启动服务时将重新请求授权。")
                .setPositiveButton("确定", (dialog, which) -> {
                    prefs.edit()
                            .remove(KEY_MEDIA_PROJECTION_RESULT_CODE)
                            .remove(KEY_MEDIA_PROJECTION_DATA)
                            .apply();
                    Toast.makeText(this, "授权缓存已清除", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void resetAllSettings() {
        new AlertDialog.Builder(this)
                .setTitle("重置所有设置")
                .setMessage("确定要重置所有设置吗？这将清除 API 配置、授权缓存和悬浮球位置。")
                .setPositiveButton("确定", (dialog, which) -> {
                    List<String> keysToRemove = Arrays.asList(
                        KEY_API_URL, KEY_API_KEY, KEY_MODEL, KEY_CUSTOM_PROMPT,
                        KEY_SILENT_MODE, KEY_MEDIA_PROJECTION_RESULT_CODE,
                        KEY_MEDIA_PROJECTION_DATA, KEY_FLOATING_X, KEY_FLOATING_Y
                    );
                    
                    SharedPreferences.Editor editor = prefs.edit();
                    for (String key : keysToRemove) {
                        editor.remove(key);
                    }
                    editor.apply();

                    loadSettings();
                    updateServiceStatus();
                    Toast.makeText(this, "所有设置已重置", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showAboutDialog() {
        new AlertDialog.Builder(this)
                .setTitle("关于 S-master AI")
                .setMessage("S-master AI 是一款智能截图分析助手，可以快速截取屏幕并通过 AI 分析问题。\n\n" +
                           "主要功能：\n" +
                           "• 悬浮球快速截屏\n" +
                           "• AI 图像分析\n" +
                           "• 自定义 Prompt\n" +
                           "• 静默模式（结果自动复制）\n\n" +
                           "版本: 1.0.0")
                .setPositiveButton("确定", null)
                .show();
    }

    private void handleCopyAction() {
        Intent intent = getIntent();
        if (intent != null && "COPY".equals(intent.getAction())) {
            String result = intent.getStringExtra("result");
            if (result != null && !result.isEmpty()) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                android.content.ClipData clip = android.content.ClipData.newPlainText("AI 分析结果", result);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(this, "结果已复制到剪贴板", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startFloatingService() {
        if (currentApiUrl.isEmpty() || currentApiKey.isEmpty()) {
            Toast.makeText(this, "请先在设置中配置 API", Toast.LENGTH_SHORT).show();
            showSettingsDialog();
            return;
        }

        if (!Settings.canDrawOverlays(this)) {
            showPermissionDialog("悬浮窗权限", "需要悬浮窗权限来显示快捷截屏按钮");
            return;
        }

        startMediaProjection();
    }

    private void startMediaProjection() {
        try {
            Intent savedData = getSavedMediaProjectionData();
            if (savedData != null) {
                int resultCode = prefs.getInt(KEY_MEDIA_PROJECTION_RESULT_CODE, -1);
                if (resultCode != -1) {
                    startServiceWithMediaProjection(resultCode, savedData);
                    return;
                }
            }
            startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION);
        } catch (Exception e) {
            Toast.makeText(this, "无法启动屏幕截图: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private Intent getSavedMediaProjectionData() {
        try {
            String dataStr = prefs.getString(KEY_MEDIA_PROJECTION_DATA, null);
            if (dataStr != null) {
                byte[] dataBytes = Base64.decode(dataStr, Base64.DEFAULT);
                ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(dataBytes));
                return (Intent) ois.readObject();
            }
        } catch (Exception e) {
            android.util.Log.e("MainActivity", "Failed to get saved MediaProjection data", e);
        }
        return null;
    }

    private void saveMediaProjectionData(int resultCode, Intent data) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(data);
            String dataStr = Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT);
            
            prefs.edit()
                    .putInt(KEY_MEDIA_PROJECTION_RESULT_CODE, resultCode)
                    .putString(KEY_MEDIA_PROJECTION_DATA, dataStr)
                    .apply();
        } catch (Exception e) {
            android.util.Log.e("MainActivity", "Failed to save MediaProjection data", e);
        }
    }

    private void startServiceWithMediaProjection(int resultCode, Intent data) {
        Intent serviceIntent = new Intent(this, FloatingService.class);
        serviceIntent.putExtra("resultCode", resultCode);
        serviceIntent.putExtra("resultData", data);
        ContextCompat.startForegroundService(this, serviceIntent);

        isServiceRunning = true;
        updateServiceStatus();
        Toast.makeText(this, "服务已启动，点击悬浮球截屏分析", Toast.LENGTH_LONG).show();
    }

    private void stopFloatingService() {
        Intent intent = new Intent(this, FloatingService.class);
        stopService(intent);
        isServiceRunning = false;
        updateServiceStatus();
        Toast.makeText(this, "服务已停止", Toast.LENGTH_SHORT).show();
    }

    private void updateServiceStatus() {
        if (isServiceRunning) {
            statusText.setText("● 服务运行中 - 点击悬浮球截屏分析");
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
        } else {
            statusText.setText("○ 服务未启动");
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "悬浮窗权限已授权", Toast.LENGTH_SHORT).show();
            }
        }

        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                saveMediaProjectionData(resultCode, data);
                
                Intent serviceIntent = new Intent(this, FloatingService.class);
                serviceIntent.putExtra("resultCode", resultCode);
                serviceIntent.putExtra("resultData", data);
                ContextCompat.startForegroundService(this, serviceIntent);

                isServiceRunning = true;
                updateServiceStatus();
                Toast.makeText(this, "服务已启动，点击悬浮球截屏分析", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadSettings();
        updateServiceStatus();
    }

    @Override
    public void onBackPressed() {
        if (isServiceRunning) {
            new AlertDialog.Builder(this)
                    .setTitle("提示")
                    .setMessage("服务正在后台运行，确定要退出吗？")
                    .setPositiveButton("确定", (d, which) -> {
                        moveTaskToBack(true);
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } else {
            super.onBackPressed();
        }
    }
}
