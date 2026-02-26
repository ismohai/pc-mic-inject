package com.pcmic.xposed;

import android.media.AudioRecord;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hook AudioRecord.startRecording() and all read() overloads.
 * Strategy: afterHookedMethod — let original read() execute first (preserving blocking),
 * then overwrite buffer with PC audio data.
 */
public class AudioRecordHook {

    private static final String TAG = "PcMic-Hook";
    private static final int SRC_RATE = 16000;

    public static void install(AudioStreamReceiver receiver) {
        // hook startRecording -> start TCP receiver
        XposedHelpers.findAndHookMethod(
            AudioRecord.class, "startRecording",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    receiver.start();
                }
            }
        );

        // read(byte[], int, int)
        XposedHelpers.findAndHookMethod(AudioRecord.class, "read",
            byte[].class, int.class, int.class,
            new ReadByteArrayHook(receiver));

        // read(byte[], int, int, int)
        XposedHelpers.findAndHookMethod(AudioRecord.class, "read",
            byte[].class, int.class, int.class, int.class,
            new ReadByteArrayHook(receiver));

        // read(short[], int, int)
        XposedHelpers.findAndHookMethod(AudioRecord.class, "read",
            short[].class, int.class, int.class,
            new ReadShortArrayHook(receiver));

        // read(short[], int, int, int)
        XposedHelpers.findAndHookMethod(AudioRecord.class, "read",
            short[].class, int.class, int.class, int.class,
            new ReadShortArrayHook(receiver));

        // read(ByteBuffer, int)
        XposedHelpers.findAndHookMethod(AudioRecord.class, "read",
            ByteBuffer.class, int.class,
            new ReadByteBufferHook(receiver));

        // read(ByteBuffer, int, int)
        XposedHelpers.findAndHookMethod(AudioRecord.class, "read",
            ByteBuffer.class, int.class, int.class,
            new ReadByteBufferHook(receiver));

        // read(float[], int, int, int)
        XposedHelpers.findAndHookMethod(AudioRecord.class, "read",
            float[].class, int.class, int.class, int.class,
            new ReadFloatArrayHook(receiver));

        XposedBridge.log(TAG + ": all read() overloads hooked");
    }

    private static int getSampleRate(Object ar) {
        try { return ((AudioRecord) ar).getSampleRate(); }
        catch (Exception e) { return SRC_RATE; }
    }

    /** Linear interpolation resample: 16kHz -> targetRate */
    private static byte[] resample(byte[] src, int targetRate) {
        if (targetRate == SRC_RATE || targetRate <= 0) return src;
        int srcN = src.length / 2;
        int dstN = (int) ((long) srcN * targetRate / SRC_RATE);
        if (dstN <= 0) return src;
        byte[] dst = new byte[dstN * 2];
        ByteBuffer sB = ByteBuffer.wrap(src).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer dB = ByteBuffer.wrap(dst).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < dstN; i++) {
            double pos = (double) i * (srcN - 1) / Math.max(dstN - 1, 1);
            int idx = (int) pos;
            double frac = pos - idx;
            short s0 = sB.getShort(idx * 2);
            short s1 = (idx + 1 < srcN) ? sB.getShort((idx + 1) * 2) : s0;
            dB.putShort(i * 2, (short) (s0 + frac * (s1 - s0)));
        }
        return dst;
    }

    // ---- read(byte[], ...) ----
    static class ReadByteArrayHook extends XC_MethodHook {
        final AudioStreamReceiver r;
        ReadByteArrayHook(AudioStreamReceiver r) { this.r = r; }
        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            byte[] buf = (byte[]) p.args[0];
            int off = (int) p.args[1], size = (int) p.args[2];
            int rate = getSampleRate(p.thisObject);
            int srcSz = (rate == SRC_RATE) ? size : (int) ((long) size * SRC_RATE / rate);
            byte[] tmp = new byte[srcSz];
            r.read(tmp, 0, srcSz);
            byte[] out = resample(tmp, rate);
            System.arraycopy(out, 0, buf, off, Math.min(out.length, size));
        }
    }

    // ---- read(short[], ...) ----
    static class ReadShortArrayHook extends XC_MethodHook {
        final AudioStreamReceiver r;
        ReadShortArrayHook(AudioStreamReceiver r) { this.r = r; }
        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            short[] buf = (short[]) p.args[0];
            int off = (int) p.args[1], size = (int) p.args[2];
            int bSz = size * 2, rate = getSampleRate(p.thisObject);
            int srcSz = (rate == SRC_RATE) ? bSz : (int) ((long) bSz * SRC_RATE / rate);
            byte[] tmp = new byte[srcSz];
            r.read(tmp, 0, srcSz);
            byte[] out = resample(tmp, rate);
            ByteBuffer bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
            int n = Math.min(out.length / 2, size);
            for (int i = 0; i < n; i++) buf[off + i] = bb.getShort(i * 2);
        }
    }

    // ---- read(ByteBuffer, ...) ----
    static class ReadByteBufferHook extends XC_MethodHook {
        final AudioStreamReceiver r;
        ReadByteBufferHook(AudioStreamReceiver r) { this.r = r; }
        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            ByteBuffer buf = (ByteBuffer) p.args[0];
            int size = (int) p.args[1], rate = getSampleRate(p.thisObject);
            int srcSz = (rate == SRC_RATE) ? size : (int) ((long) size * SRC_RATE / rate);
            byte[] tmp = new byte[srcSz];
            r.read(tmp, 0, srcSz);
            byte[] out = resample(tmp, rate);
            int copy = Math.min(out.length, size);
            buf.position(0);
            buf.put(out, 0, copy);
            buf.position(0);
        }
    }

    // ---- read(float[], ...) ----
    static class ReadFloatArrayHook extends XC_MethodHook {
        final AudioStreamReceiver r;
        ReadFloatArrayHook(AudioStreamReceiver r) { this.r = r; }
        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            float[] buf = (float[]) p.args[0];
            int off = (int) p.args[1], size = (int) p.args[2];
            int bSz = size * 2, rate = getSampleRate(p.thisObject);
            int srcSz = (rate == SRC_RATE) ? bSz : (int) ((long) bSz * SRC_RATE / rate);
            byte[] tmp = new byte[srcSz];
            r.read(tmp, 0, srcSz);
            byte[] out = resample(tmp, rate);
            ByteBuffer bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
            int n = Math.min(out.length / 2, size);
            for (int i = 0; i < n; i++) buf[off + i] = bb.getShort(i * 2) / 32768.0f;
        }
    }
}
