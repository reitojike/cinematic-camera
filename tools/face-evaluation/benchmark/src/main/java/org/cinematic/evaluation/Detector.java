package org.cinematic.evaluation;

import android.graphics.Bitmap;
import java.util.List;

/** Same upright, opaque sRGB ARGB_8888 bitmap; coordinates are unrounded input pixels. */
public interface Detector extends AutoCloseable {
    String id();
    List<float[]> detect(Bitmap input) throws Exception;
    /** Wall time inside completed SDK calls; includes async scheduling, not kernel-only time. */
    default long sdkNs() { return 0; }
    @Override void close();
}
