package com.pcmic.xposed;

import android.media.AudioFormat;
import android.media.AudioRecord;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hook AudioRecord read() overloads.
 * Source stream: 48kHz stereo 24bit from AudioStreamReceiver.
 * Converts to whatever format the target app's AudioRecord expects.
 */
public class AudioRecordHook {

    private static final String TAG = "PcMic-Hook";

    public static void install(AudioStreamReceiver receiver) {
        XposedHelpers.findAndHookMethod(
            AudioRecord.class, "startRecording",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    if (!MainHook.isMicServiceEnabled()) return;
                    receiver.start();
                }
            }
        );

        XposedHelpers.findAndHookMethod(AudioRecord.class, "read",
            byte[].class, int.class, int.class,
            new ReadByteArrayHook(receiver));

        XposedHelpers.findAndHookMethod(AudioRecord.class, "read",
            byte[].class, int.class, int.class, int.class,
            new ReadByteArrayHook(receiver));

        XposedHelpers.findAndHookMethod(AudioRecord.class, "read",
            short[].class, int.class, int.class,
            new ReadShortArrayHook(receiver));

        XposedHelpers.findAndHookMethod(AudioRecord.class, "read",
            short[].class, int.class, int.class, int.class,
            new ReadShortArrayHook(receiver));

        XposedHelpers.findAndHookMethod(AudioRecord.class, "read",
            ByteBuffer.class, int.class,
            new ReadByteBufferHook(receiver));

        XposedHelpers.findAndHookMethod(AudioRecord.class, "read",
            ByteBuffer.class, int.class, int.class,
            new ReadByteBufferHook(receiver));

        XposedHelpers.findAndHookMethod(AudioRecord.class, "read",
            float[].class, int.class, int.class, int.class,
            new ReadFloatArrayHook(receiver));

        XposedBridge.log(TAG + ": all read() overloads hooked (48kHz/stereo/24bit source)");
    }

    private static int getSampleRate(Object ar) {
        try { return ((AudioRecord) ar).getSampleRate(); }
        catch (Exception e) { return AudioStreamReceiver.SRC_RATE; }
    }

    private static int getChannelCount(Object ar) {
        try { return ((AudioRecord) ar).getChannelCount(); }
        catch (Exception e) { return 1; }
    }

    /** Read 24bit LE sample from byte array at offset, return as int (-8388608..8388607) */
    private static int read24bit(byte[] data, int off) {
        int lo = data[off] & 0xFF;
        int mid = data[off + 1] & 0xFF;
        int hi = data[off + 2]; // signed for sign extension
        return (hi << 16) | (mid << 8) | lo;
    }

    /**
     * Convert 48kHz/stereo/24bit raw PCM to target format as 16bit mono/stereo PCM bytes.
     * @param src raw 48kHz stereo 24bit data
     * @param targetRate target sample rate
     * @param targetCh target channel count (1=mono, 2=stereo)
     * @param targetSamples number of output samples (per channel) needed
     * @return 16bit LE PCM bytes in target format
     */
    private static byte[] convertToTarget(byte[] src, int targetRate, int targetCh, int targetSamples) {
        int srcBytesPerFrame = AudioStreamReceiver.SRC_CH * AudioStreamReceiver.SRC_BYTES_PER_SAMPLE; // 6
        int srcFrames = src.length / srcBytesPerFrame;
        if (srcFrames == 0) return new byte[targetSamples * targetCh * 2];

        byte[] dst = new byte[targetSamples * targetCh * 2];
        ByteBuffer dB = ByteBuffer.wrap(dst).order(ByteOrder.LITTLE_ENDIAN);

        for (int i = 0; i < targetSamples; i++) {
            // Map output sample position to source position
            double srcPos = (double) i * AudioStreamReceiver.SRC_RATE / targetRate;
            int idx = (int) srcPos;
            double frac = srcPos - idx;
            int idx1 = Math.min(idx + 1, srcFrames - 1);
            idx = Math.min(idx, srcFrames - 1);

            // Interpolate left channel (24bit -> 16bit)
            int l0 = read24bit(src, idx * srcBytesPerFrame);
            int l1 = read24bit(src, idx1 * srcBytesPerFrame);
            short left = (short) ((int)(l0 + frac * (l1 - l0)) >> 8);

            if (targetCh >= 2) {
                // Interpolate right channel
                int r0 = read24bit(src, idx * srcBytesPerFrame + 3);
                int r1 = read24bit(src, idx1 * srcBytesPerFrame + 3);
                short right = (short) ((int)(r0 + frac * (r1 - r0)) >> 8);
                dB.putShort(i * 4, left);
                dB.putShort(i * 4 + 2, right);
            } else {
                // Mono: average L+R
                int r0 = read24bit(src, idx * srcBytesPerFrame + 3);
                int r1 = read24bit(src, idx1 * srcBytesPerFrame + 3);
                int lVal = (int)(l0 + frac * (l1 - l0));
                int rVal = (int)(r0 + frac * (r1 - r0));
                short mono = (short) (((lVal + rVal) / 2) >> 8);
                dB.putShort(i * 2, mono);
            }
        }
        return dst;
    }

    /** Calculate how many bytes of 48kHz/stereo/24bit source we need for given output */
    private static int calcSrcBytes(int targetSamples, int targetRate) {
        int srcFrames = (int) ((long) targetSamples * AudioStreamReceiver.SRC_RATE / targetRate) + 2;
        return srcFrames * AudioStreamReceiver.SRC_CH * AudioStreamReceiver.SRC_BYTES_PER_SAMPLE;
    }

    // ---- read(byte[], ...) ----
    static class ReadByteArrayHook extends XC_MethodHook {
        final AudioStreamReceiver r;
        ReadByteArrayHook(AudioStreamReceiver r) { this.r = r; }
        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            if (!MainHook.isMicServiceEnabled()) return;
            byte[] buf = (byte[]) p.args[0];
            int off = (int) p.args[1], size = (int) p.args[2];
            int rate = getSampleRate(p.thisObject);
            int ch = getChannelCount(p.thisObject);
            int outSamples = size / (ch * 2); // 16bit output
            int srcBytes = calcSrcBytes(outSamples, rate);
            byte[] tmp = new byte[srcBytes];
            r.read(tmp, 0, srcBytes);
            byte[] out = convertToTarget(tmp, rate, ch, outSamples);
            System.arraycopy(out, 0, buf, off, Math.min(out.length, size));
        }
    }

    // ---- read(short[], ...) ----
    static class ReadShortArrayHook extends XC_MethodHook {
        final AudioStreamReceiver r;
        ReadShortArrayHook(AudioStreamReceiver r) { this.r = r; }
        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            if (!MainHook.isMicServiceEnabled()) return;
            short[] buf = (short[]) p.args[0];
            int off = (int) p.args[1], size = (int) p.args[2];
            int rate = getSampleRate(p.thisObject);
            int ch = getChannelCount(p.thisObject);
            int outSamples = size / ch;
            int srcBytes = calcSrcBytes(outSamples, rate);
            byte[] tmp = new byte[srcBytes];
            r.read(tmp, 0, srcBytes);
            byte[] out = convertToTarget(tmp, rate, ch, outSamples);
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
            if (!MainHook.isMicServiceEnabled()) return;
            ByteBuffer buf = (ByteBuffer) p.args[0];
            int size = (int) p.args[1];
            int rate = getSampleRate(p.thisObject);
            int ch = getChannelCount(p.thisObject);
            int outSamples = size / (ch * 2);
            int srcBytes = calcSrcBytes(outSamples, rate);
            byte[] tmp = new byte[srcBytes];
            r.read(tmp, 0, srcBytes);
            byte[] out = convertToTarget(tmp, rate, ch, outSamples);
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
            if (!MainHook.isMicServiceEnabled()) return;
            float[] buf = (float[]) p.args[0];
            int off = (int) p.args[1], size = (int) p.args[2];
            int rate = getSampleRate(p.thisObject);
            int ch = getChannelCount(p.thisObject);
            int outSamples = size / ch;
            int srcBytes = calcSrcBytes(outSamples, rate);
            byte[] tmp = new byte[srcBytes];
            r.read(tmp, 0, srcBytes);
            byte[] out = convertToTarget(tmp, rate, ch, outSamples);
            ByteBuffer bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
            int n = Math.min(out.length / 2, size);
            for (int i = 0; i < n; i++) buf[off + i] = bb.getShort(i * 2) / 32768.0f;
        }
    }
}
