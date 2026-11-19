package com.dosigom.bosstimer;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static WeakReference<MainActivity> instanceRef;

    private WebView webView;
    private TextToSpeech tts;
    private MediaProjectionManager projectionManager;

    private final ActivityResultLauncher<Intent> screenCaptureLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Intent serviceIntent = new Intent(this, ScreenOcrService.class);
                    serviceIntent.setAction(ScreenOcrService.ACTION_START);
                    serviceIntent.putExtra(ScreenOcrService.EXTRA_RESULT_CODE, result.getResultCode());
                    serviceIntent.putExtra(ScreenOcrService.EXTRA_RESULT_DATA, result.getData());

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent);
                    } else {
                        startService(serviceIntent);
                    }
                    Toast.makeText(this, "화면 자동감지(OCR)가 시작되었습니다.", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "화면 캡처 권한이 취소되었습니다.", Toast.LENGTH_SHORT).show();
                    notifyOcrStateToWeb(false, "권한 취소됨");
                }
            });

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instanceRef = new WeakReference<>(this);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        // Android 13+ 알림 권한 요청 (포그라운드 서비스 표시용)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2001);
            }
        }

        webView = new WebView(this);
        setContentView(webView);

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.KOREAN);
                tts.setPitch(1.6f);
                tts.setSpeechRate(1.1f);
            }
        });

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                notifyOcrStateToWeb(ScreenOcrService.isRunning, "");
            }
        });
        webView.setWebChromeClient(new WebChromeClient());

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        webView.loadUrl("file:///android_asset/index.html");
    }

    public static void sendOcrTextToWeb(String detectedText) {
        if (instanceRef == null) return;
        MainActivity activity = instanceRef.get();
        if (activity == null || activity.webView == null) return;

        activity.runOnUiThread(() -> {
            try {
                String quoted = JSONObject.quote(detectedText);
                String js = "if(window.handleOcrDetectedText){ window.handleOcrDetectedText(" + quoted + "); }";
                activity.webView.evaluateJavascript(js, null);
            } catch (Exception ignored) {}
        });
    }

    public static void notifyOcrStateToWeb(boolean running, String msg) {
        if (instanceRef == null) return;
        MainActivity activity = instanceRef.get();
        if (activity == null || activity.webView == null) return;

        activity.runOnUiThread(() -> {
            try {
                String quotedMsg = JSONObject.quote(msg == null ? "" : msg);
                String js = "if(window.onNativeOcrStatusChanged){ window.onNativeOcrStatusChanged(" + running + ", " + quotedMsg + "); }";
                activity.webView.evaluateJavascript(js, null);
            } catch (Exception ignored) {}
        });
    }

    public class AndroidBridge {

        @JavascriptInterface
        public void toggleScreenOcr() {
            runOnUiThread(() -> {
                if (ScreenOcrService.isRunning) {
                    Intent stopIntent = new Intent(MainActivity.this, ScreenOcrService.class);
                    stopIntent.setAction(ScreenOcrService.ACTION_STOP);
                    startService(stopIntent);
                    Toast.makeText(MainActivity.this, "화면 자동감지(OCR)를 중지했습니다.", Toast.LENGTH_SHORT).show();
                } else {
                    if (projectionManager != null) {
                        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent());
                    }
                }
            });
        }

        @JavascriptInterface
        public void requestIgnoreBatteryOptimizations() {
            runOnUiThread(() -> {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                            intent.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        } else {
                            Toast.makeText(MainActivity.this, "이미 배터리 최적화에서 제외되어 있습니다.", Toast.LENGTH_SHORT).show();
                        }
                    }
                } catch (Exception e) {
                    Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                    startActivity(intent);
                }
            });
        }

        @JavascriptInterface
        public void requestOverlayPermission() {
            runOnUiThread(() -> {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(MainActivity.this)) {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    } else {
                        Toast.makeText(MainActivity.this, "이미 다른 앱 위에 표시 권한이 허용되어 있습니다.", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    openAppSettings();
                }
            });
        }

        @JavascriptInterface
        public void openAppSettings() {
            runOnUiThread(() -> {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            });
        }

        @JavascriptInterface
        public void speak(String text) {
            runOnUiThread(() -> {
                if (tts != null) {
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "boss_alert");
                }
            });
        }

        @JavascriptInterface
        public void copyToClipboard(String text) {
            runOnUiThread(() -> {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    ClipData clip = ClipData.newPlainText("boss_timer", text);
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(MainActivity.this, "복사됨: " + text, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
