package com.dosigom.bosstimer;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
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
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;

import java.nio.ByteBuffer;

public class ScreenOcrService extends Service {

    public static final String ACTION_START = "com.dosigom.bosstimer.ACTION_START_OCR";
    public static final String ACTION_STOP = "com.dosigom.bosstimer.ACTION_STOP_OCR";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    private static final String CHANNEL_ID = "boss_ocr_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final long OCR_INTERVAL_MS = 1800L; // 1.8초 간격 스캔

    public static boolean isRunning = false;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private TextRecognizer textRecognizer;

    private HandlerThread bgThread;
    private Handler bgHandler;

    private int screenWidth;
    private int screenHeight;
    private int screenDensity;

    private boolean isProcessingFrame = false;

    private final Runnable scanTask = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;
            captureAndRecognize();
            if (bgHandler != null) {
                bgHandler.postDelayed(this, OCR_INTERVAL_MS);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        bgThread = new HandlerThread("BossOcrThread");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());

        textRecognizer = TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());

        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        if (wm != null) {
            wm.getDefaultDisplay().getRealMetrics(metrics);
            screenWidth = metrics.widthPixels;
            screenHeight = metrics.heightPixels;
            screenDensity = metrics.densityDpi;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopOcrSession();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
            Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);

            if (resultCode != Activity.RESULT_OK || resultData == null) {
                stopSelf();
                return START_NOT_STICKY;
            }

            Notification notification = buildNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }

            startMediaProjection(resultCode, resultData);
        }

        return START_NOT_STICKY;
    }

    private void startMediaProjection(int resultCode, Intent resultData) {
        try {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (mpm == null) return;

            mediaProjection = mpm.getMediaProjection(resultCode, resultData);
            if (mediaProjection == null) return;

            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    stopOcrSession();
                    stopSelf();
                }
            }, bgHandler);

            // 해상도를 절반으로 축소하여 캡처 (성능 최적화 및 발열 감소)
            int captureWidth = Math.max(360, screenWidth / 2);
            int captureHeight = Math.max(640, screenHeight / 2);

            imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2);
            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "BossTimerOcrDisplay",
                    captureWidth,
                    captureHeight,
                    screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null,
                    bgHandler
            );

            isRunning = true;
            MainActivity.notifyOcrStateToWeb(true, "화면 자동감지 실행 중");
            bgHandler.postDelayed(scanTask, 1000L);

        } catch (Exception e) {
            Log.e("BossOcrService", "MediaProjection 시작 오류", e);
            stopOcrSession();
            stopSelf();
        }
    }

    private void captureAndRecognize() {
        if (isProcessingFrame || imageReader == null || textRecognizer == null) return;

        Image image = null;
        try {
            image = imageReader.acquireLatestImage();
            if (image == null) return;

            isProcessingFrame = true;

            int width = image.getWidth();
            int height = image.getHeight();
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * width;

            Bitmap fullBitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888
            );
            fullBitmap.copyPixelsFromBuffer(buffer);
            image.close();
            image = null;

            // 화면 하단 45% 영역(채팅창 영역)만 잘라서 OCR 수행 (속도 향상 및 오인식 방지)
            int cropY = (int) (height * 0.55f);
            int cropHeight = height - cropY;
            Bitmap croppedBitmap = Bitmap.createBitmap(fullBitmap, 0, cropY, width, cropHeight);
            fullBitmap.recycle();

            InputImage inputImage = InputImage.fromBitmap(croppedBitmap, 0);
            textRecognizer.process(inputImage)
                    .addOnSuccessListener(visionText -> {
                        croppedBitmap.recycle();
                        isProcessingFrame = false;

                        String rawText = visionText.getText();
                        if (rawText != null && !rawText.isEmpty()) {
                            // 줄바꿈을 공백으로 정리하여 웹뷰의 handleOcrDetectedText로 전달
                            String singleLine = rawText.replace("\n", " ").replace("\r", " ");
                            if (singleLine.contains("드랍") || singleLine.contains("용마왕") ||
                                singleLine.contains("늑대왕") || singleLine.contains("네루") ||
                                singleLine.contains("좀비왕")) {
                                MainActivity.sendOcrTextToWeb(singleLine);
                            }
                        }
                    })
                    .addOnFailureListener(e -> {
                        croppedBitmap.recycle();
                        isProcessingFrame = false;
                    });

        } catch (Exception e) {
            isProcessingFrame = false;
            if (image != null) {
                try { image.close(); } catch (Exception ignored) {}
            }
        }
    }

    private void stopOcrSession() {
        isRunning = false;
        if (bgHandler != null) {
            bgHandler.removeCallbacks(scanTask);
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        MainActivity.notifyOcrStateToWeb(false, "화면 자동감지 중지됨");
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(this, ScreenOcrService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("보스 젠 타이머 자동감지 실행 중")
                .setContentText("게임 화면의 보스 드랍 로그를 실시간 감지하고 있습니다.")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .addAction(android.R.drawable.ic_media_pause, "감지 중지", stopPendingIntent)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "보스 타이머 화면 자동감지",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("게임 채팅창 드랍 로그 자동 인식을 위한 백그라운드 알림");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onDestroy() {
        stopOcrSession();
        if (textRecognizer != null) {
            textRecognizer.close();
        }
        if (bgThread != null) {
            bgThread.quitSafely();
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
