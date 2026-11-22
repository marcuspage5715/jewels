package com.marcuspage.jewels;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Tiny synth. Every effect is generated as a PCM buffer at start-up, written
 * to the app cache as a WAV and played through SoundPool, so the app ships
 * with no audio assets at all.
 */
public class SoundFx {

    public static final int MOVE = 0;
    public static final int ROTATE = 1;
    public static final int DROP = 2;
    public static final int CLEAR = 3;
    public static final int WIN = 4;
    public static final int OVER = 5;

    private static final int RATE = 22050;
    private static final int SLOTS = 6;

    private SoundPool pool;
    private final int[] ids = new int[SLOTS];
    private boolean muted;

    public SoundFx(Context context, boolean muted) {
        this.muted = muted;
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            pool = new SoundPool.Builder().setMaxStreams(SLOTS).setAudioAttributes(attrs).build();

            ids[MOVE] = load(context, "move", sequence(new double[]{520}, new int[]{34}, 0.30, false));
            ids[ROTATE] = load(context, "rotate", sequence(new double[]{760, 1040}, new int[]{28, 34}, 0.30, false));
            ids[DROP] = load(context, "drop", sweep(220, 90, 90, 0.42));
            ids[CLEAR] = load(context, "clear", sequence(new double[]{880, 1174, 1568}, new int[]{60, 60, 110}, 0.34, false));
            ids[WIN] = load(context, "win", sequence(new double[]{784, 988, 1318, 1568}, new int[]{90, 90, 90, 260}, 0.36, false));
            ids[OVER] = load(context, "over", sequence(new double[]{440, 349, 262}, new int[]{150, 150, 320}, 0.36, true));
        } catch (Throwable t) {
            pool = null; // Silent fallback: audio is a nicety, never a hard failure.
        }
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
    }

    public boolean isMuted() {
        return muted;
    }

    public void play(int id) {
        if (pool == null || muted || id < 0 || id >= SLOTS) return;
        try {
            pool.play(ids[id], 1f, 1f, 1, 0, 1f);
        } catch (Throwable ignored) {
        }
    }

    public void release() {
        if (pool != null) {
            pool.release();
            pool = null;
        }
    }

    private int load(Context context, String name, short[] pcm) {
        File f = new File(context.getCacheDir(), "sfx_" + name + ".wav");
        try {
            writeWav(f, pcm);
        } catch (IOException e) {
            return 0;
        }
        return pool.load(f.getAbsolutePath(), 1);
    }

    private static short[] sequence(double[] freqs, int[] millis, double volume, boolean square) {
        int total = 0;
        for (int m : millis) total += RATE * m / 1000;
        short[] out = new short[total];
        int pos = 0;
        for (int n = 0; n < freqs.length; n++) {
            int len = RATE * millis[n] / 1000;
            for (int i = 0; i < len; i++) {
                double t = (double) i / RATE;
                double attack = Math.min(1.0, i / (RATE * 0.004));
                double release = Math.min(1.0, (len - i) / (RATE * 0.02));
                double s = Math.sin(2 * Math.PI * freqs[n] * t);
                if (square) s = s >= 0 ? 0.65 : -0.65;
                out[pos + i] = (short) (s * attack * release * volume * 32767);
            }
            pos += len;
        }
        return out;
    }

    private static short[] sweep(double from, double to, int millis, double volume) {
        int len = RATE * millis / 1000;
        short[] out = new short[len];
        double phase = 0;
        for (int i = 0; i < len; i++) {
            double p = (double) i / len;
            double f = from + (to - from) * p;
            phase += 2 * Math.PI * f / RATE;
            double env = Math.min(1.0, i / (RATE * 0.003)) * (1.0 - p);
            out[i] = (short) (Math.sin(phase) * env * volume * 32767);
        }
        return out;
    }

    private static void writeWav(File file, short[] pcm) throws IOException {
        int dataLen = pcm.length * 2;
        byte[] header = new byte[44];
        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        putInt(header, 4, 36 + dataLen);
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        putInt(header, 16, 16);
        putShort(header, 20, 1);            // PCM
        putShort(header, 22, 1);            // mono
        putInt(header, 24, RATE);
        putInt(header, 28, RATE * 2);       // byte rate
        putShort(header, 32, 2);            // block align
        putShort(header, 34, 16);           // bits per sample
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        putInt(header, 40, dataLen);

        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(header);
            byte[] buf = new byte[dataLen];
            for (int i = 0; i < pcm.length; i++) {
                buf[i * 2] = (byte) (pcm[i] & 0xFF);
                buf[i * 2 + 1] = (byte) ((pcm[i] >> 8) & 0xFF);
            }
            out.write(buf);
        }
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
        b[off + 2] = (byte) ((v >> 16) & 0xFF);
        b[off + 3] = (byte) ((v >> 24) & 0xFF);
    }

    private static void putShort(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
    }
}
