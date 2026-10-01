package com.dex.note10;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;
import java.io.InputStream;
import java.net.Socket;

/**
 * Low-latency real-time PCM audio receiver and player.
 * Connects to Note 10 AudioServer on port 27184 via TCP.
 * Directly feeds 48kHz 16-bit stereo PCM into AudioTrack with zero transcoding delay.
 */
public class AudioPlayer {
    private static final String TAG = "AudioPlayer";
    public static final int SAMPLE_RATE = 48000;
    public static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_STEREO;
    public static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private final String host;
    private final int port;
    private volatile boolean running = false;
    private Thread audioThread;
    private AudioTrack audioTrack;
    private Socket audioSocket;

    public AudioPlayer(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        audioThread = new Thread(this::audioLoop, "AudioPlayerThread");
        audioThread.setPriority(Thread.MAX_PRIORITY);
        audioThread.start();
    }

    public synchronized void stop() {
        running = false;
        if (audioSocket != null) {
            try { audioSocket.close(); } catch (Exception ignored) {}
            audioSocket = null;
        }
        if (audioThread != null) {
            audioThread.interrupt();
            audioThread = null;
        }
        if (audioTrack != null) {
            try {
                audioTrack.stop();
                audioTrack.release();
            } catch (Exception ignored) {}
            audioTrack = null;
        }
    }

    private void audioLoop() {
        int minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        int bufferSize = Math.max(minBuf * 2, 8192);

        try {
            audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AUDIO_FORMAT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(CHANNEL_CONFIG)
                            .build())
                    .setBufferSizeInBytes(bufferSize * 4)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build();

            // Set volume to MAXIMUM — PCM from REMOTE_SUBMIX comes at a low level
            audioTrack.setVolume(AudioTrack.getMaxVolume());
            audioTrack.play();
            Log.i(TAG, "AudioTrack playing. Connecting to " + host + ":" + port + "...");

            audioSocket = new Socket(host, port);
            audioSocket.setTcpNoDelay(true);
            audioSocket.setReceiveBufferSize(65536);
            InputStream in = audioSocket.getInputStream();
            Log.i(TAG, "Connected to Note 10 AudioServer!");

            byte[] buffer = new byte[1920];
            while (running) {
                int read = in.read(buffer);
                if (read < 0) break;
                if (read > 0) {
                    audioTrack.write(buffer, 0, read);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Audio connection ended: " + e.getMessage());
        } finally {
            if (audioTrack != null) {
                try {
                    audioTrack.stop();
                    audioTrack.release();
                } catch (Exception ignored) {}
                audioTrack = null;
            }
            if (audioSocket != null) {
                try { audioSocket.close(); } catch (Exception ignored) {}
                audioSocket = null;
            }
        }
    }
}
