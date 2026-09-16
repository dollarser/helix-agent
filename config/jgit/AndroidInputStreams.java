package com.helix.jgit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** Helix-authored Java stream API adaptation for Android 29-32. */
public final class AndroidInputStreams {
    private AndroidInputStreams() {}

    public static int readNBytes(InputStream input, byte[] target, int offset, int length) throws IOException {
        Objects.requireNonNull(input);
        Objects.requireNonNull(target);
        if (offset < 0 || length < 0 || offset > target.length - length) throw new IndexOutOfBoundsException();
        int count = 0;
        while (count < length) {
            int read = input.read(target, offset + count, length - count);
            if (read < 0) break;
            if (read == 0) {
                int value = input.read();
                if (value < 0) break;
                target[offset + count++] = (byte) value;
            } else {
                count += read;
            }
        }
        return count;
    }

    public static byte[] readNBytes(InputStream input, int length) throws IOException {
        Objects.requireNonNull(input);
        if (length < 0) throw new IllegalArgumentException("negative length");
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(length, 8192));
        byte[] buffer = new byte[Math.min(length, 8192)];
        int remaining = length;
        while (remaining > 0) {
            int read = readNBytes(input, buffer, 0, Math.min(remaining, buffer.length));
            if (read == 0) break;
            output.write(buffer, 0, read);
            remaining -= read;
        }
        return output.toByteArray();
    }

    public static byte[] readAllBytes(InputStream input) throws IOException {
        return readNBytes(input, Integer.MAX_VALUE);
    }
}
