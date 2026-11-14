package org.cinematic.evaluation;
import android.content.Context;
import android.graphics.Bitmap;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import java.util.ArrayList;
import java.util.List;
public final class DetectorFactory {
    public static Detector create(Context context, String mode) {
        if (!mode.equals("FAST") && !mode.equals("ACCURATE")) throw new IllegalArgumentException("mode");
        var sdk = FaceDetection.getClient(new FaceDetectorOptions.Builder()
            .setPerformanceMode(mode.equals("FAST") ? FaceDetectorOptions.PERFORMANCE_MODE_FAST : FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.1f).build());
        return new Detector() {
            private long elapsed;
            public long sdkNs() { return elapsed; }
            public String id() { return "mlkit-16.1.7-" + mode + "-min0.1-no-tracking"; }
            public List<float[]> detect(Bitmap input) throws Exception {
                var image = InputImage.fromBitmap(input, 0);
                long start = android.os.SystemClock.elapsedRealtimeNanos();
                var task = sdk.process(image);
                // A cancelled wait does not cancel native work. Drain completion before releasing input.
                boolean interrupted = false;
                try {
                    while (true) {
                        try {
                            var faces = Tasks.await(task);
                            elapsed = android.os.SystemClock.elapsedRealtimeNanos() - start;
                            if (interrupted) throw new InterruptedException();
                            List<float[]> boxes = new ArrayList<>();
                            for (var face : faces) {
                                var b = face.getBoundingBox();
                                boxes.add(new float[] {b.left, b.top, b.width(), b.height()});
                            }
                            return boxes;
                        } catch (InterruptedException e) {
                            if (task.isComplete()) throw e;
                            interrupted = true;
                        }
                    }
                } finally { if (interrupted) Thread.currentThread().interrupt(); }
            }
            public void close() { sdk.close(); }
        };
    }
}
