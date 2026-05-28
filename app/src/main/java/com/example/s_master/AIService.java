package com.example.s_master;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;

import android.util.Base64;
import android.util.Log;
import android.util.Pair;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

public class AIService {

    private static final String TAG = "AIService";
    private static final String PREFS_NAME = "S_masterPrefs";

    private static final String KEY_PROVIDER = "api_provider";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_VISION_MODEL = "vision_model";
    private static final String KEY_REASONING_MODEL = "reasoning_model";
    private static final String KEY_CUSTOM_URL = "custom_url";
    private static final String KEY_VISION_MODELS = "saved_vision_models";
    private static final String KEY_TEXT_MODELS = "saved_text_models";
    private static final String KEY_VISION_PROMPT = "vision_prompt";
    private static final String KEY_TEXT_PROMPT = "text_prompt";

    private Context context;
    private String provider;
    private String apiKey;
    private String visionModel;
    private String reasoningModel;
    private String customUrl;

    public static class ProviderInfo {
        public final String name;
        public final String modelsUrl;
        public final String chatUrl;
        public final String label;
        public ProviderInfo(String name, String modelsUrl, String chatUrl, String label) {
            this.name = name;
            this.modelsUrl = modelsUrl;
            this.chatUrl = chatUrl;
            this.label = label;
        }
    }

    public static final ProviderInfo[] PROVIDERS = {
        new ProviderInfo("deepseek", "https://api.deepseek.com/models", "https://api.deepseek.com/v1/chat/completions", "DeepSeek"),
        new ProviderInfo("siliconflow", "https://api.siliconflow.cn/v1/models", "https://api.siliconflow.cn/v1/chat/completions", "硅基流动"),
        new ProviderInfo("openai", "https://api.openai.com/v1/models", "https://api.openai.com/v1/chat/completions", "OpenAI"),
        new ProviderInfo("custom", "", "", "自定义"),
    };

    private static final String[] VISION_KEYWORDS = {
        "vision", "/vl", "vl-", "-vl-", "_vl", "vl2",
        "omni", "4o", "gemini-pro-vision", "claude-3",
        "glm-4v", "qwen-vl", "internvl", "janus",
        "deepseek-vl", "cogvlm", "llava", "yi-vl",
        "step-1v", "minicpm-v", "phi-3-vision"
    };

    private static final String[] SKIP_KEYWORDS = {
        "stable-diffusion", "sdxl", "flux", "dall-e",
        "kolors", "pixart", "diffusion", "wuerstchen",
        "latent-consistency", "embedding", "tts",
        "whisper", "davinci", "babbage", "moderation"
    };

    public AIService(Context context) {
        this.context = context;
        loadConfig();
    }

