package org.cinematic.evaluation;

import android.content.Context;
import android.graphics.Bitmap;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.FaceDetectorYN;

/** Port of r10h_refine.detect_faces AFTER its linear_to_unit/uint8 conversion. */
public final class DetectorFactory {
    public static Detector create(Context context, String mode) throws Exception {
        if (!mode.equals("MULTI")) throw new IllegalArgumentException("mode");
        if (!OpenCVLoader.initLocal()) throw new IllegalStateException("OpenCV initialization failed");
        String name = "face_detection_yunet_2023mar.onnx";
        File model = new File(context.getCacheDir(), name);
        try (var in = context.getAssets().open(name)) {
            Files.copy(in, model.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        StringBuilder digest = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(model.toPath()))) {
            digest.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        }
        String hash = digest.toString();
        if (!hash.equals("8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4")) {
            throw new IllegalStateException("Model hash mismatch");
        }
        var sdk = FaceDetectorYN.create(model.getAbsolutePath(), "", new Size(640, 480), .5f, .3f, 5000);
        return new Detector() {
            private long elapsed;
            public long sdkNs() { return elapsed; }
            public String id() { return "yunet-2023mar-8f2383e4-opencv4.13.0-pc-multi-v1"; }
            public List<float[]> detect(Bitmap input) {
                elapsed = 0;
                Mat rgba = new Mat(), bgr = new Mat();
                try {
                    Utils.bitmapToMat(input, rgba);
                    Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR);
                    int w = input.getWidth(), h = input.getHeight();
                    sdk.setInputSize(new Size(w, h));
                    List<float[]> boxes = new ArrayList<>();
                    for (int angle : new int[] {0, 25, -25, 45, -45}) {
                        Mat rotation = Imgproc.getRotationMatrix2D(new Point(w / 2.0, h / 2.0), angle, 1);
                        Mat back = new Mat(), warped = new Mat(), found = new Mat();
                        try {
                            Imgproc.invertAffineTransform(rotation, back);
                            if (angle != 0) Imgproc.warpAffine(bgr, warped, rotation, new Size(w, h));
                            long start = android.os.SystemClock.elapsedRealtimeNanos();
                            sdk.detect(angle == 0 ? bgr : warped, found);
                            elapsed += android.os.SystemClock.elapsedRealtimeNanos() - start;
                            for (int i = 0; i < found.rows(); i++) {
                                if (found.get(i, 14)[0] < (angle == 0 ? .75 : .85)) continue;
                                double x = found.get(i, 0)[0], y = found.get(i, 1)[0];
                                double bw = found.get(i, 2)[0], bh = found.get(i, 3)[0];
                                double cx = back.get(0, 0)[0] * (x + bw / 2) + back.get(0, 1)[0] * (y + bh / 2) + back.get(0, 2)[0];
                                double cy = back.get(1, 0)[0] * (x + bw / 2) + back.get(1, 1)[0] * (y + bh / 2) + back.get(1, 2)[0];
                                boolean duplicate = false;
                                for (float[] b : boxes) {
                                    if (Math.abs(cx - (b[0] + b[2] / 2.0)) <= (bw + b[2]) / 3
                                        && Math.abs(cy - (b[1] + b[3] / 2.0)) <= (bh + b[3]) / 3) duplicate = true;
                                }
                                if (!duplicate) boxes.add(new float[] {(int)(cx - bw / 2), (int)(cy - bh / 2), (int)bw, (int)bh});
                            }
                        } finally { rotation.release(); back.release(); warped.release(); found.release(); }
                    }
                    return boxes;
                } finally { rgba.release(); bgr.release(); }
            }
            // OpenCV Java FaceDetectorYN has no public close/delete. Process exit is the cold reset.
            public void close() {}
        };
    }
}
