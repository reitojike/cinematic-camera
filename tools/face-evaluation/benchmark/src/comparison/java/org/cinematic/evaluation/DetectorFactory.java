package org.cinematic.evaluation;
import android.content.Context;
public final class DetectorFactory {
    public static Detector create(Context context, String mode) throws Exception {
        return mode.equals("MULTI") ? Yunet.create(context, mode) : BundledMlkit.create(context, mode);
    }
}