    private void loadConfig() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        provider = prefs.getString(KEY_PROVIDER, "deepseek");
        apiKey = prefs.getString(KEY_API_KEY, "");
        visionModel = prefs.getString(KEY_VISION_MODEL, "");
        reasoningModel = prefs.getString(KEY_REASONING_MODEL, "");
        customUrl = prefs.getString(KEY_CUSTOM_URL, "");
    }

    public void saveConfig(String provider, String apiKey, String visionModel, String reasoningModel, String customUrl) {
        this.provider = provider;
        this.apiKey = apiKey;
        this.visionModel = visionModel;
        this.reasoningModel = reasoningModel;
        this.customUrl = customUrl;
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PROVIDER, provider)
                .putString(KEY_API_KEY, apiKey)
                .putString(KEY_VISION_MODEL, visionModel)
                .putString(KEY_REASONING_MODEL, reasoningModel)
                .putString(KEY_CUSTOM_URL, customUrl)
                .apply();
    }

    public String getProvider() { return provider; }
    public String getApiKey() { return apiKey; }
    public String getVisionModel() { return visionModel; }
    public String getReasoningModel() { return reasoningModel; }
    public String getCustomUrl() { return customUrl; }
    public boolean hasApiKey() { return apiKey != null && !apiKey.isEmpty(); }

    public void saveModelLists(List<String> visionModels, List<String> textModels) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_VISION_MODELS, joinList(visionModels))
                .putString(KEY_TEXT_MODELS, joinList(textModels))
                .apply();
    }

    public List<String> getSavedVisionModels() {
        return splitList(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_VISION_MODELS, ""));
    }

    public List<String> getSavedTextModels() {
        return splitList(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_TEXT_MODELS, ""));
    }

    private String joinList(List<String> list) {
        if (list == null || list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            if (sb.length() > 0) sb.append(",");
            sb.append(s.replace(",", "，"));
        }
        return sb.toString();
    }

    private List<String> splitList(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return result;
        for (String s : raw.split(",")) {
            s = s.trim();
            if (!s.isEmpty()) result.add(s);
        }
        return result;
    }

    public void saveVisionPrompt(String prompt) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_VISION_PROMPT, prompt).apply();
    }

    public void saveTextPrompt(String prompt) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_TEXT_PROMPT, prompt).apply();
    }

    public String getVisionPrompt() {
        String custom = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_VISION_PROMPT, "");
        return custom.isEmpty() ? SYSTEM_PROMPT_VISION : custom;
    }

    public String getTextPrompt() {
        String custom = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_TEXT_PROMPT, "");
        return custom.isEmpty() ? SYSTEM_PROMPT_TEXT : custom;
    }

    private ProviderInfo getProviderInfo() {
        for (ProviderInfo p : PROVIDERS) {
            if (p.name.equals(provider)) return p;
        }
        return PROVIDERS[0];
    }

    private String getChatUrl() {
        if ("custom".equals(provider) && !customUrl.isEmpty()) return customUrl;
        return getProviderInfo().chatUrl;
    }

    private String getVisionModelId() {
        return visionModel.isEmpty() ? getDefaultVisionModel() : visionModel;
    }

    private String getReasoningModelId() {
        return reasoningModel.isEmpty() ? getDefaultReasoningModel() : reasoningModel;
    }

    private String getDefaultVisionModel() {
        switch (provider) {
            case "deepseek": return "deepseek-chat";
            case "siliconflow": return "deepseek-ai/DeepSeek-V2.5";
            case "openai": return "gpt-4o";
            default: return "gpt-4o";
        }
    }

    private String getDefaultReasoningModel() {
        switch (provider) {
            case "deepseek": return "deepseek-chat";
            case "siliconflow": return "deepseek-ai/DeepSeek-V2.5";
            case "openai": return "gpt-4o-mini";
            default: return "gpt-4o-mini";
        }
    }

    private boolean isVisionModel(String id) {
        String lower = id.toLowerCase();
        for (String kw : VISION_KEYWORDS) {
            if (lower.contains(kw)) return true;
        }
        return false;
    }

    private boolean shouldSkip(String id) {
        String lower = id.toLowerCase();
        for (String kw : SKIP_KEYWORDS) {
            if (lower.contains(kw)) return true;
        }
        return false;
    }

    public interface ModelListCallback {
        void onResult(List<String> visionModels, List<String> textModels);
        void onError(String error);
    }

    public void fetchModels(ModelListCallback callback) {
        if (!hasApiKey()) {
            callback.onError("NO_API_KEY");
            return;
        }

        String modelsUrl = getProviderInfo().modelsUrl;
        if (modelsUrl.isEmpty()) {
            callback.onError("未找到模型列表地址");
            return;
        }

        NetworkUtils.fetchModels(modelsUrl, apiKey, new NetworkUtils.NetworkCallback<JSONArray>() {
            @Override
            public void onSuccess(JSONArray data) {
                List<String> vision = new ArrayList<>();
                List<String> text = new ArrayList<>();
                for (int i = 0; i < data.length(); i++) {
                    try {
                        String m = data.getJSONObject(i).getString("id");
                        if (shouldSkip(m)) continue;
                        if (isVisionModel(m)) {
                            vision.add(m);
                        } else {
                            text.add(m);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Parse model error", e);
                    }
                }
                callback.onResult(vision, text);
            }

            @Override
            public void onError(String error) {
                callback.onError(error);
            }
        });
    }

    public interface AiCallback {
        void onResult(String analysis, String suggestion);
        void onError(String error);
    }

    public void analyzeScreenshot(Bitmap screenshot, AiCallback callback) {
        if (!hasApiKey()) {
            callback.onError("NO_API_KEY");
            return;
        }
        try {
            String base64 = bitmapToBase64(screenshot, 80);
            String model = getVisionModelId();
            String body = buildVisionRequestBody(model, base64);
            NetworkUtils.callChatApi(getChatUrl(), apiKey, body, new NetworkUtils.NetworkCallback<String>() {
                @Override
                public void onSuccess(String result) {
                    String[] parts = parseResponse(result);
                    callback.onResult(parts[0], parts[1]);
                }

                @Override
                public void onError(String error) {
                    callback.onError(error != null ? error : "请求失败");
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Vision API error", e);
            callback.onError(e.getMessage());
        }
    }

    public void testModel(String modelId, boolean isVision, AiCallback callback) {
        if (!hasApiKey()) {
            callback.onError("NO_API_KEY");
            return;
        }
        try {
            JSONObject body = new JSONObject();
            body.put("model", modelId);
            body.put("max_tokens", 100);
            body.put("temperature", 0.5);
            JSONArray messages = new JSONArray();
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", "回复「测试通过」四个字即可");
            messages.put(userMsg);
            body.put("messages", messages);

            NetworkUtils.callChatApi(getChatUrl(), apiKey, body.toString(), new NetworkUtils.NetworkCallback<String>() {
                @Override
                public void onSuccess(String result) {
                    callback.onResult("", result);
                }

                @Override
                public void onError(String error) {
                    callback.onError(error != null ? error : "测试失败");
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Test model error", e);
            callback.onError(e.getMessage());
        }
    }

    public void analyzeText(String chatText, AiCallback callback) {
        if (!hasApiKey()) {
            callback.onResult("", getBuiltInSuggestion(chatText));
            return;
        }
        try {
            String model = getReasoningModelId();
            String body = buildTextRequestBody(model, chatText);
            NetworkUtils.callChatApi(getChatUrl(), apiKey, body, new NetworkUtils.NetworkCallback<String>() {
                @Override
                public void onSuccess(String result) {
                    String[] parts = parseResponse(result);
                    callback.onResult(parts[0], parts[1]);
                }

                @Override
                public void onError(String error) {
                    Log.w(TAG, "Text analysis fallback: " + error);
                    callback.onResult("", getBuiltInSuggestion(chatText));
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Reasoning API error", e);
            callback.onResult("", getBuiltInSuggestion(chatText));
        }
    }

    private String buildVisionRequestBody(String model, String base64) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("max_tokens", 1000);
        body.put("temperature", 0.7);

        JSONArray messages = new JSONArray();
        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        JSONArray content = new JSONArray();
        JSONObject textPart = new JSONObject();
        textPart.put("type", "text");
        textPart.put("text", getVisionPrompt());
        content.put(textPart);
        JSONObject imagePart = new JSONObject();
        JSONObject imageUrl = new JSONObject();
        imageUrl.put("url", "data:image/jpeg;base64," + base64);
        imagePart.put("type", "image_url");
        imagePart.put("image_url", imageUrl);
        content.put(imagePart);
        userMsg.put("content", content);
        messages.put(userMsg);
        body.put("messages", messages);
        return body.toString();
    }

    private String buildTextRequestBody(String model, String chatText) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("max_tokens", 800);
        body.put("temperature", 0.7);

        JSONArray messages = new JSONArray();
        JSONObject sysMsg = new JSONObject();
        sysMsg.put("role", "system");
        sysMsg.put("content", getTextPrompt());
        messages.put(sysMsg);
        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", "聊天内容：\n" + chatText);
        messages.put(userMsg);
        body.put("messages", messages);
        return body.toString();
    }

    public interface ChatCallback {
        void onResponse(String reply);
        void onError(String error);
    }

    public void chatWithAgent(List<Pair<String, String>> conversation, ChatCallback callback) {
        if (!hasApiKey()) {
            callback.onError("请先在设置中填写 API Key");
            return;
        }
        try {
            String model = getReasoningModelId();
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("max_tokens", 1000);
            body.put("temperature", 0.8);

            JSONArray messages = new JSONArray();
            JSONObject sysMsg = new JSONObject();
            sysMsg.put("role", "system");
            String prompt = getTextPrompt();
            if (!prompt.contains("Agent") && !prompt.contains("助")) {
                prompt = "你是S master智能助手，基于屏幕分析结果为用户提供聊天建议。" +
                        "你擅长分析对话内容，给出高情商回复建议。" +
                        "回答要简洁、实用、有温度。\n\n" + prompt;
            }
            sysMsg.put("content", prompt);
            messages.put(sysMsg);

            for (Pair<String, String> turn : conversation) {
                JSONObject msg = new JSONObject();
                msg.put("role", turn.first);
                msg.put("content", turn.second);
                messages.put(msg);
            }
            body.put("messages", messages);

            NetworkUtils.callChatApi(getChatUrl(), apiKey, body.toString(), new NetworkUtils.NetworkCallback<String>() {
                @Override
                public void onSuccess(String result) {
                    try {
                        JSONObject json = new JSONObject(result);
                        String reply = json.getJSONArray("choices")
                                .getJSONObject(0)
                                .getJSONObject("message")
                                .getString("content");
                        callback.onResponse(reply);
                    } catch (Exception e) {
                        callback.onResponse(result);
                    }
                }

                @Override
                public void onError(String error) {
                    callback.onError(error != null ? error : "对话请求失败");
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Chat error", e);
            callback.onError(e.getMessage());
        }
    }

    private String[] parseResponse(String response) {
        String analysis = "";
        StringBuilder suggestionsBuilder = new StringBuilder();

        int analysisStart = response.indexOf("【分析】");
        if (analysisStart >= 0) {
            int analysisEnd = response.indexOf("【建议", analysisStart + 1);
            if (analysisEnd < 0) analysisEnd = response.length();
            analysis = response.substring(analysisStart + 4, analysisEnd).trim();
        } else {
            analysis = response;
        }

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "【建议([^】]*)】\\s*([\\s\\S]*?)(?=(【建议|$))");
        java.util.regex.Matcher matcher = pattern.matcher(response);

        int count = 0;
        while (matcher.find() && count < 3) {
            String style = matcher.group(1).trim();
            String text = matcher.group(2).trim();
            style = style.replaceFirst("[一二三]\\s*-\\s*", "");
            if (!text.isEmpty()) {
                if (suggestionsBuilder.length() > 0) suggestionsBuilder.append("|||");
                suggestionsBuilder.append(style).append("|||").append(text);
                count++;
            }
        }

        String suggestion = suggestionsBuilder.toString();
        if (suggestion.isEmpty()) {
            int idx = Math.max(response.lastIndexOf("建议"), response.lastIndexOf("回复"));
            if (idx > 0) {
                if (analysis.isEmpty()) analysis = response.substring(0, idx).trim();
                suggestion = "默认|||" + response.substring(idx).replaceFirst("建议[：:]?|回复[：:]?", "").trim();
            } else {
                suggestion = "默认|||" + response;
            }
        }

        return new String[]{analysis, suggestion};
    }

    private String bitmapToBase64(Bitmap bitmap, int quality) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos);
        byte[] bytes = baos.toByteArray();
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    private String getBuiltInSuggestion(String chatText) {
        String t = chatText.toLowerCase();
        if (t.contains("在吗") || t.contains("在嘛"))
            return "别回「在吗」，直接说事。比如：刚看到一个好玩的，分享给你";
        if (t.contains("晚安"))
            return "别只说晚安，加点温度：\"晚安，记得梦到我哦 😉\"";
        if (t.contains("哈哈") || t.contains("haha"))
            return "别只回哈哈，延续话题：\"你平时也这么开心吗 😄\"";
        if (t.contains("好呀") || t.contains("好的") || t.contains("可以啊"))
            return "对方同意了！赶紧收口：\"那周六下午3点我来接你\"";
        if (t.contains("在干嘛") || t.contains("干嘛呢"))
            return "别问干嘛呢，分享生活：\"刚看到一家超棒的店，推荐给你\"";
        if (t.contains("忙吗"))
            return "别问忙不忙，直接邀约：\"周末有空一起喝杯咖啡吗\"";
        if (t.contains("下次") || t.contains("改天"))
            return "\"下次吧\"是万能挡箭牌。冷2-3天再换个具体活动重新约";
        if (t.contains("哦") || t.contains("嗯") || t.contains("好吧"))
            return "对方有点敷衍了，换话题制造兴趣：\"我刚遇到一个超离谱的事...\"";
        if (t.contains("喜欢你") || t.contains("在一起"))
            return "对方在升温！自然回应：\"我也很喜欢和你聊天\" → 邀约面谈";
        if (t.contains("分手") || t.contains("不合适"))
            return "冷静应对。\"好。\" → 最有力量的回复。然后至少冷2周";
        if (t.contains("约") || t.contains("见面") || t.contains("出来"))
            return "推进见面！三步法：埋线→模糊邀约→确定时间地点";
        return "观察对方的回复长度和频率，主动引领话题方向。";
    }

    public static final String SYSTEM_PROMPT_VISION =
        "你是一个恋爱聊天顾问「S master」。请分析这张聊天截图，按以下要求回复：\n\n" +
        "1. 先提取截图中的聊天内容（过滤掉广告、UI元素、系统通知等无关信息）\n" +
        "2. 分析当前的对话状态：谁在主动、话题走向、对方兴趣信号\n" +
        "3. 给出3种不同风格的回复建议，让用户选择\n\n" +
        "回复格式（严格按此格式）：\n" +
        "【分析】\n" +
        "（对聊天的分析，1-3句话）\n" +
        "【建议一 - 温柔风格】\n" +
        "（温柔体贴型回复，可直接使用）\n" +
        "【建议二 - 幽默风格】\n" +
        "（幽默风趣型回复）\n" +
        "【建议三 - 直球风格】\n" +
        "（直接坦率型回复）";

    public static final String SYSTEM_PROMPT_TEXT =
        "你是恋爱聊天顾问「S master」。分析以下聊天内容：\n\n" +
        "1. 判断当前关系阶段（破冰/好感/升温/邀约/约会/亲密/确立）\n" +
        "2. 分析对方的态度和兴趣信号\n" +
        "3. 给出3种不同风格的回复建议，让用户选择\n\n" +
        "回复格式（严格按此格式）：\n" +
        "【分析】\n" +
        "（分析内容）\n" +
        "【建议一 - 温柔风格】\n" +
        "（温柔体贴型回复）\n" +
        "【建议二 - 幽默风格】\n" +
        "（幽默风趣型回复）\n" +
        "【建议三 - 直球风格】\n" +
        "（直接坦率型回复）";
}
