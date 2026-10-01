package bridge;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Build;
import android.os.Looper;
import android.os.Process;
import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.*;
import java.util.Arrays;

/**
 * High-performance TCP-to-LocalAbstractSocket bridge for scrcpy video/control
 * and native low-latency system AudioServer using REMOTE_SUBMIX.
 * Runs on the remote device via app_process with shell permissions.
 */
public class TcpBridge {
    private static final int BUF_SIZE = 65536;
    private static final int DEFAULT_TCP_PORT = 27183;
    private static final int DEFAULT_AUDIO_PORT = 27184;

    public static void main(String[] args) {
        int port = DEFAULT_TCP_PORT;
        String abstractName = "scrcpy";
        int audioPort = DEFAULT_AUDIO_PORT;

        if (args.length >= 1) {
            try { port = Integer.parseInt(args[0]); } catch (NumberFormatException ignored) {}
        }
        if (args.length >= 2) {
            abstractName = args[1];
        }
        if (args.length >= 3) {
            try { audioPort = Integer.parseInt(args[2]); } catch (NumberFormatException ignored) {}
        }

        System.out.println("TcpBridge starting: Video/Control on " + port + " -> " + abstractName + ", Audio on " + audioPort);

        // Start Audio Server in background thread
        startAudioServer(audioPort);

        // Video / Control Server loop
        try {
            ServerSocket serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress("0.0.0.0", port));
            System.out.println("Listening for video/control on port " + port);

            int connNum = 0;
            while (true) {
                Socket tcpSocket = serverSocket.accept();
                tcpSocket.setTcpNoDelay(true);
                tcpSocket.setSendBufferSize(262144);
                tcpSocket.setReceiveBufferSize(262144);
                connNum++;
                System.out.println("TCP connection #" + connNum + " from " + tcpSocket.getRemoteSocketAddress());

                LocalSocket localSocket = new LocalSocket();
                localSocket.connect(new LocalSocketAddress(abstractName, LocalSocketAddress.Namespace.ABSTRACT));
                System.out.println("Connected to localabstract:" + abstractName + " for connection #" + connNum);

                InputStream tcpIn = tcpSocket.getInputStream();
                OutputStream tcpOut = tcpSocket.getOutputStream();
                InputStream localIn = localSocket.getInputStream();
                OutputStream localOut = localSocket.getOutputStream();

                final int num = connNum;
                Thread t2l = new Thread(() -> copyStream("TCP->Local#" + num, tcpIn, localOut), "T2L-" + num);
                Thread l2t = new Thread(() -> copyStream("Local->TCP#" + num, localIn, tcpOut), "L2T-" + num);
                t2l.setDaemon(true);
                l2t.setDaemon(true);
                t2l.start();
                l2t.start();
            }
        } catch (Exception e) {
            System.err.println("TcpBridge fatal error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void copyStream(String tag, InputStream in, OutputStream out) {
        try {
            byte[] buf = new byte[BUF_SIZE];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (Exception ignored) {
        }
        System.out.println(tag + " closed");
    }

    // ================= AUDIO STREAMING SERVER =================

    private static Context shellContext = null;

    private static synchronized Context getShellContext() {
        if (shellContext != null) return shellContext;
        try {
            if (Looper.myLooper() == null) {
                try {
                    Looper.prepareMainLooper();
                } catch (Exception ignored) {}
            }

            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object activityThread = null;
            try {
                Method currentActivityThread = activityThreadClass.getMethod("currentActivityThread");
                activityThread = currentActivityThread.invoke(null);
            } catch (Exception ignored) {}

            if (activityThread == null) {
                Constructor<?> atConstructor = activityThreadClass.getDeclaredConstructor();
                atConstructor.setAccessible(true);
                activityThread = atConstructor.newInstance();

                Field sCurrentActivityThread = activityThreadClass.getDeclaredField("sCurrentActivityThread");
                sCurrentActivityThread.setAccessible(true);
                sCurrentActivityThread.set(null, activityThread);

                Field mSystemThread = activityThreadClass.getDeclaredField("mSystemThread");
                mSystemThread.setAccessible(true);
                mSystemThread.setBoolean(activityThread, true);
            }

            Method getSystemContext = activityThreadClass.getMethod("getSystemContext");
            Context systemContext = (Context) getSystemContext.invoke(activityThread);

            shellContext = new ContextWrapper(systemContext) {
                @Override
                public String getPackageName() { return "com.android.shell"; }
                @Override
                public String getOpPackageName() { return "com.android.shell"; }
                @Override
                public AttributionSource getAttributionSource() {
                    return new AttributionSource.Builder(android.os.Process.SHELL_UID)
                            .setPackageName("com.android.shell")
                            .build();
                }
            };
        } catch (Exception e) {
            e.printStackTrace();
        }
        return shellContext;
    }

    private static void startAudioServer(int audioPort) {
        Thread audioServerThread = new Thread(() -> {
            try {
                ServerSocket audioServerSocket = new ServerSocket();
                audioServerSocket.setReuseAddress(true);
                audioServerSocket.bind(new InetSocketAddress("0.0.0.0", audioPort));
                System.out.println("AudioServer listening on port " + audioPort);

                while (true) {
                    Socket client = audioServerSocket.accept();
                    client.setTcpNoDelay(true);
                    client.setSendBufferSize(65536);
                    System.out.println("Audio client connected: " + client.getRemoteSocketAddress());

                    Thread streamer = new Thread(() -> streamAudio(client), "AudioStreamer");
                    streamer.setDaemon(true);
                    streamer.start();
                }
            } catch (Exception e) {
                System.err.println("AudioServer error: " + e.getMessage());
            }
        }, "AudioServerThread");
        audioServerThread.setDaemon(true);
        audioServerThread.start();
    }

    private static void streamAudio(Socket client) {
        AudioRecord ar = null;
        try {
            Context ctx = getShellContext();
            int sampleRate = 48000;
            int channelConfig = AudioFormat.CHANNEL_IN_STEREO;
            int encoding = AudioFormat.ENCODING_PCM_16BIT;
            int minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding);
            int bufferSize = Math.max(minBuf * 2, 8192);

            AudioRecord.Builder builder = new AudioRecord.Builder();
            if (Build.VERSION.SDK_INT >= 31 && ctx != null) {
                try {
                    Method setContext = AudioRecord.Builder.class.getMethod("setContext", Context.class);
                    setContext.invoke(builder, ctx);
                } catch (Exception ignored) {}
            }

            builder.setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX);
            builder.setAudioFormat(new AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build());
            builder.setBufferSizeInBytes(bufferSize);

            ar = builder.build();
            ar.startRecording();

            OutputStream out = client.getOutputStream();
            byte[] buf = new byte[1920]; // 10ms chunks of 48kHz stereo 16-bit PCM

            // REMOTE_SUBMIX PCM comes at a very low level (~-20dB below normal)
            // Amplify by 3x to bring it to a normal listening volume
            final float GAIN = 3.0f;

            while (!client.isClosed()) {
                int read = ar.read(buf, 0, buf.length, AudioRecord.READ_BLOCKING);
                if (read > 0) {
                    // Apply gain: PCM is little-endian signed 16-bit
                    for (int i = 0; i + 1 < read; i += 2) {
                        int sample = (short) ((buf[i] & 0xFF) | (buf[i + 1] << 8));
                        sample = Math.round(sample * GAIN);
                        // Clamp to int16 range
                        if (sample > 32767) sample = 32767;
                        else if (sample < -32768) sample = -32768;
                        buf[i]     = (byte) (sample & 0xFF);
                        buf[i + 1] = (byte) ((sample >> 8) & 0xFF);
                    }
                    out.write(buf, 0, read);
                    out.flush();
                } else if (read == 0) {
                    Thread.sleep(5);
                } else {
                    break;
                }
            }
        } catch (Exception e) {
            System.out.println("Audio client disconnected: " + e.getMessage());
        } finally {
            if (ar != null) {
                try { ar.stop(); ar.release(); } catch (Exception ignored) {}
            }
            try { client.close(); } catch (Exception ignored) {}
        }
    }
}
