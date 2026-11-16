package org.cinematic.evaluation;
import android.content.Context;
public final class DetectorFactory {
    public static Detector create(Context context, String mode) throws Exception {
        return Yunet.create(context, mode);
    }
}
