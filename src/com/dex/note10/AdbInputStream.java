package com.dex.note10;

import com.tananaev.adblib.AdbStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

public class AdbInputStream extends InputStream {
    private final AdbStream stream;
    private byte[] currentChunk = null;
    private int chunkPos = 0;

    public AdbInputStream(AdbStream stream) {
        this.stream = stream;
    }

    @Override
    public int read() throws IOException {
        byte[] b = new byte[1];
        int r = read(b, 0, 1);
        return r > 0 ? (b[0] & 0xFF) : -1;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) return 0;
        int totalRead = 0;
        while (totalRead < len) {
            if (currentChunk == null || chunkPos >= currentChunk.length) {
                try {
                    currentChunk = stream.read();
                    chunkPos = 0;
                    if (currentChunk == null) {
                        return totalRead > 0 ? totalRead : -1;
                    }
                } catch (Exception e) {
                    return totalRead > 0 ? totalRead : -1;
                }
            }
            int available = currentChunk.length - chunkPos;
            int toCopy = Math.min(len - totalRead, available);
            System.arraycopy(currentChunk, chunkPos, b, off + totalRead, toCopy);
            chunkPos += toCopy;
            totalRead += toCopy;
        }
        return totalRead;
    }

    public void readFully(byte[] b, int off, int len) throws IOException {
        int n = 0;
        while (n < len) {
            int count = read(b, off + n, len - n);
            if (count <= 0) throw new EOFException("End of stream reached");
            n += count;
        }
    }
}
