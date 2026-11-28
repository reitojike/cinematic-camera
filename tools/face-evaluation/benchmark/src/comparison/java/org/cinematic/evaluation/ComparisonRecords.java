package org.cinematic.evaluation;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
/** Pure validator and aggregate: no Android, images or URI. */
public final class ComparisonRecords {
    static final String CONFIG = "upright-srgb-4mp-black-alpha-v1-bundled16.1.7-yunet2023mar";
    static final String[] MODES = {"FAST", "ACCURATE", "MULTI"};
    static final String[] IDS = {"mlkit-16.1.7-FAST-min0.1-no-tracking", "mlkit-16.1.7-ACCURATE-min0.1-no-tracking", "yunet-2023mar-8f2383e4-opencv4.13.0-pc-multi-v1"};
    private ComparisonRecords() {}
    public static void validate(JSONArray samples) throws Exception {
        if (samples.length() > 64) throw new IllegalArgumentException();
        var ids = new java.util.HashSet<String>(); var hashes = new java.util.HashSet<String>();
        for (int i = 0; i < samples.length(); i++) {
            JSONObject sample = samples.getJSONObject(i);
            String id = sample.getString("id"), hash = sample.getString("sha256");
            if (!id.matches("evaluation-[0-9a-f]{16}") || !ids.add(id) || !hash.matches("[0-9a-f]{64}") || !hashes.add(hash)) throw new IllegalArgumentException();
            JSONArray results = sample.getJSONArray("results");
            if (results.length() != 3 || sample.getLong("decode_ns") < 0) throw new IllegalArgumentException();
            JSONObject labels = sample.getJSONObject("labels");
            var keys = labels.keys(); while (keys.hasNext()) if (!List.of("0", "1", "2").contains(keys.next())) throw new IllegalArgumentException();
            int expectedWidth = -1, expectedHeight = -1;
            for (int d = 0; d < 3; d++) {
                JSONObject result = results.getJSONObject(d);
                if (!IDS[d].equals(result.getString("detector")) || !id.equals(result.getString("sample")) || !"upright-srgb-argb8888-v1".equals(result.getString("geometry"))) throw new IllegalArgumentException();
                if (!List.of("first-call", "warm").contains(result.getString("phase"))) throw new IllegalArgumentException();
                String status = result.getString("status");
                if (!List.of("SUCCESS", "FAILED", "CANCELLED", "NOT_RUN").contains(status)) throw new IllegalArgumentException();
                JSONArray boxes = result.getJSONArray("boxes");
                if (boxes.length() > 10000 || (!status.equals("SUCCESS") && boxes.length() != 0)) throw new IllegalArgumentException();
                if (status.equals("SUCCESS")) {
                    int w = result.getInt("width"), h = result.getInt("height");
                    if (result.getDouble("width") != w || result.getDouble("height") != h) throw new IllegalArgumentException();
                    if (expectedWidth != -1 && (expectedWidth != w || expectedHeight != h)) throw new IllegalArgumentException();
                    expectedWidth = w; expectedHeight = h;
                    if (w < 1 || h < 1 || (long)w*h > 4_000_000 || result.getLong("sdk_ns") < 0 || result.getLong("adapter_ns") < 0) throw new IllegalArgumentException();
                }
                for (int n = 0; n < boxes.length(); n++) {
                    JSONArray box = boxes.getJSONArray(n); if (box.length() != 4) throw new IllegalArgumentException();
                    for (int k = 0; k < 4; k++) if (!Double.isFinite(box.getDouble(k))) throw new IllegalArgumentException();
                    if (box.getDouble(2) <= 0 || box.getDouble(3) <= 0) throw new IllegalArgumentException();
                }
                JSONObject label = labels.optJSONObject(Integer.toString(d));
                if (label != null) {
                    Object value = label.get("missed");
                    if (!(value instanceof Number) || label.getDouble("missed") != label.getInt("missed") || label.getInt("missed") < 0 || label.getInt("missed") > 10000 || !status.equals("SUCCESS")) throw new IllegalArgumentException();
                    var seen = new java.util.HashSet<Integer>(); JSONArray falseBoxes = label.getJSONArray("false_indices");
                    for (int n = 0; n < falseBoxes.length(); n++) {
                        int index = falseBoxes.getInt(n);
                        if (!(falseBoxes.get(n) instanceof Number) || falseBoxes.getDouble(n) != index || index < 0 || index >= boxes.length() || !seen.add(index)) throw new IllegalArgumentException();
                    }
                } else if (labels.has(Integer.toString(d))) throw new IllegalArgumentException();
            }
        }
    }
    /** Fixed allowlist only. No source records, identifiers, boxes, URI, or hashes are exported. */
    public static String aggregate(JSONArray samples) throws Exception {
        validate(samples);
        JSONArray rows = new JSONArray();
        for (int d = 0; d < 3; d++) {
            int scored = 0, unscored = 0, failed = 0, cancelled = 0, notRun = 0, detected = 0, missed = 0, fp = 0;
            List<Long> first = new ArrayList<>(), warm = new ArrayList<>(), sdkFirst = new ArrayList<>(), sdkWarm = new ArrayList<>();
            for (int i = 0; i < samples.length(); i++) {
                JSONObject sample = samples.getJSONObject(i), result = sample.getJSONArray("results").getJSONObject(d);
                switch (result.getString("status")) {
                    case "FAILED": failed++; continue;
                    case "CANCELLED": cancelled++; continue;
                    case "NOT_RUN": notRun++; continue;
                    case "SUCCESS": break;
                    default: throw new IllegalArgumentException();
                }
                JSONObject label = sample.getJSONObject("labels").optJSONObject(Integer.toString(d));
                if (label == null) { unscored++; continue; }
                scored++; detected += result.getJSONArray("boxes").length(); missed += label.getInt("missed");
                fp += label.getJSONArray("false_indices").length(); boolean cold = result.getString("phase").equals("first-call");
                (cold ? first : warm).add(result.getLong("adapter_ns")); (cold ? sdkFirst : sdkWarm).add(result.getLong("sdk_ns"));
            }
            rows.put(new JSONObject().put("detector", MODES[d]).put("scored", scored).put("unscored", unscored)
                .put("failed", failed).put("cancelled", cancelled).put("not_run", notRun).put("detected", detected)
                .put("missed", missed).put("false_positive", fp).put("first_call", timing(first, sdkFirst)).put("warm", timing(warm, sdkWarm)));
        }
        return new JSONObject().put("schema", 1).put("config", CONFIG).put("detectors", rows).toString(2);
    }
    private static Object median(List<Long> values) {
        java.util.Collections.sort(values);
        return values.isEmpty() ? JSONObject.NULL : (values.get((values.size()-1)/2)/2.0 + values.get(values.size()/2)/2.0)/1_000_000.0;
    }
    private static JSONObject timing(List<Long> adapter, List<Long> sdk) throws Exception {
        return new JSONObject().put("count", adapter.size()).put("median_adapter_ms", median(adapter)).put("median_sdk_ms", median(sdk));
    }
}
