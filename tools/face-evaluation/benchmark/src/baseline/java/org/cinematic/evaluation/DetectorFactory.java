package org.cinematic.evaluation;
import android.content.Context;
import android.graphics.Bitmap;
import java.util.List;
public final class DetectorFactory {
    public static Detector create(Context context, String mode) {
        return new Detector() {
            public String id() { return "baseline-no-detector"; }
            public List<float[]> detect(Bitmap input) { throw new UnsupportedOperationException("Size baseline only"); }
            public void close() {}
        };
    }
}
