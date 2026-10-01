package com.dex.note10;

import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Sends control packets to scrcpy-server via direct TCP stream.
 * All writes happen on a dedicated background thread to prevent UI freezes.
 * Touch MOVE events are throttled to max ~30/sec.
 */
public class ControlSender {
    private static final String TAG = "ControlSender";

    public static final int ACTION_DOWN = 0;
    public static final int ACTION_UP = 1;
    public static final int ACTION_MOVE = 2;

    public static final long POINTER_ID_MOUSE = -1L;
    public static final long POINTER_ID_GENERIC_FINGER = -2L;

    public static final int KEYCODE_HOME = 3;
    public static final int KEYCODE_BACK = 4;
    public static final int KEYCODE_POWER = 26;
    public static final int KEYCODE_APP_SWITCH = 187;

    private volatile OutputStream controlOut;
    private volatile int remoteWidth = 1080;
    private volatile int remoteHeight = 2280;

    // Background sending thread
    private final ArrayBlockingQueue<byte[]> sendQueue = new ArrayBlockingQueue<>(128);
    private volatile Thread senderThread;
    private volatile boolean senderRunning = false;

    // Throttle: min 8ms between MOVE events (~125fps smooth tracking)
    private long lastMoveTimestamp = 0;
    private static final long MOVE_THROTTLE_MS = 8;

    public void setControlOutput(OutputStream out) {
        this.controlOut = out;
        if (out != null) {
            startSenderThread();
        } else {
            stopSenderThread();
        }
    }

    public void setDeviceSize(int width, int height) {
        if (width > 0 && height > 0) {
            this.remoteWidth = width;
            this.remoteHeight = height;
        }
    }

    public int getRemoteWidth() { return remoteWidth; }
    public int getRemoteHeight() { return remoteHeight; }

    private void startSenderThread() {
        stopSenderThread();
        senderRunning = true;
        senderThread = new Thread(() -> {
            while (senderRunning) {
                try {
                    byte[] packet = sendQueue.take(); // blocks until available
                    OutputStream out = controlOut;
                    if (out != null) {
                        out.write(packet);
                        // Batch-write any other pending control packets
                        byte[] next;
                        while ((next = sendQueue.poll()) != null) {
                            out.write(next);
                        }
                        out.flush();
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "senderThread write error", e);
                }
            }
        }, "ControlSenderThread");
        senderThread.setPriority(Thread.MAX_PRIORITY);
        senderThread.setDaemon(true);
        senderThread.start();
    }

    private void stopSenderThread() {
        senderRunning = false;
        sendQueue.clear();
        if (senderThread != null) {
            senderThread.interrupt();
            senderThread = null;
        }
    }

    private void enqueue(byte[] packet) {
        // Non-blocking: if queue is full, drop the packet (better than freezing)
        sendQueue.offer(packet);
    }

    public void sendTouchEvent(MotionEvent event, int viewWidth, int viewHeight) {
        if (controlOut == null || viewWidth <= 0 || viewHeight <= 0) return;

        int actionMasked = event.getActionMasked();

        // Throttle MOVE events to avoid flooding
        if (actionMasked == MotionEvent.ACTION_MOVE) {
            long now = System.currentTimeMillis();
            if (now - lastMoveTimestamp < MOVE_THROTTLE_MS) {
                return; // skip this move
            }
            lastMoveTimestamp = now;
        }

        float eventX = event.getX();
        float eventY = event.getY();

        int remoteX = Math.max(0, Math.min(Math.round(eventX * remoteWidth / viewWidth), remoteWidth));
        int remoteY = Math.max(0, Math.min(Math.round(eventY * remoteHeight / viewHeight), remoteHeight));

        int action;
        short pressure;
        int buttons;

        boolean isMouse = (event.getSource() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE;
        long pointerId = isMouse ? POINTER_ID_MOUSE : POINTER_ID_GENERIC_FINGER;

        switch (actionMasked) {
            case MotionEvent.ACTION_DOWN:
                action = ACTION_DOWN;
                pressure = (short) 0xffff;
                buttons = isMouse ? 1 : 0;
                break;
            case MotionEvent.ACTION_MOVE:
                action = ACTION_MOVE;
                pressure = (short) 0xffff;
                buttons = isMouse ? 1 : 0;
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                action = ACTION_UP;
                pressure = 0;
                buttons = 0;
                break;
            default:
                return;
        }

        ByteBuffer buf = ByteBuffer.allocate(28).order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) 2);              // TypeInjectTOUCHEvent
        buf.put((byte) action);
        buf.putLong(pointerId);
        buf.putInt(remoteX);
        buf.putInt(remoteY);
        buf.putShort((short) remoteWidth);
        buf.putShort((short) remoteHeight);
        buf.putShort(pressure);
        buf.putInt(buttons);

        enqueue(buf.array());
    }

    public void sendScroll(int x, int y, int hScroll, int vScroll) {
        if (controlOut == null) return;
        ByteBuffer buf = ByteBuffer.allocate(21).order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) 3);
        buf.putInt(Math.max(0, x));
        buf.putInt(Math.max(0, y));
        buf.putShort((short) remoteWidth);
        buf.putShort((short) remoteHeight);
        buf.putInt(hScroll);
        buf.putInt(vScroll);
        enqueue(buf.array());
    }

    public void sendKeycode(int keyCode, int action) {
        if (controlOut == null) return;
        ByteBuffer buf = ByteBuffer.allocate(14).order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) 0);
        buf.put((byte) action);
        buf.putInt(keyCode);
        buf.putInt(0);
        buf.putInt(0);
        enqueue(buf.array());
    }

    public void sendKeyClick(int keyCode) {
        sendKeycode(keyCode, ACTION_DOWN);
        sendKeycode(keyCode, ACTION_UP);
    }
}
