package org.cinematic.evaluation;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
public final class ComparisonRecordsTest {
    private JSONArray fixture() throws Exception {
        String id = "evaluation-0123456789abcdef";
        JSONArray results = new JSONArray();
        for (String detector : ComparisonRecords.IDS) results.put(new JSONObject().put("detector", detector).put("sample", id)
            .put("geometry", "upright-srgb-argb8888-v1").put("phase", "warm").put("status", "SUCCESS")
            .put("width", 640).put("height", 480).put("sdk_ns", 4_000_000L).put("adapter_ns", 6_000_000L)
            .put("boxes", new JSONArray().put(new JSONArray(new int[]{5, 10, 20, 30}))));
        return new JSONArray().put(new JSONObject().put("id", id).put("sha256", "a".repeat(64)).put("decode_ns", 1L)
            .put("results", results).put("labels", new JSONObject()));
    }
    private void label(JSONArray a, int d) throws Exception {
        a.getJSONObject(0).getJSONObject("labels").put(""+d, new JSONObject().put("missed",2).put("false_indices",new JSONArray().put(0)));
    }
    @Test public void unscoredNeverBecomesZeroMisses() throws Exception {
        JSONObject row = new JSONObject(ComparisonRecords.aggregate(fixture())).getJSONArray("detectors").getJSONObject(0);
        assertEquals(0, row.getInt("scored")); assertEquals(1, row.getInt("unscored")); assertTrue(row.getJSONObject("warm").isNull("median_adapter_ms"));
    }
    @Test public void labelsAndAllowlist() throws Exception {
        JSONArray samples = fixture(); label(samples,0);
        String value = ComparisonRecords.aggregate(samples);
        JSONObject row = new JSONObject(value).getJSONArray("detectors").getJSONObject(0);
        assertEquals(1,row.getInt("scored")); assertEquals(2,row.getInt("missed")); assertEquals(1,row.getInt("false_positive"));
        assertEquals(6,row.getJSONObject("warm").getDouble("median_adapter_ms"),.001); assertEquals(4,row.getJSONObject("warm").getDouble("median_sdk_ms"),.001);
        assertEquals(0,row.getJSONObject("first_call").getInt("count"));
        for (String forbidden : new String[]{"evaluation-", "sha256", "boxes", "sample", "uri"}) assertFalse(value.contains(forbidden));
    }
    @Test public void firstAndWarmAreSeparate() throws Exception {
        JSONArray samples=fixture(); label(samples,0); samples.getJSONObject(0).getJSONArray("results").getJSONObject(0).put("phase","first-call");
        JSONObject row=new JSONObject(ComparisonRecords.aggregate(samples)).getJSONArray("detectors").getJSONObject(0);
        assertEquals(1,row.getJSONObject("first_call").getInt("count"));assertEquals(0,row.getJSONObject("warm").getInt("count"));
    }
    @Test public void statusesRemainDistinct() throws Exception {
        JSONArray samples=fixture();JSONArray results=samples.getJSONObject(0).getJSONArray("results");
        String[] statuses={"FAILED","CANCELLED","NOT_RUN"}, fields={"failed","cancelled","not_run"};
        for(int i=0;i<3;i++)results.getJSONObject(i).put("status",statuses[i]).put("boxes",new JSONArray());
        JSONArray rows=new JSONObject(ComparisonRecords.aggregate(samples)).getJSONArray("detectors");
        for(int i=0;i<3;i++){assertEquals(1,rows.getJSONObject(i).getInt(fields[i]));assertEquals(0,rows.getJSONObject(i).getInt("scored"));}
    }
    @Test public void duplicatePixelsRejected() throws Exception {
        JSONArray samples=fixture();samples.put(new JSONObject(samples.getJSONObject(0).toString()).put("id","evaluation-fedcba9876543210"));
        assertThrows(Exception.class,()->ComparisonRecords.aggregate(samples));
    }
    @Test public void duplicateFalseIndexRejected() throws Exception {
        JSONArray samples=fixture();label(samples,0);samples.getJSONObject(0).getJSONObject("labels").getJSONObject("0").getJSONArray("false_indices").put(0);
        assertThrows(Exception.class,()->ComparisonRecords.aggregate(samples));
    }
    @Test public void wrongDetectorRejected() throws Exception {
        JSONArray samples=fixture();samples.getJSONObject(0).getJSONArray("results").getJSONObject(0).put("detector","different-settings");
        assertThrows(Exception.class,()->ComparisonRecords.aggregate(samples));
    }
    @Test public void blankOrNegativeMissedRejected() throws Exception {
        for(Object value:new Object[]{"",-1,.5}) {
            JSONArray samples=fixture();label(samples,0);samples.getJSONObject(0).getJSONObject("labels").getJSONObject("0").put("missed",value);
            assertThrows(Exception.class,()->ComparisonRecords.aggregate(samples));
        }
    }
    @Test public void failedCannotHaveLabel() throws Exception {
        JSONArray samples=fixture();label(samples,0);samples.getJSONObject(0).getJSONArray("results").getJSONObject(0).put("status","FAILED").put("boxes",new JSONArray());
        assertThrows(Exception.class,()->ComparisonRecords.aggregate(samples));
    }
    @Test public void detectorDimensionsMustMatch() throws Exception {
        JSONArray samples=fixture();samples.getJSONObject(0).getJSONArray("results").getJSONObject(1).put("width",320);
        assertThrows(Exception.class,()->ComparisonRecords.aggregate(samples));
    }
}
