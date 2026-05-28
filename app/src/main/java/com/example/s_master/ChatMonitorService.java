package com.example.s_master;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import java.nio.ByteBuffer;
import java.util.Random;

public class ChatMonitorService extends Service {

    private static final String TAG = "ChatMonitorService";
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "s_master_channel";

    private static final String ACTION_CAPTURE = "com.example.s_master.CAPTURE_NOW";
    private static final String ACTION_TOGGLE_MODE = "com.example.s_master.TOGGLE_MODE";
    private static final String ACTION_COPY = "com.example.s_master.COPY_RESULT";
    private static final String ACTION_STOP = "com.example.s_master.STOP_SERVICE";
    private static final String ACTION_OPEN_APP = "com.example.s_master.OPEN_APP";

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    private AIService aiService;

    private Random random = new Random();
    private int captureInterval = 6000;
    private long lastAnalysisTime = 0;
    private boolean isAnalyzing = false;
    private boolean isManualMode = true;

    private String lastResultText = "";
    private String lastResultLabel = "";

    private BroadcastReceiver actionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_CAPTURE.equals(action)) {
                takeScreenshot();
            } else if (ACTION_TOGGLE_MODE.equals(action)) {
                isManualMode = !isManualMode;
                String modeText = isManualMode ? "手动模式" : "实时模式";
                if (!isManualMode) {
                    startCaptureLoop();
                }
                showToast("已切换为" + modeText);
                updateNotification("就绪");
            } else if (ACTION_COPY.equals(action)) {
                copyLastResult();
            } else if (ACTION_STOP.equals(action)) {
                stopService();
            } else if (ACTION_OPEN_APP.equals(action)) {
                Intent launchIntent = new Intent(ChatMonitorService.this, MainActivity.class);
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(launchIntent);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        aiService = new AIService(this);

        backgroundThread = new HandlerThread("ChatMonitor");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification("就绪"));

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_CAPTURE);
        filter.addAction(ACTION_TOGGLE_MODE);
        filter.addAction(ACTION_COPY);
        filter.addAction(ACTION_STOP);
        filter.addAction(ACTION_OPEN_APP);
        registerReceiver(actionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "S master",
                NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(true);
        channel.enableVibration(false);
        channel.setSound(null, null);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);
    }

    private Notification createNotification(String status) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        String modeLabel = isManualMode ? "手动" : "实时";

        String contentText;
        if ("分析中".equals(status)) {
            contentText = "⏳ 正在分析屏幕内容...";
        } else if (!lastResultText.isEmpty()) {
            contentText = "💡 " + lastResultLabel;
        } else {
            contentText = "点击「📷」分析当前屏幕（" + modeLabel + "）";
        }

        PendingIntent openPi = PendingIntent.getBroadcast(this, 5,
                new Intent(ACTION_OPEN_APP).setPackage(getPackageName()),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        PendingIntent capturePi = PendingIntent.getBroadcast(this, 0,
                new Intent(ACTION_CAPTURE).setPackage(getPackageName()),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        PendingIntent togglePi = PendingIntent.getBroadcast(this, 1,
                new Intent(ACTION_TOGGLE_MODE).setPackage(getPackageName()),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        PendingIntent copyPi = PendingIntent.getBroadcast(this, 2,
                new Intent(ACTION_COPY).setPackage(getPackageName()),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        PendingIntent stopPi = PendingIntent.getBroadcast(this, 3,
                new Intent(ACTION_STOP).setPackage(getPackageName()),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        builder.setContentTitle("S master · " + modeLabel)
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .setContentIntent(openPi)
                .setShowWhen(true)
                .addAction(android.R.drawable.ic_menu_camera, "📷 分析", capturePi)
                .addAction(android.R.drawable.ic_menu_sort_by_size, "🔄 " + modeLabel, togglePi);

        if (!lastResultText.isEmpty()) {
            builder.addAction(android.R.drawable.ic_menu_edit, "📋 复制", copyPi);
        }

        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "⏹ 停止", stopPi);

        if ("分析中".equals(status)) {
            builder.setProgress(0, 0, true);
        }

        return builder.build();
    }

    private void updateNotification(String status) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFICATION_ID, createNotification(status));
    }

    private void showToast(String msg) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
    }

    private void copyLastResult() {
        if (lastResultText.isEmpty()) {
            showToast("暂无分析结果可复制");
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("suggestion", lastResultText);
        clipboard.setPrimaryClip(clip);
        showToast("✅ 已复制分析结果");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if (intent.hasExtra("mode")) {
                isManualMode = "manual".equals(intent.getStringExtra("mode"));
            }
            if (intent.hasExtra("resultCode") && intent.hasExtra("resultData")) {
                int resultCode = intent.getIntExtra("resultCode", 0);
                Intent data;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    data = intent.getParcelableExtra("resultData", Intent.class);
                } else {
                    data = intent.getParcelableExtra("resultData");
                }
                if (data != null) {
                    setupMediaProjection(resultCode, data);
                }
            }
        }
        return START_STICKY;
    }

    private void setupMediaProjection(int resultCode, Intent data) {
        MediaProjectionManager projectionManager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        mediaProjection = projectionManager.getMediaProjection(resultCode, data);
        if (mediaProjection != null) {
            setupVirtualDisplay();
            updateNotification("就绪");
            if (!isManualMode) {
                startCaptureLoop();
            }
        }
    }

    private void setupVirtualDisplay() {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        int density = metrics.densityDpi;

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(reader -> {
            if (!isManualMode) {
                Image image = reader.acquireLatestImage();
                if (image != null) {
                    if (!isAnalyzing) {
                        processScreenshot(image);
                    }
                    image.close();
                }
            }
        }, backgroundHandler);

        virtualDisplay = mediaProjection.createVirtualDisplay(
                "S master Capture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                backgroundHandler
        );
    }

    private void startCaptureLoop() {
        backgroundHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isManualMode) {
                    takeScreenshot();
                    backgroundHandler.postDelayed(this,
                            captureInterval + random.nextInt(3000));
                }
            }
        }, 3000);
    }

    private void takeScreenshot() {
        if (imageReader == null || isAnalyzing) return;

        Image image = imageReader.acquireLatestImage();
        if (image == null) {
            sendSuggestion("⚠️ 截图失败，请检查权限");
            return;
        }
        processScreenshot(image);
        image.close();
    }

    private void processScreenshot(Image image) {
        long now = System.currentTimeMillis();
        if (!isManualMode && now - lastAnalysisTime < 10000) return;
        lastAnalysisTime = now;

        Bitmap bitmap = imageToBitmap(image);
        if (bitmap == null) return;

        isAnalyzing = true;
        updateNotification("分析中");

        if (aiService.hasApiKey()) {
            sendSuggestion("🤖 AI 正在分析屏幕内容...");

            final Bitmap safeBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            bitmap.recycle();

            aiService.analyzeScreenshot(safeBitmap, new AIService.AiCallback() {
                @Override
                public void onResult(String analysis, String suggestion) {
                    String fullText = (analysis.isEmpty() ? "" : "📊 " + analysis + "\n\n")
                            + "💡 " + suggestion;
                    String label = suggestion.length() > 40
                            ? suggestion.substring(0, 40) + "..." : suggestion;

                    lastResultText = fullText;
                    lastResultLabel = label;
                    sendSuggestion(fullText);
                    isAnalyzing = false;
                    updateNotification("就绪");
                    if (!safeBitmap.isRecycled()) safeBitmap.recycle();
                }

                @Override
                public void onError(String error) {
                    if ("NO_API_KEY".equals(error)) {
                        sendSuggestion("⚠️ 请先在设置中填写 API Key");
                    } else {
                        Log.e(TAG, "Vision AI error: " + error);
                        sendSuggestion("⚠️ AI 分析失败：" + error);
                    }
                    isAnalyzing = false;
                    updateNotification("就绪");
                    if (!safeBitmap.isRecycled()) safeBitmap.recycle();
                }
            });
        } else {
            sendSuggestion("⚠️ 请先在设置中填写 API Key");
            isAnalyzing = false;
            updateNotification("就绪");
            bitmap.recycle();
        }
    }

    private Bitmap imageToBitmap(Image image) {
        if (image.getPlanes().length == 0) return null;
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        int width = image.getWidth();
        int height = image.getHeight();
        int pixelStride = image.getPlanes()[0].getPixelStride();
        int rowStride = image.getPlanes()[0].getRowStride();
        int rowPadding = rowStride - pixelStride * width;

        Bitmap bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(buffer);
        if (rowPadding > 0) {
            Bitmap cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height);
            bitmap.recycle();
            return cropped;
        }
        return bitmap;
    }

    private void sendSuggestion(String suggestion) {
        Intent intent = new Intent("com.example.s_master.SUGGESTION");
        intent.putExtra("suggestion", suggestion);
        sendBroadcast(intent);
    }

    private void stopService() {
        showToast("🛑 服务已停止");
        stopSelf();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(actionReceiver);
        } catch (Exception e) {}
        if (virtualDisplay != null) virtualDisplay.release();
        if (imageReader != null) imageReader.close();
        if (mediaProjection != null) mediaProjection.stop();

        if (backgroundThread != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                backgroundThread.quitSafely();
            } else {
                backgroundThread.quit();
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
