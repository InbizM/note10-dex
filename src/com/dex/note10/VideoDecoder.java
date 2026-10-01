package com.dex.note10;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.view.Surface;
import java.nio.ByteBuffer;

/**
 * Ultra-low latency hardware H.264 decoder for scrcpy video streaming.
 * Configured with realtime low-latency decoder hints and adaptive resolution support.
 * Automatically detects dynamic resolution changes (orientation flips) via MediaCodec.
 */
public class VideoDecoder {
    private static final String TAG = "VideoDecoder";
    private static final String MIME_TYPE = "video/avc";

    public interface Listener {
        void onVideoSizeChanged(int width, int height);
    }

    private MediaCodec codec;
    private Surface surface;
    private final int initialWidth;
    private final int initialHeight;
    private final Listener listener;
    private volatile boolean running = false;
    private Thread renderThread;

    public VideoDecoder(Surface surface, int width, int height, Listener listener) {
        this.surface = surface;
        this.initialWidth = width;
        this.initialHeight = height;
        this.listener = listener;
    }

    public synchronized void setSurface(Surface newSurface) {
        this.surface = newSurface;
        if (codec != null && running && newSurface != null && newSurface.isValid()) {
            if (Build.VERSION.SDK_INT >= 23) {
                try {
                    codec.setOutputSurface(newSurface);
                    Log.i(TAG, "setOutputSurface successfully attached to new Surface");
                    return;
                } catch (Exception e) {
                    Log.w(TAG, "setOutputSurface failed, restarting codec: " + e.getMessage());
                }
            }
            try {
                stop();
                start();
            } catch (Exception e) {
                Log.e(TAG, "Restart decoder error: " + e.getMessage());
            }
        }
    }

    public synchronized void start() throws Exception {
        if (running) return;

        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, initialWidth, initialHeight);

        // Low latency hints for Android MediaCodec
        if (Build.VERSION.SDK_INT >= 30) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        }
        if (Build.VERSION.SDK_INT >= 23) {
            format.setInteger(MediaFormat.KEY_PRIORITY, 0); // 0 = realtime priority
        }

        // Allow adaptive resolution switching between portrait and landscape without decoder re-init
        int maxDim = Math.max(initialWidth, initialHeight);
        format.setInteger(MediaFormat.KEY_MAX_WIDTH, maxDim);
        format.setInteger(MediaFormat.KEY_MAX_HEIGHT, maxDim);

        // Vendor-specific low latency hints (Qualcomm / Samsung Exynos)
        try {
            format.setInteger("vendor.low-latency.enable", 1);
            format.setInteger("vendor.qti-ext-dec-low-latency.enable", 1);
        } catch (Exception ignored) {}

        codec = MediaCodec.createDecoderByType(MIME_TYPE);
        codec.configure(format, surface, null, 0);
        codec.start();
        running = true;

        // Render thread: drains decoded frames immediately and sends to Surface
        renderThread = new Thread(this::renderLoop, "VideoRenderThread");
        renderThread.setPriority(Thread.MAX_PRIORITY);
        renderThread.start();

        Log.i(TAG, "VideoDecoder started (" + initialWidth + "x" + initialHeight + ") with adaptive resolution");
    }

    private void renderLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running) {
            try {
                if (codec == null) break;
                int outIndex = codec.dequeueOutputBuffer(info, 2000);
                if (outIndex >= 0) {
                    codec.releaseOutputBuffer(outIndex, true);
                    // DRAIN any additional ready buffers immediately to prevent lag buildup!
                    while (running) {
                        outIndex = codec.dequeueOutputBuffer(info, 0);
                        if (outIndex >= 0) {
                            codec.releaseOutputBuffer(outIndex, true);
                        } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            handleFormatChanged();
                        } else {
                            break;
                        }
                    }
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    handleFormatChanged();
                }
            } catch (IllegalStateException e) {
                if (!running) break;
            } catch (Exception e) {
                Log.e(TAG, "renderLoop exception", e);
            }
        }
    }

    private void handleFormatChanged() {
        try {
            if (codec == null) return;
            MediaFormat newFormat = codec.getOutputFormat();
            int newW = newFormat.getInteger(MediaFormat.KEY_WIDTH);
            int newH = newFormat.getInteger(MediaFormat.KEY_HEIGHT);

            // Some hardware decoders report crop bounds
            if (newFormat.containsKey("crop-left") && newFormat.containsKey("crop-right")) {
                int left = newFormat.getInteger("crop-left");
                int right = newFormat.getInteger("crop-right");
                newW = right - left + 1;
            }
            if (newFormat.containsKey("crop-top") && newFormat.containsKey("crop-bottom")) {
                int top = newFormat.getInteger("crop-top");
                int bottom = newFormat.getInteger("crop-bottom");
                newH = bottom - top + 1;
            }

            Log.i(TAG, "Stream resolution changed: " + newW + "x" + newH);
            if (listener != null && newW > 0 && newH > 0) {
                listener.onVideoSizeChanged(newW, newH);
            }
        } catch (Exception e) {
            Log.w(TAG, "handleFormatChanged error: " + e.getMessage());
        }
    }

    public void feedPacket(byte[] data, long pts, boolean isConfig, boolean isKeyFrame) {
        if (!running || codec == null) return;
        try {
            int inIndex = codec.dequeueInputBuffer(10000); // 10ms max wait
            if (inIndex >= 0) {
                ByteBuffer inBuf = codec.getInputBuffer(inIndex);
                if (inBuf != null) {
                    inBuf.clear();
                    if (data.length <= inBuf.capacity()) {
                        inBuf.put(data);
                        int flags = 0;
                        if (isConfig) flags |= MediaCodec.BUFFER_FLAG_CODEC_CONFIG;
                        if (isKeyFrame) flags |= MediaCodec.BUFFER_FLAG_KEY_FRAME;
                        codec.queueInputBuffer(inIndex, 0, data.length, pts, flags);
                    } else {
                        Log.w(TAG, "Frame size " + data.length + " exceeds buffer capacity " + inBuf.capacity());
                    }
                }
            }
        } catch (IllegalStateException e) {
            // Codec restarting or stopped
        } catch (Exception e) {
            Log.e(TAG, "feedPacket error", e);
        }
    }

    public synchronized void stop() {
        running = false;
        if (renderThread != null) {
            renderThread.interrupt();
            try {
                renderThread.join(500);
            } catch (InterruptedException ignored) {}
            renderThread = null;
        }
        if (codec != null) {
            try { codec.stop(); } catch (Exception ignored) {}
            try { codec.release(); } catch (Exception ignored) {}
            codec = null;
        }
        Log.i(TAG, "VideoDecoder stopped");
    }
}
