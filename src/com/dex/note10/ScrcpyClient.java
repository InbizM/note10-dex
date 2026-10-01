package com.dex.note10;

import android.content.Context;
import android.util.Base64;
import android.util.Log;
import android.view.Surface;
import com.tananaev.adblib.*;
import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Connects to scrcpy-server on a remote device.
 * Uses ADB only for setup (push files, launch processes).
 * Video and control data flows over DIRECT TCP for maximum throughput.
 *
 * Architecture:
 *   App --[TCP 27183]--> TcpBridge --[localabstract:scrcpy]--> scrcpy-server
 *   (Full WiFi speed)     (on device)                          (on device)
 */
public class ScrcpyClient {
    private static final String TAG = "ScrcpyClient";
    public static final String TARGET_IP = "192.168.18.123";
    public static final int TARGET_PORT = 5555;        // ADB port
    public static final int TCP_BRIDGE_PORT = 27183;   // Direct TCP video/control port
    public static final int TCP_AUDIO_PORT = 27184;    // Direct TCP audio port

    public interface Listener {
        void onStatusUpdate(String status);
        void onConnected(String deviceName, int width, int height);
        void onVideoSizeChanged(int width, int height);
        void onDisconnected(String reason);
    }

    private final Context context;
    private final Listener listener;
    private final ControlSender controlSender;

    private volatile boolean running = false;
    private Thread workerThread;
    private AdbConnection adbConn;
    private VideoDecoder videoDecoder;
    private AudioPlayer audioPlayer;
    private Surface surface;
    private Socket videoSocket;
    private Socket controlSocket;

    public ScrcpyClient(Context context, Listener listener, ControlSender controlSender) {
        this.context = context;
        this.listener = listener;
        this.controlSender = controlSender;
    }

    public synchronized boolean isRunning() {
        return running;
    }

    public synchronized void updateSurface(Surface newSurface) {
        this.surface = newSurface;
        if (videoDecoder != null) {
            videoDecoder.setSurface(newSurface);
        }
    }

    public synchronized void start(Surface surface) {
        if (running) {
            updateSurface(surface);
            return;
        }
        this.surface = surface;
        running = true;
        workerThread = new Thread(this::connectionLoop, "ScrcpyWorkerThread");
        workerThread.start();
    }

    public synchronized void stop() {
        running = false;
        controlSender.setControlOutput(null);
        if (audioPlayer != null) { audioPlayer.stop(); audioPlayer = null; }
        closeQuietly(videoSocket);
        closeQuietly(controlSocket);
        if (videoDecoder != null) { videoDecoder.stop(); videoDecoder = null; }
        if (adbConn != null) { try { adbConn.close(); } catch (Exception ignored) {} adbConn = null; }
        if (workerThread != null) { workerThread.interrupt(); workerThread = null; }
    }

