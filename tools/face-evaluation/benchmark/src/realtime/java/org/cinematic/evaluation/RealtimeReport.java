package org.cinematic.evaluation;

import java.util.ArrayList;
import java.util.Collections;
import org.json.JSONObject;

/** Aggregate-only, bounded observations. No image, face count, frame ID, or timestamp output. */
final class RealtimeReport {
    static final class Samples {
        final ArrayList<Long> values = new ArrayList<>();
        int unavailable, overflow;
        void add(long value) {
            if (value < 0) { unavailable++; return; }
            if (values.size() == 10000) { overflow++; return; }
            values.add(value);
        }
        JSONObject json() throws Exception {
            var sorted = new ArrayList<>(values); Collections.sort(sorted);
            int n = sorted.size();
            return new JSONObject().put("n", n).put("unavailable", unavailable).put("overflow", overflow)
                .put("median_ms", n == 0 ? JSONObject.NULL : (sorted.get((n-1)/2)/2.0 + sorted.get(n/2)/2.0)/1e6)
                .put("p95_ms", n == 0 ? JSONObject.NULL : sorted.get((int)Math.ceil(n*0.95)-1)/1e6);
        }
    }
    final String source, mode;
    final Samples firstSdk = new Samples(), warmSdk = new Samples(), firstAdapter = new Samples(), warmAdapter = new Samples();
    final Samples firstTotal = new Samples(), warmTotal = new Samples(), preparation = new Samples(), pipeline = new Samples();
    final Samples previewIntervals = new Samples(), windowTotal = new Samples(), uiIntervals = new Samples();
    private long previousPreview, previousUi;
    private int success, failed, cancelled, notRun, control, skipped, width, height, geometryChanges, droppedMetrics;
    private long initializationNs=-1;
    RealtimeReport(String source, String mode) {
        if (!source.equals("synthetic") && !source.equals("camera")) throw new IllegalArgumentException("source");
        if (!mode.equals("NONE") && !mode.equals("ACCURATE") && !mode.equals("ACCURATE_MIN005")) throw new IllegalArgumentException("mode");
        this.source=source; this.mode=mode;
    }
    synchronized void initialized(long ns) { initializationNs=ns; }
    synchronized void skipped() { skipped++; }
    synchronized void windowFrame(long ns,long total,int dropped) {
        droppedMetrics+=dropped; windowTotal.add(total);
        if(ns<0) {previewIntervals.add(-1);previousPreview=0;return;}
        if(previousPreview!=0) previewIntervals.add(ns-previousPreview); previousPreview=ns;
    }
    synchronized void ui(long ns) { if (previousUi != 0) uiIntervals.add(ns-previousUi); previousUi=ns; }
    synchronized void geometry(int w, int h) {
        if (width != 0 && (width != w || height != h)) geometryChanges++;
        width=w; height=h;
    }
    synchronized void failure() { failed++; }
    synchronized void record(JSONObject measurement, long preparationNs, long pipelineNs) throws Exception {
        preparation.add(preparationNs); pipeline.add(pipelineNs);
        if (mode.equals("NONE")) { if (measurement != null) throw new IllegalArgumentException("control"); control++; return; }
        String status=measurement.getString("status");
        switch (status) {
            case "SUCCESS" -> {
                boolean first=measurement.getString("phase").equals("first-call");
                (first ? firstSdk : warmSdk).add(measurement.getLong("sdk_ns"));
                (first ? firstAdapter : warmAdapter).add(measurement.getLong("adapter_ns"));
                (first ? firstTotal : warmTotal).add(measurement.getLong("total_ns")); success++;
            }
            case "FAILED" -> failed++;
            case "CANCELLED" -> cancelled++;
            case "NOT_RUN" -> notRun++;
            default -> throw new IllegalArgumentException("status");
        }
    }
    synchronized JSONObject json(String end, long durationNs) throws Exception {
        int completed=success+control;
        String result = !end.equals("COMPLETED") ? end : failed+cancelled+notRun>0 ? "INCOMPLETE" : completed==0 ? "NOT_RUN" : "MEASURED";
        return new JSONObject().put("schema", "face-realtime-aggregate-v1").put("adoption", "UNDECIDED")
            .put("source", source).put("mode", mode).put("status", result).put("end_reason", end)
            .put("detector", mode.equals("NONE") ? "no-detector" : MlkitCondition.fromMode(mode).id)
            .put("duration_ms", durationNs/1e6).put("initialization_ms", mode.equals("NONE") || initializationNs<0 ? JSONObject.NULL : initializationNs/1e6)
            .put("width", width).put("height", height).put("geometry_changes", geometryChanges)
            .put("success", success).put("control_frames", control).put("failed", failed).put("cancelled", cancelled).put("not_run", notRun)
            .put("synthetic_busy_skips", skipped).put("camera_dropped_frames", JSONObject.NULL)
            .put("completed_per_second", durationNs > 0 ? completed*1e9/durationNs : JSONObject.NULL)
            .put("first_sdk", firstSdk.json()).put("warm_sdk", warmSdk.json())
            .put("first_adapter", firstAdapter.json()).put("warm_adapter", warmAdapter.json())
            .put("first_total", firstTotal.json()).put("warm_total", warmTotal.json())
            .put("input_preparation", preparation.json()).put("analyzer_pipeline", pipeline.json())
            .put("window_frame_intervals", previewIntervals.json()).put("window_total",windowTotal.json())
            .put("window_metrics_dropped",droppedMetrics).put("ui_vsync_intervals", uiIntervals.json());
    }
}
