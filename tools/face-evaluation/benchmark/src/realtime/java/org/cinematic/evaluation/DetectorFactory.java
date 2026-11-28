package org.cinematic.evaluation;
import android.content.Context;
public final class DetectorFactory {
    private DetectorFactory() {}
    public static Detector create(Context context, String mode) {
        if (!mode.equals("ACCURATE") && !mode.equals("ACCURATE_MIN005")) throw new IllegalArgumentException("mode");
        return BundledMlkit.create(context, mode);
    }
}
