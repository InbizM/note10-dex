package com.dex.note10;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Rational;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity implements StreamManager.Listener {
    private static final String TAG = "Note10MainActivity";

    private AspectRatioFrameLayout aspectFrame;
    private SurfaceView surfaceView;
    private LinearLayout layoutStatus;
    private TextView textStatus;

    private ControlSender controlSender;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Boolean currentLandscape = null;
    private boolean isRelaunching = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (getIntent().hasExtra("isLandscape")) {
            currentLandscape = getIntent().getBooleanExtra("isLandscape", false);
        }

        setContentView(R.layout.activity_main);

        aspectFrame = findViewById(R.id.aspect_frame);
        surfaceView = findViewById(R.id.surface_view);
        layoutStatus = findViewById(R.id.layout_status);
        textStatus = findViewById(R.id.text_status);

        StreamManager streamMgr = StreamManager.getInstance();
        controlSender = streamMgr.getControlSender();
        aspectFrame.setControlSender(controlSender);

        // Pre-configure aspect ratio with last known video dimensions
        aspectFrame.setVideoSize(streamMgr.getLastWidth(), streamMgr.getLastHeight());

        surfaceView.setClickable(false);
        surfaceView.setFocusable(true);

        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                // Connect or rebind to existing stream without interrupting connection/audio
                StreamManager.getInstance().startOrRebind(
                        MainActivity.this, holder.getSurface(), MainActivity.this);
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                StreamManager.getInstance().setListener(null);
            }
        });
    }

    private void enterPipMode() {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder();
                int rw = controlSender.getRemoteWidth();
                int rh = controlSender.getRemoteHeight();
                if (rw <= 0 || rh <= 0) {
                    rw = 1080;
                    rh = 2280;
                }
                float ratio = (float) rw / (float) rh;
                if (ratio < 0.41841f) {
                    builder.setAspectRatio(new Rational(1000, 2390));
                } else if (ratio > 2.39f) {
                    builder.setAspectRatio(new Rational(2390, 1000));
                } else {
                    builder.setAspectRatio(new Rational(rw, rh));
                }
                enterPictureInPictureMode(builder.build());
            } catch (Exception ignored) {
                enterPictureInPictureMode();
            }
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (!isRelaunching) {
            enterPipMode();
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        if (isInPictureInPictureMode) {
            layoutStatus.setVisibility(View.GONE);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    @Override
    public void onStatusUpdate(String status) {
        mainHandler.post(() -> {
            textStatus.setText(status);
            layoutStatus.setVisibility(View.VISIBLE);
        });
    }

    @Override
    public void onConnected(String deviceName, int width, int height) {
        mainHandler.post(() -> {
            aspectFrame.setVideoSize(width, height);
            layoutStatus.setVisibility(View.GONE);
            if (currentLandscape == null) {
                currentLandscape = (width > height);
            }
        });
    }

    @Override
    public void onVideoSizeChanged(int width, int height) {
        mainHandler.post(() -> onVideoSize(width, height));
    }

    private void onVideoSize(int w, int h) {
        aspectFrame.setVideoSize(w, h);
        boolean landscape = (w > h);

        if (currentLandscape == null) {
            currentLandscape = landscape;
            return;
        }
        if (currentLandscape == landscape) return;
        currentLandscape = landscape;

        // Intento 1: pedir orientación
        try {
            setRequestedOrientation(landscape
                    ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        } catch (Exception ignored) {}

        // Intento 2: relanzar con nuevos bounds que coinciden exactamente con la pantalla del teléfono
        relaunchWithBounds(w, h);
    }

    /**
     * Calculates the exact window size (width and height) in pixels to match
     * the phone screen aspect ratio, including the Samsung DeX window title bar,
     * so the content area has ZERO black bars.
     */
    private int[] windowSizeFor(int videoW, int videoH) {
        try {
            Rect screen = getWindowManager().getMaximumWindowMetrics().getBounds();
            float maxW = screen.width() * 0.85f;
            float maxH = screen.height() * 0.85f;
            float density = getResources().getDisplayMetrics().density;
            // Height of the DeX window title bar (~36dp to 40dp)
            int titleBar = Math.round(36 * density);

            float scale = Math.min(maxW / videoW, (maxH - titleBar) / videoH);
            int winW = Math.round(videoW * scale);
            int winH = Math.round(videoH * scale) + titleBar;
            return new int[] { winW, winH };
        } catch (Exception e) {
            // Fallback for default sizes
            float density = getResources().getDisplayMetrics().density;
            if (videoW > videoH) {
                return new int[] { Math.round(880 * density), Math.round(420 * density) };
            } else {
                return new int[] { Math.round(420 * density), Math.round(880 * density) };
            }
        }
    }

    private void relaunchWithBounds(int w, int h) {
        if (isFinishing() || isDestroyed()) return;
        try {
            isRelaunching = true;
            int[] winSize = windowSizeFor(w, h);
            Rect screen = getWindowManager().getMaximumWindowMetrics().getBounds();
            Rect cur = getWindowManager().getCurrentWindowMetrics().getBounds();

            int left = cur.left;
            int top = cur.top;
            if (left + winSize[0] > screen.right) {
                left = Math.max(0, screen.right - winSize[0] - 20);
            }
            if (top + winSize[1] > screen.bottom) {
                top = Math.max(0, screen.bottom - winSize[1] - 40);
            }
            Rect rect = new Rect(left, top, left + winSize[0], top + winSize[1]);

            ActivityOptions opts = ActivityOptions.makeBasic();
            opts.setLaunchBounds(rect);

            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            i.putExtra("isLandscape", (w > h));
            startActivity(i, opts.toBundle());
            finish();
        } catch (Exception e) {
            isRelaunching = false;
            Log.w(TAG, "relaunchWithBounds error: " + e.getMessage());
        }
    }

    @Override
    public void onDisconnected(String reason) {
        mainHandler.post(() -> {
            textStatus.setText(reason);
            layoutStatus.setVisibility(View.VISIBLE);
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.getRepeatCount() == 0) {
            controlSender.sendKeyClick(ControlSender.KEYCODE_BACK);
            return true;
        }
        controlSender.sendKeycode(keyCode, ControlSender.ACTION_DOWN);
        return true;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return true;
        }
        controlSender.sendKeycode(keyCode, ControlSender.ACTION_UP);
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        StreamManager.getInstance().setListener(null);
        // Only stop streaming if the user closed the window (not when relaunching for orientation)
        if (!isRelaunching && isFinishing()) {
            StreamManager.getInstance().stop();
        }
    }
}
