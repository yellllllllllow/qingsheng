package com.example.s_master;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;

import android.content.res.ColorStateList;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_OVERLAY_PERMISSION = 101;
    private static final int REQUEST_MEDIA_PROJECTION = 103;
    private static final int REQUEST_IMAGE_PICK = 105;
    private static final String PREFS_NAME = "S_masterPrefs";
    private static final String KEY_WAS_RUNNING = "was_running";
    private static final String KEY_CHAT_HISTORY = "chat_history";

    private AIService aiService;
    private MediaProjectionManager projectionManager;
    private Intent monitorServiceIntent;
    private boolean isRunning = false;
    private boolean isManualMode = true;
    private boolean permissionsReady = false;

    private List<String> visionModels = new ArrayList<>();
    private List<String> textModels = new ArrayList<>();

    private RecyclerView chatList;
    private ChatAdapter chatAdapter;
    private List<Message> messages = new ArrayList<>();
    private EditText inputText;
    private MaterialButton sendBtn, attachBtn;
    private TextView statusText;
    private View statusDot;

    private List<Pair<String, String>> conversationHistory = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.status_text);
        statusDot = findViewById(R.id.status_dot);
        chatList = findViewById(R.id.chat_list);
        inputText = findViewById(R.id.input_text);
        sendBtn = findViewById(R.id.send_btn);
        attachBtn = findViewById(R.id.attach_btn);

        aiService = new AIService(this);
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        chatAdapter = new ChatAdapter(messages);
        chatList.setLayoutManager(new LinearLayoutManager(this));
        chatList.setAdapter(chatAdapter);

        findViewById(R.id.settings_btn).setOnClickListener(v -> showSettingsSheet());

        View startBtn = findViewById(R.id.start_btn);
        startBtn.setOnClickListener(v -> {
            if (isRunning) {
                stopService();
                startBtn.setBackgroundResource(R.drawable.start_btn_bg);
                ((TextView) startBtn).setText("▶ 开始");
            } else {
                startService();
                startBtn.setBackgroundResource(R.drawable.stop_btn_bg);
                ((TextView) startBtn).setText("⏹ 停止");
            }
        });

        sendBtn.setOnClickListener(v -> sendMessage());
        attachBtn.setOnClickListener(v -> pickImage());

        inputText.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                if (!event.isShiftPressed()) {
                    sendMessage();
                    return true;
                }
            }
            return false;
        });

        loadChatHistory();
        if (messages.isEmpty()) {
            addMessage(Message.TYPE_SYSTEM, "欢迎使用 S master Agent！\n\n📌 点击输入框输入文字对话\n📷 点击图片按钮上传截图分析\n⚙️ 点击右上角设置 API Key");

            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            boolean hasKey = !aiService.getApiKey().isEmpty();
            if (!hasKey) {
                addMessage(Message.TYPE_SYSTEM, "⚠️ 未检测到 API Key\n请点击右上角 ⚙️ 进入设置并填写 API Key");
            }
        }

        checkFirstRunPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    private void addMessage(int type, String text) {
        messages.add(new Message(type, text));
        chatAdapter.notifyItemInserted(messages.size() - 1);
        chatList.smoothScrollToPosition(messages.size() - 1);
        saveChatHistory();
    }

    private void sendMessage() {
        String text = inputText.getText().toString().trim();
        if (text.isEmpty()) return;

        inputText.setText("");
        addMessage(Message.TYPE_USER, text);

        if (!aiService.hasApiKey()) {
            addMessage(Message.TYPE_AGENT, "⚠️ 请先在设置中填写 API Key\n点击右上角 ⚙️ 进入设置");
            return;
        }

        conversationHistory.add(new Pair<>("user", text));

        addMessage(Message.TYPE_SYSTEM, "⏳ Agent 思考中...");
        final int loadingIdx = messages.size() - 1;

        aiService.chatWithAgent(conversationHistory, new AIService.ChatCallback() {
            @Override
            public void onResponse(String reply) {
                runOnUiThread(() -> {
                    messages.remove(loadingIdx);
                    chatAdapter.notifyItemRemoved(loadingIdx);

                    conversationHistory.add(new Pair<>("assistant", reply));
                    addMessage(Message.TYPE_AGENT, reply);
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    messages.remove(loadingIdx);
                    chatAdapter.notifyItemRemoved(loadingIdx);
                    addMessage(Message.TYPE_AGENT, "❌ " + error);
                });
            }
        });
    }

    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_IMAGE_PICK);
    }

    private void analyzeImage(Uri imageUri) {
        addMessage(Message.TYPE_USER, "📷 [上传了一张截图]");

        if (!aiService.hasApiKey()) {
            addMessage(Message.TYPE_AGENT, "⚠️ 请先在设置中填写 API Key");
            return;
        }

        addMessage(Message.TYPE_SYSTEM, "⏳ 正在分析截图...");
        final int loadingIdx = messages.size() - 1;

        try {
            android.graphics.Bitmap bitmap = android.provider.MediaStore.Images.Media.getBitmap(
                    getContentResolver(), imageUri);
            aiService.analyzeScreenshot(bitmap, new AIService.AiCallback() {
                @Override
                public void onResult(String analysis, String suggestion) {
                    runOnUiThread(() -> {
                        messages.remove(loadingIdx);
                        chatAdapter.notifyItemRemoved(loadingIdx);

                        String result = "";
                        if (!analysis.isEmpty()) {
                            result += "📊 分析：\n" + analysis + "\n\n";
                        }
                        result += "💡 建议：\n" + suggestion;

                        conversationHistory.add(new Pair<>("user", "【截图分析结果】" + analysis + " " + suggestion));
                        conversationHistory.add(new Pair<>("assistant", "分析完成，以上是截图分析结果和建议"));
                        addMessage(Message.TYPE_AGENT, result);
                    });
                }

                @Override
                public void onError(String error) {
                    runOnUiThread(() -> {
                        messages.remove(loadingIdx);
                        chatAdapter.notifyItemRemoved(loadingIdx);
                        addMessage(Message.TYPE_AGENT, "❌ 分析失败：" + error);
                    });
                }
            });
        } catch (Exception e) {
            messages.remove(loadingIdx);
            chatAdapter.notifyItemRemoved(loadingIdx);
            addMessage(Message.TYPE_AGENT, "❌ 读取图片失败：" + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_IMAGE_PICK && resultCode == RESULT_OK && data != null) {
            analyzeImage(data.getData());
            return;
        }

        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            checkPermissionsAndShowDialog();
            return;
        }

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
        }
    }

    private void saveChatHistory() {
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, messages.size() - 100);
        for (int i = start; i < messages.size(); i++) {
            Message m = messages.get(i);
            sb.append(m.type).append("|").append(m.text.replace("\n", "\\n")).append("\n");
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putString(KEY_CHAT_HISTORY, sb.toString()).apply();
    }

    private void loadChatHistory() {
        String raw = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(KEY_CHAT_HISTORY, "");
        if (raw.isEmpty()) return;
        messages.clear();
        for (String line : raw.split("\n")) {
            int sep = line.indexOf("|");
            if (sep > 0) {
                try {
                    int type = Integer.parseInt(line.substring(0, sep));
                    String text = line.substring(sep + 1).replace("\\n", "\n");
                    messages.add(new Message(type, text));
                } catch (Exception ignored) {}
            }
        }
        if (messages.size() > 200) {
            messages = new ArrayList<>(messages.subList(messages.size() - 200, messages.size()));
        }
    }

    private void checkPermissionsAndAutoStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 104);
            }
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
            aiService.testModel(model, true, new AIService.AiCallback() {
                @Override
                public void onResult(String analysis, String suggestion) {
                    runOnUiThread(() -> {
                        testVisionBtn.setText("✅ " + shortModel);
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
            aiService.testModel(model, false, new AIService.AiCallback() {
                @Override
                public void onResult(String analysis, String suggestion) {
                    runOnUiThread(() -> {
                        testTextBtn.setText("✅ " + shortModel);
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

    private void checkFirstRunPermissions() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean firstRun = prefs.getBoolean("first_run", true);

        if (!firstRun) {
            checkPermissionsAndShowDialog();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("📱 权限授权")
                .setMessage("为了让应用正常工作，需要授予以下权限：\n\n" +
                        "• 悬浮窗权限 - 显示悬浮按钮\n" +
                        "• 屏幕录制权限 - 读取屏幕内容\n" +
                        "• 通知权限 - 显示服务通知")
                .setPositiveButton("开始授权", (dialog, which) -> {
                    prefs.edit().putBoolean("first_run", false).apply();
                    requestAllPermissions();
                })
                .setCancelable(false)
                .show();
    }

    private void checkPermissionsAndShowDialog() {
        List<String> missing = new ArrayList<>();
        if (!Settings.canDrawOverlays(this)) missing.add("悬浮窗权限");

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int code = prefs.getInt("media_projection_result_code", -1);
        String data = prefs.getString("media_projection_result_data", null);
        if (code == -1 || data == null) missing.add("屏幕录制权限");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                missing.add("通知权限");
            }
        }

        if (!missing.isEmpty()) {
            StringBuilder msg = new StringBuilder("缺少以下权限：\n");
            for (String p : missing) msg.append("• ").append(p).append("\n");
            msg.append("\n是否前往授权？");
            new AlertDialog.Builder(this)
                    .setTitle("⚠️ 缺少权限").setMessage(msg.toString())
                    .setPositiveButton("前往授权", (d, w) -> requestAllPermissions())
                    .setNegativeButton("稍后", null).show();
        }
    }

    private void requestAllPermissions() {
        if (!Settings.canDrawOverlays(this)) {
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())), REQUEST_OVERLAY_PERMISSION);
            return;
        }
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int code = prefs.getInt("media_projection_result_code", -1);
        String data = prefs.getString("media_projection_result_data", null);
        if (code == -1 || data == null) {
            startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 104);
            }
        }
    }

    private void startService() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int code = prefs.getInt("media_projection_result_code", -1);
        String data = prefs.getString("media_projection_result_data", null);

        if (code == -1 || data == null) {
            startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION);
        } else {
            startMonitoringService(code, data);
        }
    }

    private void startMonitoringService(int resultCode, String resultData) {
        Intent data = new Intent();
        data.setData(Uri.parse(resultData));

        monitorServiceIntent = new Intent(this, ChatMonitorService.class);
        monitorServiceIntent.putExtra("resultCode", resultCode);
        monitorServiceIntent.putExtra("resultData", data);
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
        stopService(new Intent(this, FloatingService.class));
        updateUI(false);
        saveRunningState(false);
        Toast.makeText(this, "🛑 服务已停止", Toast.LENGTH_SHORT).show();
    }

    private void updateUI(boolean running) {
        isRunning = running;
        if (running) {
            statusText.setText("运行中");
            statusDot.setBackgroundResource(R.drawable.dot_green);
        } else {
            statusText.setText("未启动");
            statusDot.setBackgroundResource(R.drawable.dot_red);
        }
    }

    private void saveRunningState(boolean running) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putBoolean(KEY_WAS_RUNNING, running).apply();
    }

    private static class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.ViewHolder> {
        private List<Message> data;
        ChatAdapter(List<Message> data) { this.data = data; }

        @Override
        public int getItemViewType(int position) {
            return data.get(position).type;
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == Message.TYPE_USER) {
                return new ViewHolder(inflater.inflate(R.layout.chat_item_user, parent, false));
            } else if (viewType == Message.TYPE_SYSTEM) {
                return new ViewHolder(inflater.inflate(R.layout.chat_item_system, parent, false));
            }
            return new ViewHolder(inflater.inflate(R.layout.chat_item_agent, parent, false));
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            Message msg = data.get(position);
            if (holder.textView != null) {
                holder.textView.setText(msg.text);
            }
        }

        @Override
        public int getItemCount() { return data.size(); }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView textView;
            ViewHolder(View itemView) {
                super(itemView);
                textView = itemView.findViewById(R.id.agent_text);
                if (textView == null) textView = itemView.findViewById(R.id.user_text);
                if (textView == null) textView = itemView.findViewById(R.id.system_text);
            }
        }
    }
}
