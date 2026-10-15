package com.dex.note10;

import android.content.Context;
import android.util.Log;
import android.view.Surface;

/**
 * Singleton stream manager that keeps the WiFi ADB / scrcpy streaming connection
 * alive across Activity recreations and bounds relaunch in Samsung DeX.
 */
public class StreamManager implements ScrcpyClient.Listener {
    private static final String TAG = "StreamManager";
    private static volatile StreamManager instance;

    public interface Listener {
        void onStatusUpdate(String status);
        void onConnected(String deviceName, int width, int height);
        void onVideoSizeChanged(int width, int height);
        void onDisconnected(String reason);
    }

    private final ControlSender controlSender = new ControlSender();
    private ScrcpyClient scrcpyClient;
    private Listener currentListener;

    private int lastWidth = 1080;
    private int lastHeight = 2280;
    private String lastDeviceName = "Note 10";
    private boolean connected = false;
    private String lastStatus = "Conectando...";
    String cachedIp = null; // last working IP, skip scan on reconnect

    private StreamManager() {}

    public static synchronized StreamManager getInstance() {
        if (instance == null) {
            instance = new StreamManager();
        }
        return instance;
    }

    public ControlSender getControlSender() {
        return controlSender;
    }

    public int getLastWidth() {
        return lastWidth;
    }

    public int getLastHeight() {
        return lastHeight;
    }

    public boolean isConnected() {
        return connected;
    }

    public String getLastDeviceName() {
        return lastDeviceName;
    }

    public synchronized void setListener(Listener listener) {
        this.currentListener = listener;
        if (listener != null) {
            if (connected) {
                listener.onConnected(lastDeviceName, lastWidth, lastHeight);
            } else if (lastStatus != null) {
                listener.onStatusUpdate(lastStatus);
            }
        }
    }

    public synchronized void startOrRebind(Context context, Surface surface, Listener listener) {
        this.currentListener = listener;

        if (scrcpyClient != null && scrcpyClient.isRunning()) {
            Log.i(TAG, "Rebinding existing scrcpy session to new Surface");
            scrcpyClient.updateSurface(surface);
            if (listener != null && connected) {
                listener.onConnected(lastDeviceName, lastWidth, lastHeight);
            }
            return;
        }

        Log.i(TAG, "Starting new scrcpy session");
        connected = false;
        scrcpyClient = new ScrcpyClient(context.getApplicationContext(), this, controlSender, cachedIp);
        scrcpyClient.start(surface);
    }

    public synchronized void stop() {
        if (scrcpyClient != null) {
            scrcpyClient.stop();
            scrcpyClient = null;
        }
        connected = false;
    }

    @Override
    public void onStatusUpdate(String status) {
        this.lastStatus = status;
        Listener l = currentListener;
        if (l != null) l.onStatusUpdate(status);
    }

    @Override
    public void onConnected(String deviceName, int width, int height) {
        this.lastDeviceName = deviceName;
        this.lastWidth = width;
        this.lastHeight = height;
        this.connected = true;
        if (scrcpyClient != null) cachedIp = scrcpyClient.getTargetIp();
        Listener l = currentListener;
        if (l != null) l.onConnected(deviceName, width, height);
    }

    @Override
    public void onVideoSizeChanged(int width, int height) {
        this.lastWidth = width;
        this.lastHeight = height;
        Listener l = currentListener;
        if (l != null) l.onVideoSizeChanged(width, height);
    }

    @Override
    public void onDisconnected(String reason) {
        this.connected = false;
        this.lastStatus = reason;
        Listener l = currentListener;
        if (l != null) l.onDisconnected(reason);
    }
}
