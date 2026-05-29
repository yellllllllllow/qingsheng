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

public class ChatMonitorService extends Service {

    private static final String TAG = "ChatMonitorService";
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "s_master_channel";

    private static final String ACTION_CAPTURE = "com.example.s_master.CAPTURE_NOW";
    private static final String ACTION_COPY = "com.example.s_master.COPY_RESULT";
    private static final String ACTION_STOP = "com.example.s_master.STOP_SERVICE";
    private static final String ACTION_OPEN_APP = "com.example.s_master.OPEN_APP";

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    private AIService aiService;

    private volatile boolean isAnalyzing = false;
    private volatile String lastResultText = "";
    private volatile String lastResultLabel = "";
    private volatile boolean pendingCapture = false;

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            Log.w(TAG, "MediaProjection stopped by system");
            getSharedPreferences("S_masterPrefs", MODE_PRIVATE).edit()
                    .putBoolean("media_projection_granted", false).apply();
            stopSelf();
        }
    };

    private BroadcastReceiver actionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_CAPTURE.equals(action)) {
                takeScreenshot();
            } else if (ACTION_COPY.equals(action)) {
                copyLastResult();
            } else if (ACTION_STOP.equals(action)) {
                showToast("🛑 服务已停止");
                stopSelf();
            } else if (ACTION_OPEN_APP.equals(action)) {
                Intent launchIntent = new Intent(ChatMonitorService.this, MainActivity.class);
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(launchIntent);
            }
        }
    };

    private boolean isSilentMode() {
        return getSharedPreferences("S_masterPrefs", MODE_PRIVATE)
                .getBoolean("silent_mode", false);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        aiService = new AIService(this);

        backgroundThread = new HandlerThread("CaptureWorker");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());

        createNotificationChannel();
        startForegroundService();

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_CAPTURE);
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
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification createNotification() {
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID);

        boolean hasResult = !lastResultText.isEmpty();

        boolean silent = isSilentMode();

        String contentText;
        if (isAnalyzing) {
            contentText = "⏳ 正在分析屏幕...";
        } else if (hasResult) {
            contentText = (silent ? "🔇 " : "💡 ") + lastResultLabel;
        } else {
            contentText = silent ? "🔇 静默模式 | 点击悬浮球分析" : "点击悬浮球分析当前屏幕";
        }

        PendingIntent openPi = PendingIntent.getBroadcast(this, 5,
                new Intent(ACTION_OPEN_APP).setPackage(getPackageName()),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        PendingIntent capturePi = PendingIntent.getBroadcast(this, 0,
                new Intent(ACTION_CAPTURE).setPackage(getPackageName()),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        PendingIntent copyPi = PendingIntent.getBroadcast(this, 2,
                new Intent(ACTION_COPY).setPackage(getPackageName()),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        PendingIntent stopPi = PendingIntent.getBroadcast(this, 3,
                new Intent(ACTION_STOP).setPackage(getPackageName()),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        builder.setContentTitle("S master")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .setContentIntent(hasResult ? copyPi : openPi)
                .setShowWhen(true)
                .addAction(android.R.drawable.ic_menu_camera, "📷", capturePi);

        if (hasResult) {
            builder.addAction(android.R.drawable.ic_menu_edit, "📋 复制", copyPi);
        }

        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "⏹", stopPi);

        if (isAnalyzing) {
            builder.setProgress(0, 0, true);
        }

        return builder.build();
    }

    private void updateNotification() {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, createNotification());
    }

    private void showToast(String msg) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
    }

    private void copyLastResult() {
        if (lastResultText.isEmpty()) {
            showToast("暂无分析结果");
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("suggestion", lastResultText));
        showToast("✅ 已复制");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_CAPTURE);
        filter.addAction(ACTION_COPY);
        filter.addAction(ACTION_STOP);
        filter.addAction(ACTION_OPEN_APP);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(actionReceiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(actionReceiver, filter);
        }

        if (intent != null) {
            int resultCode = intent.getIntExtra("resultCode", -1);
            String resultData = intent.getStringExtra("resultData");
            if (resultCode != -1 && resultData != null) {
                restoreMediaProjection(resultCode, resultData);
            }
        }

        return START_STICKY;
    }

    private void restoreMediaProjection(int resultCode, String serializedData) {
        try {
            String[] parts = serializedData.split("\\|", 2);
            if (parts.length < 2) {
                Log.e(TAG, "Invalid serialized data format");
                return;
            }
            int uid = Integer.parseInt(parts[0]);
            String uriString = parts[1];

            Intent data = new Intent();
            data.setData(android.net.Uri.parse(uriString));
            data.putExtra("android.content.pm.PACKAGE_NAME", getPackageName());
            data.putExtra("android.content.pm.PROJECTION_SECONDARY_UID", uid);

            setupMediaProjection(resultCode, data);
        } catch (Exception e) {
            Log.e(TAG, "Failed to restore MediaProjection", e);
            showToast("⚠️ 无法恢复屏幕投影权限，请重新授权");
            getSharedPreferences("S_masterPrefs", MODE_PRIVATE).edit()
                    .putBoolean("media_projection_granted", false).apply();
        }
    }

    private void setupMediaProjection(int resultCode, Intent data) {
        try {
            MediaProjectionManager pm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            mediaProjection = pm.getMediaProjection(resultCode, data);
            if (mediaProjection != null) {
                mediaProjection.registerCallback(projectionCallback, backgroundHandler);
                setupVirtualDisplay();
            } else {
                showToast("⚠️ 无法获取屏幕投影权限，请重新授权");
                getSharedPreferences("S_masterPrefs", MODE_PRIVATE).edit()
                        .putBoolean("media_projection_granted", false).apply();
                stopSelf();
            }
        } catch (SecurityException e) {
            Log.e(TAG, "MediaProjection security error", e);
            showToast("⚠️ 屏幕投影权限已失效，请重新授权");
            getSharedPreferences("S_masterPrefs", MODE_PRIVATE).edit()
                    .putBoolean("media_projection_granted", false).apply();
            stopSelf();
        } catch (Exception e) {
            Log.e(TAG, "MediaProjection setup error", e);
            showToast("⚠️ 投影初始化失败：" + e.getMessage());
            stopSelf();
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
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image != null && pendingCapture && !isAnalyzing) {
                    pendingCapture = false;
                    processScreenshot(image);
                }
            } catch (Exception e) {
                Log.e(TAG, "Image available error", e);
            } finally {
                if (image != null) {
                    try { image.close(); } catch (Exception ignored) {}
                }
            }
        }, backgroundHandler);

        virtualDisplay = mediaProjection.createVirtualDisplay(
                "S master Capture", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, backgroundHandler);
    }

    private void takeScreenshot() {
        if (isAnalyzing) {
            showToast("⏳ 正在分析中，请稍后再试");
            return;
        }
        if (imageReader == null || mediaProjection == null) {
            showToast("⚠️ 屏幕投影未就绪，请重新启动服务");
            stopSelf();
            return;
        }
        pendingCapture = true;
    }

    private void processScreenshot(Image image) {
        Bitmap bitmap = imageToBitmap(image);
        if (bitmap == null) return;

        isAnalyzing = true;
        updateNotification();

        if (aiService.hasApiKey()) {
            sendSuggestion("🤖 正在分析...");

            aiService.analyzeScreenshot(bitmap, new AIService.AiCallback() {
                @Override
                public void onResult(String analysis, String suggestion) {
                    String fullText = buildResultText(analysis, suggestion);
                    lastResultText = fullText;
                    lastResultLabel = suggestion.length() > 40 ? suggestion.substring(0, 40) + "..." : suggestion;
                    sendSuggestion(fullText);
                    isAnalyzing = false;
                    updateNotification();
                }

                @Override
                public void onError(String error) {
                    String msg = "NO_API_KEY".equals(error)
                            ? "⚠️ 请先在设置中填写 API Key"
                            : "⚠️ 分析失败：" + error;
                    sendSuggestion(msg);
                    isAnalyzing = false;
                    updateNotification();
                }
            });
        } else {
            sendSuggestion("⚠️ 请先在设置中填写 API Key");
            isAnalyzing = false;
            updateNotification();
        }
        bitmap.recycle();
    }

    private String buildResultText(String analysis, String suggestion) {
        String r = "";
        if (!analysis.isEmpty()) r += "📊 " + analysis + "\n\n";
        r += "💡 " + suggestion;
        return r;
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
        try {
            sendBroadcast(new Intent("com.example.s_master.SUGGESTION")
                    .putExtra("suggestion", suggestion));
        } catch (Exception e) {
            Log.e(TAG, "sendSuggestion error", e);
        }
    }

    @Override
    public void onDestroy() {
        try { unregisterReceiver(actionReceiver); } catch (Exception e) {}

        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            backgroundThread = null;
        }
        if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
        if (imageReader != null) { imageReader.close(); imageReader = null; }
        if (mediaProjection != null) { mediaProjection.stop(); mediaProjection = null; }

        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