    private void connectionLoop() {
        while (running) {
            try {
                // ============ PHASE 1: ADB setup ============
                listener.onStatusUpdate("Conectando ADB...");
                AdbCrypto crypto = getOrCreateCrypto(context);

                Socket adbSocket = new Socket(TARGET_IP, TARGET_PORT);
                adbSocket.setTcpNoDelay(true);
                adbConn = AdbConnection.create(adbSocket, crypto);
                adbConn.connect(15000, TimeUnit.MILLISECONDS, false);

                // Kill old processes
                listener.onStatusUpdate("Preparando servidor...");
                shellExec(adbConn, "pkill -f tcpbridge 2>/dev/null; pkill -f scrcpy 2>/dev/null; sleep 0.5");

                // Wake up Note 10 screen, unlock keyguard, and keep display always ON
                shellExec(adbConn, "input keyevent 224; input keyevent 82; wm dismiss-keyguard; svc power stayon true");

                // Ensure server and bridge jars are on the device
                listener.onStatusUpdate("Verificando archivos...");
                pushAssetIfNeeded(context, adbConn, "scrcpy-server.jar", "/data/local/tmp/scrcpy-server.jar");
                pushAssetIfNeeded(context, adbConn, "tcpbridge.jar", "/data/local/tmp/tcpbridge.jar");

                // Start scrcpy-server (creates localabstract:scrcpy)
                listener.onStatusUpdate("Iniciando servidor...");
                // stay_awake=true (arg 13) keeps device awake and displaying image continuously
                String scrcpyCmd = "shell:CLASSPATH=/data/local/tmp/scrcpy-server.jar " +
                        "app_process / com.genymobile.scrcpy.Server " +
                        "1.20 info 1600 10000000 60 -1 true - true true 0 false true - - false";
                AdbStream serverStream = adbConn.open(scrcpyCmd);

                // Drain server stdout
                drainStreamInBackground(serverStream, "ScrcpyServerDrain");
                Thread.sleep(800);

                // Start TCP bridge (TCP:27183 <-> localabstract:scrcpy)
                listener.onStatusUpdate("Iniciando puente TCP...");
                String bridgeCmd = "shell:CLASSPATH=/data/local/tmp/tcpbridge.jar " +
                        "app_process / bridge.TcpBridge " + TCP_BRIDGE_PORT + " scrcpy";
                AdbStream bridgeStream = adbConn.open(bridgeCmd);
                drainStreamInBackground(bridgeStream, "TcpBridgeDrain");
                Thread.sleep(500);

                // ============ PHASE 2: Direct TCP connection ============
                // Now connect directly to the device via TCP — NO ADB overhead!
                listener.onStatusUpdate("Conectando video directo...");

                // Video connection (first)
                videoSocket = new Socket(TARGET_IP, TCP_BRIDGE_PORT);
                videoSocket.setTcpNoDelay(true);
                videoSocket.setReceiveBufferSize(262144);
                BufferedInputStream videoIn = new BufferedInputStream(videoSocket.getInputStream(), 131072);

                // 1. Read dummy byte
                int dummy = videoIn.read();
                if (dummy < 0) throw new IOException("No dummy byte received");

                // Control connection (second)
                listener.onStatusUpdate("Conectando control...");
                controlSocket = new Socket(TARGET_IP, TCP_BRIDGE_PORT);
                controlSocket.setTcpNoDelay(true);
                controlSender.setControlOutput(new BufferedOutputStream(controlSocket.getOutputStream(), 4096));

                // 2. Read 64 bytes device name
                byte[] devNameBytes = new byte[64];
                readFully(videoIn, devNameBytes, 0, 64);
                String deviceName = new String(devNameBytes, StandardCharsets.UTF_8).trim();

                // 3. Read 4 bytes resolution
                byte[] resBytes = new byte[4];
                readFully(videoIn, resBytes, 0, 4);
                int width = ((resBytes[0] & 0xFF) << 8) | (resBytes[1] & 0xFF);
                int height = ((resBytes[2] & 0xFF) << 8) | (resBytes[3] & 0xFF);

                Log.i(TAG, "Connected via TCP: " + deviceName + " " + width + "x" + height);
                controlSender.setDeviceSize(width, height);
                listener.onConnected(deviceName, width, height);

                // 4. Start hardware video decoder with adaptive resolution listener
                if (videoDecoder != null) videoDecoder.stop();
                videoDecoder = new VideoDecoder(surface, width, height, (newW, newH) -> {
                    controlSender.setDeviceSize(newW, newH);
                    listener.onVideoSizeChanged(newW, newH);
                });
                videoDecoder.start();

                // 5. Start real-time audio playback
                if (audioPlayer != null) audioPlayer.stop();
                audioPlayer = new AudioPlayer(TARGET_IP, TCP_AUDIO_PORT);
                audioPlayer.start();

                // 6. Read and feed video frames — at full TCP speed!
                byte[] header = new byte[12];
                while (running) {
                    readFully(videoIn, header, 0, 12);
                    ByteBuffer hb = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
                    long ptsAndFlags = hb.getLong();
                    int packetSize = hb.getInt();

                    if (packetSize <= 0 || packetSize > 5 * 1024 * 1024) {
                        throw new IOException("Bad packet size: " + packetSize);
                    }

                    boolean isConfig = (ptsAndFlags & (1L << 63)) != 0;
                    boolean isKeyFrame = (ptsAndFlags & (1L << 62)) != 0;
                    long pts = ptsAndFlags & (~(3L << 62));

                    byte[] packetData = new byte[packetSize];
                    readFully(videoIn, packetData, 0, packetSize);
                    videoDecoder.feedPacket(packetData, pts, isConfig, isKeyFrame);
                }
            } catch (Exception e) {
                Log.e(TAG, "Connection error", e);
                controlSender.setControlOutput(null);
                if (audioPlayer != null) { audioPlayer.stop(); audioPlayer = null; }
                if (running) {
                    listener.onDisconnected("Reconectando...");
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) { break; }
                }
            } finally {
                controlSender.setControlOutput(null);
                if (audioPlayer != null) { audioPlayer.stop(); audioPlayer = null; }
                closeQuietly(videoSocket); videoSocket = null;
                closeQuietly(controlSocket); controlSocket = null;
                if (videoDecoder != null) { videoDecoder.stop(); videoDecoder = null; }
                if (adbConn != null) { try { adbConn.close(); } catch (Exception ignored) {} adbConn = null; }
            }
        }
    }

    // === Helper methods ===

    private static void readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int n = 0;
        while (n < len) {
            int count = in.read(buf, off + n, len - n);
            if (count <= 0) throw new EOFException("Stream ended");
            n += count;
        }
    }

    private void shellExec(AdbConnection conn, String cmd) {
        try {
            AdbStream s = conn.open("shell:" + cmd + "; echo __DONE__");
            AdbInputStream ain = new AdbInputStream(s);
            byte[] buf = new byte[256];
            StringBuilder sb = new StringBuilder();
            int r;
            while ((r = ain.read(buf, 0, buf.length)) > 0) {
                sb.append(new String(buf, 0, r));
                if (sb.toString().contains("__DONE__")) break;
            }
        } catch (Exception e) {
            Log.w(TAG, "shellExec error: " + e.getMessage());
        }
    }

    private void drainStreamInBackground(AdbStream stream, String name) {
        Thread t = new Thread(() -> {
            try {
                AdbInputStream sin = new AdbInputStream(stream);
                byte[] sbuf = new byte[256];
                while (sin.read(sbuf, 0, sbuf.length) > 0) { /* discard */ }
            } catch (Exception ignored) {}
        }, name);
        t.setDaemon(true);
        t.start();
    }

    private void pushAssetIfNeeded(Context context, AdbConnection conn, String assetName, String remotePath) throws Exception {
        byte[] assetBytes;
        try (InputStream is = context.getAssets().open(assetName);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] b = new byte[4096];
            int r;
            while ((r = is.read(b)) > 0) baos.write(b, 0, r);
            assetBytes = baos.toByteArray();
        }

        // Check if file already exists with correct size
        try {
            AdbStream checkStream = conn.open("shell:wc -c < " + remotePath + " 2>/dev/null || echo 0");
            AdbInputStream checkIn = new AdbInputStream(checkStream);
            byte[] outBuf = new byte[64];
            StringBuilder sb = new StringBuilder();
            int r;
            while ((r = checkIn.read(outBuf, 0, outBuf.length)) > 0) {
                sb.append(new String(outBuf, 0, r));
            }
            String sizeStr = sb.toString().trim();
            if (sizeStr.equals(String.valueOf(assetBytes.length))) {
                Log.i(TAG, assetName + " already present (" + assetBytes.length + " bytes)");
                return;
            }
        } catch (Exception ignored) {}

        Log.i(TAG, "Pushing " + assetName + " (" + assetBytes.length + " bytes)...");
        String b64 = Base64.encodeToString(assetBytes, Base64.NO_WRAP);
        byte[] b64Bytes = b64.getBytes(StandardCharsets.UTF_8);

        AdbStream pushStream = conn.open("shell:base64 -d > " + remotePath);
        int offset = 0;
        while (offset < b64Bytes.length) {
            int chunk = Math.min(2048, b64Bytes.length - offset);
            byte[] slice = Arrays.copyOfRange(b64Bytes, offset, offset + chunk);
            pushStream.write(slice);
            offset += chunk;
        }
        pushStream.close();
        Thread.sleep(300);

        shellExec(conn, "chmod 755 " + remotePath);
    }

    private AdbCrypto getOrCreateCrypto(Context context) throws Exception {
        File keyFile = new File(context.getFilesDir(), "adbkey");
        File pubFile = new File(context.getFilesDir(), "adbkey.pub");
        AdbBase64 base64 = new AndroidAdbBase64();

        if (!keyFile.exists() || !pubFile.exists()) {
            try (InputStream inKey = context.getAssets().open("adbkey");
                 InputStream inPub = context.getAssets().open("adbkey.pub");
                 FileOutputStream outKey = new FileOutputStream(keyFile);
                 FileOutputStream outPub = new FileOutputStream(pubFile)) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = inKey.read(buf)) > 0) outKey.write(buf, 0, n);
                while ((n = inPub.read(buf)) > 0) outPub.write(buf, 0, n);
            } catch (Exception e) {
                Log.w(TAG, "Asset key copy failed, generating new pair", e);
            }
        }

        if (keyFile.exists() && pubFile.exists()) {
            try { return AdbCrypto.loadAdbKeyPair(base64, keyFile, pubFile); }
            catch (Exception e) { Log.e(TAG, "Failed to load key, regenerating", e); }
        }

        AdbCrypto crypto = AdbCrypto.generateAdbKeyPair(base64);
        crypto.saveAdbKeyPair(keyFile, pubFile);
        return crypto;
    }

    private static void closeQuietly(Socket s) {
        if (s != null) { try { s.close(); } catch (Exception ignored) {} }
    }
}
