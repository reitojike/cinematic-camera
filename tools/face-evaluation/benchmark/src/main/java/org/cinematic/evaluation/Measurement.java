package org.cinematic.evaluation;

import android.graphics.Bitmap;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;

/** Call off the main thread, serially. Completion is awaited before closing/reusing the detector. */
public final class Measurement {
    private Measurement() {}

    public static JSONObject run(Detector detector, Bitmap input, String sample, String phase,
                                 int iteration, long initializationNs) throws Exception {
        if (!sample.matches("synthetic-[a-z0-9-]+")) throw new IllegalArgumentException("Synthetic IDs only");
        if (!phase.equals("first-call") && !phase.equals("warm")) throw new IllegalArgumentException("phase");
        JSONObject result = new JSONObject().put("schema", 1).put("sample", sample)
            .put("detector", detector.id()).put("phase", phase).put("iteration", iteration)
            .put("initialization_ns", initializationNs).put("geometry", "upright-srgb-argb8888-v1")
            .put("status", "NOT_RUN").put("boxes", new JSONArray());
        long start = SystemClock.elapsedRealtimeNanos();
        Bitmap owned = null;
        try {
            if (input == null || input.getConfig() != Bitmap.Config.ARGB_8888 || input.hasAlpha()) {
                throw new IllegalArgumentException("Opaque ARGB_8888 required");
            }
            result.put("width", input.getWidth()).put("height", input.getHeight());
            owned = input.copy(Bitmap.Config.ARGB_8888, false);
            long call = SystemClock.elapsedRealtimeNanos();
            var boxes = detector.detect(owned);
            long end = SystemClock.elapsedRealtimeNanos();
            JSONArray serialized = new JSONArray();
            for (float[] box : boxes) {
                if (box.length != 4) throw new IllegalStateException("Invalid box");
                JSONArray b = new JSONArray();
                for (float v : box) { if (!Float.isFinite(v)) throw new IllegalStateException("Nonfinite box"); b.put(v); }
                if (box[2] <= 0 || box[3] <= 0) throw new IllegalStateException("Empty box");
                serialized.put(b);
            }
            result.put("status", "SUCCESS").put("boxes", serialized)
                .put("sdk_ns", detector.sdkNs()).put("adapter_ns", end - call).put("total_ns", end - start)
                .put("completed_elapsed_ns", end);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result.put("status", "CANCELLED");
        } catch (Exception e) {
            // Do not serialize messages: SDK exceptions can carry private paths or source details.
            result.put("status", "FAILED").put("error_type", e.getClass().getSimpleName());
        } finally { if (owned != null) owned.recycle(); }
        return result;
    }
}
