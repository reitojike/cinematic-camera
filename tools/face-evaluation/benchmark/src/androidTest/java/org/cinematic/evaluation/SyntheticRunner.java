package org.cinematic.evaluation;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.File;
import java.io.FileWriter;

/** Explicit synthetic-only entry point. Never opens a photo, camera, picker or external storage. */
public final class SyntheticRunner extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle args) { super.onCreate(args); arguments = args; start(); }
    @Override public void onStart() {
        Bundle status = new Bundle();
        Bitmap bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.BLACK);
        bitmap.setHasAlpha(false);
        try {
            if (!"true".equals(arguments.getString("offlineReviewed"))) {
                throw new IllegalStateException("Runtime offline/IPC gate not approved");
            }
            String mode = arguments.getString("mode", "FAST");
            long start = SystemClock.elapsedRealtimeNanos();
            try (Detector detector = DetectorFactory.create(getTargetContext(), mode);
                 FileWriter writer = new FileWriter(new File(getTargetContext().getFilesDir(), "synthetic-results.jsonl"))) {
                long init = SystemClock.elapsedRealtimeNanos() - start;
                for (int i = 0; i < 11; i++) {
                    var row = Measurement.run(detector, bitmap, "synthetic-black", i == 0 ? "first-call" : "warm", i, init);
                    writer.write(row + "\n");
                    if (!"SUCCESS".equals(row.getString("status"))) throw new IllegalStateException("Detection did not succeed");
                }
            }
            status.putString("result", "synthetic-only; real accuracy and device performance NOT_MEASURED");
            finish(-1, status);
        } catch (Exception e) {
            status.putString("error_type", e.getClass().getSimpleName());
            finish(0, status);
        } finally { bitmap.recycle(); }
    }
}
