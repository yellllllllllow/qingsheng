package com.example.s_master;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class FloatingService extends Service {

    private static final long AUTO_HIDE_DELAY = 30000;

    private WindowManager windowManager;
    private LayoutInflater inflater;
    private int screenWidth;
    private int screenHeight;
    private float density;

    private LinearLayout popupView;
    private WindowManager.LayoutParams popupParams;
    
    private View dockView;
    private WindowManager.LayoutParams dockParams;
    private float dockDownX, dockDownY;
    private float dockTouchX, dockTouchY;
    private boolean isDragging = false;
    private android.view.GestureDetector gestureDetector;

    private float popupDownX, popupDownY;
    private float popupTouchX, popupTouchY;
    private boolean isLoading = false;
    private boolean popupShowing = false;
    private String lastSuggestion = "";
    private Handler autoHideHandler = new Handler();
    private Runnable autoHideRunnable = this::hideResultPopup;

    private BroadcastReceiver suggestionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.example.s_master.SUGGESTION".equals(intent.getAction())) {
                String suggestion = intent.getStringExtra("suggestion");
                if (suggestion != null) {
                    lastSuggestion = suggestion;
                    isLoading = false;
                    showResultPopup(suggestion);
                }
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);

        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        density = metrics.density;

        createNotificationChannel();
        Notification notification = new Notification.Builder(this, "float_channel")
                .setContentTitle("S master")
                .setContentText("分析服务运行中")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .build();
        startForeground(2, notification);

        registerReceiver(suggestionReceiver,
                new IntentFilter("com.example.s_master.SUGGESTION"),
                Context.RECEIVER_NOT_EXPORTED);

        showDock();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel("float_channel", "分析服务",
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private void showDock() {
        if (dockView != null) {
            try {
                windowManager.removeView(dockView);
            } catch (Exception e) {}
        }

        int dockSize = (int)(56 * density);
        dockView = new TaiChiView(this);
        
        int layoutFlag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        dockParams = new WindowManager.LayoutParams(
                dockSize,
                dockSize,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);

        dockParams.gravity = Gravity.TOP | Gravity.LEFT;
        dockParams.x = screenWidth - dockSize - (int)(20 * density);
        dockParams.y = screenHeight / 2;

        gestureDetector = new android.view.GestureDetector(this, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override
            public void onLongPress(MotionEvent e) {
                isDragging = true;
                dockView.getRootView().setAlpha(0.7f);
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (!isDragging) {
                    triggerCapture();
                }
                return true;
            }
        });
        gestureDetector.setIsLongpressEnabled(true);

        dockView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    isDragging = false;
                    dockDownX = dockParams.x;
                    dockDownY = dockParams.y;
                    dockTouchX = event.getRawX();
                    dockTouchY = event.getRawY();
                    gestureDetector.onTouchEvent(event);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (isDragging) {
                        float dx = event.getRawX() - dockTouchX;
                        float dy = event.getRawY() - dockTouchY;
                        dockParams.x = Math.max(0, Math.min(screenWidth - dockSize, (int)(dockDownX + dx)));
                        dockParams.y = Math.max(0, Math.min(screenHeight - dockSize, (int)(dockDownY + dy)));
                        windowManager.updateViewLayout(dockView, dockParams);
                    } else {
                        float dist = (float) Math.sqrt(
                                Math.pow(event.getRawX() - dockTouchX, 2)
                                + Math.pow(event.getRawY() - dockTouchY, 2));
                        if (dist > 20) {
                            isDragging = true;
                            dockView.getRootView().setAlpha(0.7f);
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (isDragging) {
                        dockView.getRootView().setAlpha(1.0f);
                    } else {
                        gestureDetector.onTouchEvent(event);
                    }
                    isDragging = false;
                    return true;
            }
            return false;
        });

        windowManager.addView(dockView, dockParams);
    }

    private void triggerCapture() {
        Intent captureIntent = new Intent("com.example.s_master.CAPTURE_NOW");
        captureIntent.setPackage(getPackageName());
        sendBroadcast(captureIntent);
        isLoading = true;
    }

    private void showResultPopup(String suggestion) {
        autoHideHandler.removeCallbacks(autoHideRunnable);

        if (popupView != null) {
            try {
                windowManager.removeView(popupView);
            } catch (Exception e) {}
            popupView = null;
        }

        popupView = (LinearLayout) inflater.inflate(R.layout.result_overlay, null);
        TextView popupClose = popupView.findViewById(R.id.popup_close);
        TextView popupAnalysis = popupView.findViewById(R.id.popup_analysis);
        LinearLayout analysisSection = popupView.findViewById(R.id.analysis_section);
        LinearLayout optionsContainer = popupView.findViewById(R.id.options_container);

        List<String[]> suggestions = parseSuggestion(suggestion);

        String analysis = suggestions.isEmpty() ? "" : suggestions.get(0)[2];
        if (!analysis.isEmpty()) {
            popupAnalysis.setText(analysis);
            analysisSection.setVisibility(View.VISIBLE);
        }

        int emojis[] = {0x1F60A, 0x1F604, 0x1F525};
        String stylePrefixes[] = {"温柔", "幽默", "直球"};

        for (int i = 1; i < suggestions.size(); i++) {
            String[] item = suggestions.get(i);
            String style = item[0];
            String text = item[1];

            int matchedIdx = -1;
            for (int j = 0; j < stylePrefixes.length; j++) {
                if (style.contains(stylePrefixes[j])) { matchedIdx = j; break; }
            }

            String styleLabel = matchedIdx >= 0
                    ? new String(Character.toChars(emojis[matchedIdx])) + " " + stylePrefixes[matchedIdx] + "风格"
                    : "💡 " + style;

            optionsContainer.addView(createOptionCard(styleLabel, text));
        }

        int layoutFlag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        popupParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);

        popupParams.gravity = Gravity.TOP | Gravity.LEFT;

        popupView.measure(
                View.MeasureSpec.makeMeasureSpec(screenWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(screenHeight, View.MeasureSpec.AT_MOST));
        int popupW = popupView.getMeasuredWidth();

        popupParams.x = Math.max(0, Math.min(screenWidth - popupW, dockParams.x + (int)(56 * density / 2) - popupW / 2));
        popupParams.y = Math.max(0, Math.min(screenHeight - popupView.getMeasuredHeight(), dockParams.y - popupView.getMeasuredHeight() / 2));

        popupClose.setOnClickListener(v -> hideResultPopup());

        popupView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    popupDownX = popupParams.x;
                    popupDownY = popupParams.y;
                    popupTouchX = event.getRawX();
                    popupTouchY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - popupTouchX;
                    float dy = event.getRawY() - popupTouchY;
                    popupParams.x = (int) (popupDownX + dx);
                    popupParams.y = (int) (popupDownY + dy);
                    windowManager.updateViewLayout(popupView, popupParams);
                    return true;
            }
            return false;
        });

        windowManager.addView(popupView, popupParams);
        popupShowing = true;

        autoHideHandler.postDelayed(autoHideRunnable, AUTO_HIDE_DELAY);
    }

    private View createOptionCard(String label, String text) {
        int dp8 = (int)(8 * density);
        int dp12 = (int)(12 * density);
        int accentColor = getResources().getColor(R.color.purple_500);
        int textColor = getResources().getColor(R.color.text_primary);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp12, dp12, dp12, dp12);
        card.setBackgroundResource(R.drawable.option_card_bg);

        LinearLayout.LayoutParams margin = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        margin.bottomMargin = dp8;
        card.setLayoutParams(margin);

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        labelView.setTextColor(accentColor);
        labelView.setTypeface(null, Typeface.BOLD);

        TextView textView = new TextView(this);
        textView.setText(text);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        textView.setTextColor(textColor);
        textView.setLineSpacing(0, 1.2f);
        textView.setPadding(0, dp8, 0, dp12);

        Button copyBtn = new Button(this);
        copyBtn.setText("📋 复制此条");
        copyBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        copyBtn.setAllCaps(false);
        copyBtn.setTextColor(getResources().getColor(R.color.white));

        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setCornerRadius(dp8);
        btnBg.setColors(new int[]{accentColor, getResources().getColor(R.color.purple_700)});
        btnBg.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        btnBg.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
        copyBtn.setBackground(btnBg);

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int)(36 * density));
        copyBtn.setLayoutParams(btnLp);

        copyBtn.setOnClickListener(v -> {
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("suggestion", text));
            android.widget.Toast.makeText(FloatingService.this,
                    "✅ 已复制：「" + label + "」", android.widget.Toast.LENGTH_SHORT).show();
            hideResultPopup();
        });

        card.addView(labelView);
        card.addView(textView);
        card.addView(copyBtn);
        return card;
    }

    private List<String[]> parseSuggestion(String raw) {
        List<String[]> result = new ArrayList<>();

        String[] parts = raw.split("\\|\\|\\|");
        if (parts.length >= 3) {
            result.add(new String[]{"分析", "", parts[0]});
            for (int i = 1; i + 1 < parts.length; i += 2) {
                String style = parts[i].trim();
                String text = parts[i + 1].trim();
                if (!text.isEmpty()) {
                    result.add(new String[]{style, text, ""});
                }
            }
        } else if (parts.length == 1) {
            result.add(new String[]{"分析", "", ""});
            result.add(new String[]{"默认", parts[0], ""});
        }

        if (result.size() <= 1) {
            result.add(new String[]{"默认", raw, ""});
        }

        return result;
    }

    private String extractSuggestion(String text) {
        if (text == null || text.isEmpty()) return "";
        String[] parts = text.split("\\|\\|\\|");
        if (parts.length >= 3) {
            for (int i = 1; i + 1 < parts.length; i += 2) {
                String t = parts[i + 1].trim();
                if (!t.isEmpty()) return t;
            }
        }
        return text;
    }

    private void hideResultPopup() {
        autoHideHandler.removeCallbacks(autoHideRunnable);
        if (popupView != null) {
            try {
                windowManager.removeView(popupView);
            } catch (Exception e) {}
            popupView = null;
        }
        popupShowing = false;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        autoHideHandler.removeCallbacks(autoHideRunnable);
        try {
            unregisterReceiver(suggestionReceiver);
        } catch (Exception e) {}
        hideResultPopup();
        if (dockView != null) {
            try {
                windowManager.removeView(dockView);
            } catch (Exception e) {}
            dockView = null;
        }
    }
}