package org.easydarwin.sw;

/** Pixel conversion used by the hardware decoder's I420 callback. */
public final class JNIUtil {
    static {
        System.loadLibrary("EasyPlayerFFmpeg");
    }

    private JNIUtil() {}

    /** Converts packed NV12 to packed I420 in place. Only mode 4 is supported. */
    public static native void yuvConvert(byte[] data, int width, int height, int mode);
}
