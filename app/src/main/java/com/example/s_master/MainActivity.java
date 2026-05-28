package com.example.s_master;

import androidx.appcompat.app.AppCompatActivity;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;

import android.content.res.ColorStateList;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_OVERLAY_PERMISSION = 101;
    private static final int REQUEST_MEDIA_PROJECTION = 103;
    private static final String PREFS_NAME = "S_masterPrefs";
    private static final String KEY_WAS_RUNNING = "was_running";

    private MaterialButton startBtn, stopBtn;
    private TextView statusText, hintText;
    private View statusDot;
    private AIService aiService;

    private MediaProjectionManager projectionManager;
    private Intent monitorServiceIntent;
    private boolean isRunning = false;
    private boolean isManualMode = true;
    private boolean permissionsReady = false;

    private List<String> visionModels = new ArrayList<>();
    private List<String> textModels = new ArrayList<>();
    
    private TextView visionModelName, textModelName;
    private View visionStatusDot, textStatusDot;
    private TextView visionStatusText, textStatusText;
    private TextView modelStatusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        startBtn = findViewById(R.id.start_btn);
        stopBtn = findViewById(R.id.stop_btn);
        statusText = findViewById(R.id.status_text);
        statusDot = findViewById(R.id.status_dot);
        hintText = findViewById(R.id.hint_text);

        aiService = new AIService(this);
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        startBtn.setOnClickListener(v -> startService());
        stopBtn.setOnClickListener(v -> stopService());

        findViewById(R.id.settings_btn).setOnClickListener(v -> showSettingsSheet());
        
        initModelStatusViews();
        loadSavedModelStatus();
        
        requestScreenCapture();
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkPermissionsAndAutoStart();
    }

    private void checkPermissionsAndAutoStart() {
        boolean notifOk = true;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notifOk = false;
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 104);
            }
        }

        permissionsReady = notifOk;

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean wasRunning = prefs.getBoolean(KEY_WAS_RUNNING, false);

        if (wasRunning && !isRunning && permissionsReady) {
            hintText.setText("上次服务已中断，正在重新启动...");
            startService();
        } else if (isRunning) {
            hintText.setText("服务运行中，下拉通知栏点击「开始分析」");
        } else if (!permissionsReady) {
            hintText.setText("请完成权限授予后点击「启动」");
        } else {
            hintText.setText("点击下方「启动」按钮开启服务");
        }
    }

    private void showSettingsSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View sheet = getLayoutInflater().inflate(R.layout.bottom_sheet_settings, null);
        dialog.setContentView(sheet);

        Spinner providerSpinner = sheet.findViewById(R.id.bs_provider);
        androidx.appcompat.widget.AppCompatEditText
                apiKeyInput = sheet.findViewById(R.id.bs_api_key),
                customUrlInput = sheet.findViewById(R.id.bs_custom_url);
        MaterialButton fetchBtn = sheet.findViewById(R.id.bs_fetch_btn);
        TextView fetchStatus = sheet.findViewById(R.id.bs_fetch_status);
        Spinner visionSpinner = sheet.findViewById(R.id.bs_vision_model);
        Spinner reasoningSpinner = sheet.findViewById(R.id.bs_reasoning_model);
        MaterialButton modeManual = sheet.findViewById(R.id.bs_mode_manual);
        MaterialButton modeRealtime = sheet.findViewById(R.id.bs_mode_realtime);
        MaterialButton saveBtn = sheet.findViewById(R.id.bs_save_btn);
        MaterialButton cancelBtn = sheet.findViewById(R.id.bs_cancel_btn);

        String[] providerLabels = new String[AIService.PROVIDERS.length];
        for (int i = 0; i < AIService.PROVIDERS.length; i++) {
            providerLabels[i] = AIService.PROVIDERS[i].label;
        }

        ArrayAdapter<String> providerAdapter = new ArrayAdapter<>(this,
                R.layout.spinner_dropdown_item, providerLabels);
        providerSpinner.setAdapter(providerAdapter);

        String currentProvider = aiService.getProvider();
        for (int i = 0; i < AIService.PROVIDERS.length; i++) {
            if (AIService.PROVIDERS[i].name.equals(currentProvider)) {
                providerSpinner.setSelection(i);
                break;
            }
        }

        providerSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                boolean isCustom = "custom".equals(AIService.PROVIDERS[position].name);
                customUrlInput.setVisibility(isCustom ? View.VISIBLE : View.GONE);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        boolean isCustomInit = "custom".equals(currentProvider);
        customUrlInput.setVisibility(isCustomInit ? View.VISIBLE : View.GONE);

        apiKeyInput.setText(aiService.getApiKey());
        customUrlInput.setText(aiService.getCustomUrl());

        String savedVisionModel = aiService.getVisionModel();
        String savedReasoningModel = aiService.getReasoningModel();

        List<String> savedVisionList = aiService.getSavedVisionModels();
        List<String> savedTextList = aiService.getSavedTextModels();

        if (!savedVisionList.isEmpty()) {
            String[] vArr = savedVisionList.toArray(new String[0]);
            ArrayAdapter<String> vAdapter = new ArrayAdapter<>(this,
                    R.layout.spinner_dropdown_item, vArr);
            visionSpinner.setAdapter(vAdapter);
            visionModels = savedVisionList;
            int idx = savedVisionModel.isEmpty() ? 0 : savedVisionList.indexOf(savedVisionModel);
            visionSpinner.setSelection(Math.max(0, idx));
        } else {
            String[] defaultModels = {"点击「获取模型列表」加载"};
            ArrayAdapter<String> emptyAdapter = new ArrayAdapter<>(this,
                    R.layout.spinner_dropdown_item, defaultModels);
            visionSpinner.setAdapter(emptyAdapter);
        }

        if (!savedTextList.isEmpty()) {
            String[] tArr = savedTextList.toArray(new String[0]);
            ArrayAdapter<String> tAdapter = new ArrayAdapter<>(this,
                    R.layout.spinner_dropdown_item, tArr);
            reasoningSpinner.setAdapter(tAdapter);
            textModels = savedTextList;
            int idx = savedReasoningModel.isEmpty() ? 0 : savedTextList.indexOf(savedReasoningModel);
            reasoningSpinner.setSelection(Math.max(0, idx));
        } else {
            String[] defaultModels = {"点击「获取模型列表」加载"};
            ArrayAdapter<String> emptyAdapter = new ArrayAdapter<>(this,
                    R.layout.spinner_dropdown_item, defaultModels);
            reasoningSpinner.setAdapter(emptyAdapter);
        }

        updateModeButtons(modeManual, modeRealtime);

        modeManual.setOnClickListener(v -> {
            isManualMode = true;
            updateModeButtons(modeManual, modeRealtime);
        });

        modeRealtime.setOnClickListener(v -> {
            isManualMode = false;
            updateModeButtons(modeManual, modeRealtime);
        });

        Button testVisionBtn = sheet.findViewById(R.id.bs_test_vision);
        Button testTextBtn = sheet.findViewById(R.id.bs_test_text);
        androidx.appcompat.widget.AppCompatEditText visionPromptInput = sheet.findViewById(R.id.bs_vision_prompt);
        androidx.appcompat.widget.AppCompatEditText textPromptInput = sheet.findViewById(R.id.bs_text_prompt);
        TextView promptToggle = sheet.findViewById(R.id.bs_prompt_toggle);
        LinearLayout promptSection = sheet.findViewById(R.id.bs_prompt_section);
        Button resetVisionPrompt = sheet.findViewById(R.id.bs_reset_vision_prompt);
        Button resetTextPrompt = sheet.findViewById(R.id.bs_reset_text_prompt);

        visionPromptInput.setText(aiService.getVisionPrompt());
        textPromptInput.setText(aiService.getTextPrompt());

        promptToggle.setOnClickListener(v -> {
            boolean isVis = promptSection.getVisibility() == View.GONE;
            promptSection.setVisibility(isVis ? View.VISIBLE : View.GONE);
            promptToggle.setText(isVis ? "📝 收起自定义提示词" : "📝 自定义 AI 提示词（点我展开）");
        });

        resetVisionPrompt.setOnClickListener(v ->
                visionPromptInput.setText(AIService.SYSTEM_PROMPT_VISION));
        resetTextPrompt.setOnClickListener(v ->
                textPromptInput.setText(AIService.SYSTEM_PROMPT_TEXT));

        testVisionBtn.setOnClickListener(v -> {
            String model = visionSpinner.getSelectedItem().toString();
            if (model.startsWith("（") || model.startsWith("点击")) {
                Toast.makeText(this, "请先选择图形模型", Toast.LENGTH_SHORT).show();
                return;
            }
            String shortModel = model.length() > 20 ? model.substring(0, 20) + "..." : model;
            testVisionBtn.setText("⏳ 测试: " + shortModel);
            testVisionBtn.setEnabled(false);
            setVisionModelStatus(model, "testing");
            aiService.testModel(model, true, new AIService.AiCallback() {
                @Override
                public void onResult(String analysis, String suggestion) {
                    runOnUiThread(() -> {
                        testVisionBtn.setText("✅ " + shortModel);
                        setVisionModelStatus(model, "success");
                        Toast.makeText(MainActivity.this, "图形模型测试通过: " + model, Toast.LENGTH_SHORT).show();
                        new android.os.Handler().postDelayed(() -> {
                            testVisionBtn.setText("▶ 测试图形模型");
                            testVisionBtn.setEnabled(true);
                        }, 2500);
                    });
                }
                @Override
                public void onError(String error) {
                    runOnUiThread(() -> {
                        testVisionBtn.setText("❌ " + shortModel);
                        setVisionModelStatus(model, "failed");
                        new android.os.Handler().postDelayed(() -> {
                            testVisionBtn.setText("▶ 测试图形模型");
                            testVisionBtn.setEnabled(true);
                        }, 3000);
                        Toast.makeText(MainActivity.this, "测试失败 [" + model + "]: " + error, Toast.LENGTH_LONG).show();
                    });
                }
            });
        });

        testTextBtn.setOnClickListener(v -> {
            String model = reasoningSpinner.getSelectedItem().toString();
            if (model.startsWith("（") || model.startsWith("点击")) {
                Toast.makeText(this, "请先选择推理模型", Toast.LENGTH_SHORT).show();
                return;
            }
            String shortModel = model.length() > 20 ? model.substring(0, 20) + "..." : model;
            testTextBtn.setText("⏳ 测试: " + shortModel);
            testTextBtn.setEnabled(false);
            setTextModelStatus(model, "testing");
            aiService.testModel(model, false, new AIService.AiCallback() {
                @Override
                public void onResult(String analysis, String suggestion) {
                    runOnUiThread(() -> {
                        testTextBtn.setText("✅ " + shortModel);
                        setTextModelStatus(model, "success");
                        Toast.makeText(MainActivity.this, "推理模型测试通过: " + model, Toast.LENGTH_SHORT).show();
                        new android.os.Handler().postDelayed(() -> {
                            testTextBtn.setText("▶ 测试推理模型");
                            testTextBtn.setEnabled(true);
                        }, 2500);
                    });
                }
                @Override
                public void onError(String error) {
                    runOnUiThread(() -> {
                        testTextBtn.setText("❌ " + shortModel);
                        setTextModelStatus(model, "failed");
                        new android.os.Handler().postDelayed(() -> {
                            testTextBtn.setText("▶ 测试推理模型");
                            testTextBtn.setEnabled(true);
                        }, 3000);
                        Toast.makeText(MainActivity.this, "测试失败 [" + model + "]: " + error, Toast.LENGTH_LONG).show();
                    });
                }
            });
        });

        fetchBtn.setOnClickListener(v -> {
            String key = apiKeyInput.getText().toString().trim();
            if (key.isEmpty()) {
                fetchStatus.setText("⚠️ 请先填写 API Key");
                return;
            }

            String providerName = AIService.PROVIDERS[providerSpinner.getSelectedItemPosition()].name;

            if ("custom".equals(providerName)) {
                String customUrl = customUrlInput.getText().toString().trim();
                if (customUrl.isEmpty()) {
                    fetchStatus.setText("⚠️ 自定义模式下请填写 API 地址");
                    return;
                }
            }

            fetchStatus.setText("⏳ 正在获取模型列表...");

            aiService.saveConfig(providerName, key, "", "", customUrlInput.getText().toString().trim());

            aiService.fetchModels(new AIService.ModelListCallback() {
                @Override
                public void onResult(List<String> vModels, List<String> tModels) {
                    runOnUiThread(() -> {
                        visionModels = vModels;
                        textModels = tModels;

                        aiService.saveModelLists(vModels, tModels);

                        String[] vArr = vModels.isEmpty() ? new String[]{"（无图形模型）"} : vModels.toArray(new String[0]);
                        String[] tArr = tModels.isEmpty() ? new String[]{"（无文本模型）"} : tModels.toArray(new String[0]);

                        ArrayAdapter<String> vAdapter = new ArrayAdapter<>(MainActivity.this,
                                R.layout.spinner_dropdown_item, vArr);
                        ArrayAdapter<String> tAdapter = new ArrayAdapter<>(MainActivity.this,
                                R.layout.spinner_dropdown_item, tArr);

                        visionSpinner.setAdapter(vAdapter);
                        reasoningSpinner.setAdapter(tAdapter);

                        if (!vModels.isEmpty()) {
                            int idx = savedVisionModel.isEmpty() ? 0 : vModels.indexOf(savedVisionModel);
                            visionSpinner.setSelection(Math.max(0, idx));
                        }
                        if (!tModels.isEmpty()) {
                            int idx = savedReasoningModel.isEmpty() ? 0 : tModels.indexOf(savedReasoningModel);
                            reasoningSpinner.setSelection(Math.max(0, idx));
                        }

                        fetchStatus.setText("✅ 获取成功！图形 " + vModels.size() + " 个，文本 " + tModels.size() + " 个");
                    });
                }

                @Override
                public void onError(String error) {
                    runOnUiThread(() -> {
                        if ("NO_API_KEY".equals(error)) {
                            fetchStatus.setText("⚠️ 请先填写 API Key");
                        } else {
                            fetchStatus.setText("❌ 获取失败：" + error);
                        }
                    });
                }
            });
        });

        saveBtn.setOnClickListener(v -> {
            String providerName = AIService.PROVIDERS[providerSpinner.getSelectedItemPosition()].name;
            String key = apiKeyInput.getText().toString().trim();
            String customUrl = customUrlInput.getText().toString().trim();

            String visionModel = "";
            String reasoningModel = "";
            if (visionSpinner.getAdapter() != null && visionSpinner.getAdapter().getCount() > 0
                    && !visionSpinner.getSelectedItem().toString().startsWith("（")) {
                visionModel = visionSpinner.getSelectedItem().toString();
            }
            if (reasoningSpinner.getAdapter() != null && reasoningSpinner.getAdapter().getCount() > 0
                    && !reasoningSpinner.getSelectedItem().toString().startsWith("（")) {
                reasoningModel = reasoningSpinner.getSelectedItem().toString();
            }

            aiService.saveConfig(providerName, key, visionModel, reasoningModel, customUrl);
            aiService.saveVisionPrompt(visionPromptInput.getText().toString().trim());
            aiService.saveTextPrompt(textPromptInput.getText().toString().trim());

            String msg;
            if (key.isEmpty()) {
                msg = "已切换为内置分析模式";
            } else {
                msg = "配置已保存（" + providerName + "）";
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private void updateModeButtons(MaterialButton manual, MaterialButton realtime) {
        int blue = getResources().getColor(R.color.purple_500);
        int gray = getResources().getColor(R.color.text_hint);
        if (isManualMode) {
            manual.setBackgroundTintList(ColorStateList.valueOf(blue));
            manual.setTextColor(getResources().getColor(R.color.white));
            realtime.setBackgroundTintList(ColorStateList.valueOf(gray));
            realtime.setTextColor(getResources().getColor(R.color.white));
        } else {
            manual.setBackgroundTintList(ColorStateList.valueOf(gray));
            manual.setTextColor(getResources().getColor(R.color.white));
            realtime.setBackgroundTintList(ColorStateList.valueOf(blue));
            realtime.setTextColor(getResources().getColor(R.color.white));
        }
    }

    private void requestScreenCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION);
        }
    }

    private void startService() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int savedResultCode = prefs.getInt("media_projection_result_code", -1);
        String savedResultData = prefs.getString("media_projection_result_data", null);
        
        if (savedResultCode == -1 || savedResultData == null) {
            Intent captureIntent = projectionManager.createScreenCaptureIntent();
            startActivityForResult(captureIntent, REQUEST_MEDIA_PROJECTION);
        } else {
            startMonitoringService(savedResultCode, savedResultData);
        }
    }

    private void startMonitoringService(int resultCode, String resultData) {
        Intent data = new Intent();
        data.setData(Uri.parse(resultData));
        
        monitorServiceIntent = new Intent(this, ChatMonitorService.class);
        monitorServiceIntent.putExtra("resultCode", resultCode);
        monitorServiceIntent.putExtra("resultData", data);
        monitorServiceIntent.putExtra("mode", isManualMode ? "manual" : "realtime");
        startService(monitorServiceIntent);

        if (Settings.canDrawOverlays(this)) {
            Intent floatIntent = new Intent(this, FloatingService.class);
            floatIntent.putExtra("mode", isManualMode ? "manual" : "realtime");
            startService(floatIntent);
        }

        updateUI(true);
        saveRunningState(true);

        Toast.makeText(this, "✅ 服务已启动，可切换到其他应用", Toast.LENGTH_SHORT).show();
        
        moveTaskToBack(true);
    }

    private void stopService() {
        if (monitorServiceIntent != null) {
            stopService(monitorServiceIntent);
            monitorServiceIntent = null;
        }

        Intent floatIntent = new Intent(this, FloatingService.class);
        stopService(floatIntent);

        isRunning = false;
        updateUI(false);
        saveRunningState(false);
        Toast.makeText(this, "服务已停止", Toast.LENGTH_SHORT).show();
    }

    private void updateUI(boolean running) {
        isRunning = running;
        statusText.setText(running ? "运行中" : "未启动");
        statusDot.setBackgroundResource(running ? R.drawable.dot_green : R.drawable.dot_red);

        startBtn.setEnabled(!running);
        stopBtn.setEnabled(running);

        if (hintText != null) {
            if (running) {
                hintText.setText("✅ 服务运行中，下拉通知栏点击「开始分析」");
            } else {
                hintText.setText("点击下方「启动」按钮开启服务");
            }
        }
    }

    private void saveRunningState(boolean running) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_WAS_RUNNING, running)
                .apply();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                String dataStr = data.getData() != null ? data.getData().toString() : "screen_capture_granted";
                prefs.edit()
                        .putInt("media_projection_result_code", resultCode)
                        .putString("media_projection_result_data", dataStr)
                        .apply();
                
                monitorServiceIntent = new Intent(this, ChatMonitorService.class);
                monitorServiceIntent.putExtra("resultCode", resultCode);
                monitorServiceIntent.putExtra("resultData", data);
                monitorServiceIntent.putExtra("mode", isManualMode ? "manual" : "realtime");
                startService(monitorServiceIntent);

                if (Settings.canDrawOverlays(this)) {
                    Intent floatIntent = new Intent(this, FloatingService.class);
                    floatIntent.putExtra("mode", isManualMode ? "manual" : "realtime");
                    startService(floatIntent);
                }

                updateUI(true);
                saveRunningState(true);

                Toast.makeText(this, "✅ 服务已启动，可切换到其他应用", Toast.LENGTH_SHORT).show();
                
                moveTaskToBack(true);
            } else {
                Toast.makeText(this, "需要屏幕录制权限", Toast.LENGTH_SHORT).show();
                saveRunningState(false);
            }
        } else if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            if (isRunning && Settings.canDrawOverlays(this)) {
                Intent floatIntent = new Intent(this, FloatingService.class);
                startService(floatIntent);
            }
        }
    }

    private void initModelStatusViews() {
        visionModelName = findViewById(R.id.vision_model_name);
        textModelName = findViewById(R.id.text_model_name);
        visionStatusDot = findViewById(R.id.vision_status_dot);
        textStatusDot = findViewById(R.id.text_status_dot);
        visionStatusText = findViewById(R.id.vision_status_text);
        textStatusText = findViewById(R.id.text_status_text);
        modelStatusText = findViewById(R.id.model_status_text);
        
        findViewById(R.id.vision_model_card).setOnClickListener(v -> showSettingsSheet());
        findViewById(R.id.text_model_card).setOnClickListener(v -> showSettingsSheet());
    }

    private void loadSavedModelStatus() {
        String visionModel = aiService.getVisionModel();
        String textModel = aiService.getReasoningModel();
        
        visionModelName.setText(visionModel.isEmpty() ? "未选择" : visionModel);
        textModelName.setText(textModel.isEmpty() ? "未选择" : textModel);
        
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String visionStatus = prefs.getString("vision_model_status", "untested");
        String textStatus = prefs.getString("text_model_status", "untested");
        
        updateModelStatus(visionStatusDot, visionStatusText, visionStatus);
        updateModelStatus(textStatusDot, textStatusText, textStatus);
        updateOverallStatus();
    }

    private void updateModelStatus(View dot, TextView text, String status) {
        switch (status) {
            case "success":
                dot.setBackgroundResource(R.drawable.dot_green);
                text.setText("✓ 测试通过");
                text.setTextColor(getResources().getColor(R.color.green_500));
                break;
            case "testing":
                dot.setBackgroundResource(R.drawable.dot_yellow);
                text.setText("⏳ 测试中");
                text.setTextColor(getResources().getColor(R.color.yellow_500));
                break;
            case "failed":
                dot.setBackgroundResource(R.drawable.dot_red);
                text.setText("✗ 测试失败");
                text.setTextColor(getResources().getColor(R.color.red_500));
                break;
            default:
                dot.setBackgroundResource(R.drawable.dot_grey);
                text.setText("未测试");
                text.setTextColor(getResources().getColor(R.color.text_hint));
        }
    }

    public void setVisionModelStatus(String modelName, String status) {
        visionModelName.setText(modelName);
        updateModelStatus(visionStatusDot, visionStatusText, status);
        saveModelStatus("vision", modelName, status);
        updateOverallStatus();
    }

    public void setTextModelStatus(String modelName, String status) {
        textModelName.setText(modelName);
        updateModelStatus(textStatusDot, textStatusText, status);
        saveModelStatus("text", modelName, status);
        updateOverallStatus();
    }

    private void saveModelStatus(String type, String modelName, String status) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit()
                .putString(type + "_model_name", modelName)
                .putString(type + "_model_status", status)
                .apply();
    }

    private void updateOverallStatus() {
        String visionStatus = visionStatusText.getText().toString();
        String textStatus = textStatusText.getText().toString();
        
        if (visionStatus.equals("✓ 测试通过") && textStatus.equals("✓ 测试通过")) {
            modelStatusText.setText("全部通过");
            modelStatusText.setTextColor(getResources().getColor(R.color.green_500));
        } else if (visionStatus.equals("⏳ 测试中") || textStatus.equals("⏳ 测试中")) {
            modelStatusText.setText("测试中");
            modelStatusText.setTextColor(getResources().getColor(R.color.yellow_500));
        } else if (visionStatus.equals("✗ 测试失败") || textStatus.equals("✗ 测试失败")) {
            modelStatusText.setText("部分失败");
            modelStatusText.setTextColor(getResources().getColor(R.color.red_500));
        } else {
            modelStatusText.setText("未测试");
            modelStatusText.setTextColor(getResources().getColor(R.color.text_hint));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}
