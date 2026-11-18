package org.cinematic.evaluation;
import org.junit.Test;
import org.json.JSONObject;
import java.nio.ByteBuffer;
import static org.junit.Assert.*;

public class RealtimeReportTest {
    private JSONObject measurement(String status, String phase) throws Exception {
        return new JSONObject().put("status",status).put("phase",phase).put("sdk_ns",1000000L).put("adapter_ns",2000000L).put("total_ns",3000000L);
    }
    @Test public void zeroObservationsAreNotSuccessOrZeroTime() throws Exception {
        var j=new RealtimeReport("camera","ACCURATE").json("COMPLETED",0);
        assertEquals("NOT_RUN",j.getString("status"));
        assertTrue(j.getJSONObject("warm_sdk").isNull("median_ms"));
        assertTrue(j.isNull("completed_per_second"));
    }
    @Test public void failuresAndCancellationAreNotMeasurements() throws Exception {
        var r=new RealtimeReport("camera","ACCURATE");
        for(String state:new String[]{"FAILED","CANCELLED","NOT_RUN"}) r.record(measurement(state,"first-call"),4,9);
        var j=r.json("COMPLETED",100);
        assertEquals("INCOMPLETE",j.getString("status"));assertEquals(0,j.getInt("success"));
        assertEquals(0,j.getJSONObject("first_sdk").getInt("n"));
        assertEquals("BACKGROUND_CANCELLED",r.json("BACKGROUND_CANCELLED",100).getString("status"));
    }
    @Test public void phasesAndControlsRemainSeparate() throws Exception {
        var r=new RealtimeReport("synthetic","ACCURATE_MIN005");
        r.record(measurement("SUCCESS","first-call"),10,20);r.record(measurement("SUCCESS","warm"),10,20);
        var j=r.json("COMPLETED",100);
        assertEquals(1,j.getJSONObject("first_sdk").getInt("n"));assertEquals(1,j.getJSONObject("warm_sdk").getInt("n"));
        assertEquals(MlkitCondition.fromMode("ACCURATE_MIN005").id,j.getString("detector"));
        var c=new RealtimeReport("synthetic","NONE"); c.record(null,10,20);j=c.json("COMPLETED",100);
        assertEquals(0,j.getInt("success"));assertEquals(1,j.getInt("control_frames"));assertTrue(j.isNull("initialization_ms"));
        assertEquals(0,j.getJSONObject("warm_sdk").getInt("n"));
        assertThrows(IllegalArgumentException.class,()->c.record(measurement("SUCCESS","warm"),1,2));
    }
    @Test public void percentilesAreBoundedAndMissingIntervalsExplicit() throws Exception {
        var s=new RealtimeReport.Samples();s.add(-1);for(int i=1;i<=10001;i++)s.add(i*1000000L);
        var j=s.json();assertEquals(10000,j.getInt("n"));assertEquals(1,j.getInt("overflow"));assertEquals(1,j.getInt("unavailable"));
        assertEquals(5000.5,j.getDouble("median_ms"),0);assertEquals(9500,j.getDouble("p95_ms"),0);
        var r=new RealtimeReport("camera","NONE");r.windowFrame(100,10,0);r.windowFrame(200,20,2);r.ui(100);
        j=r.json("COMPLETED",100);assertEquals(1,j.getJSONObject("window_frame_intervals").getInt("n"));assertEquals(0,j.getJSONObject("ui_vsync_intervals").getInt("n"));
        assertEquals(2,j.getInt("window_metrics_dropped"));assertEquals(2,j.getJSONObject("window_total").getInt("n"));
        assertTrue(j.isNull("camera_dropped_frames"));
    }
    @Test public void paddedRgbaCopiesWithoutMovingBufferAndRejectsTruncation() {
        ByteBuffer b=ByteBuffer.allocate(32);b.position(3);b.put(3,(byte)255);b.put(4,(byte)128);b.put(5,(byte)64);b.put(19,(byte)16);
        int[] pixels=RgbaPixels.copy(b,2,2,16,4);
        assertEquals(0xffff8040,pixels[0]);assertEquals(0xff100000,pixels[2]);assertEquals(3,b.position());
        b.limit(22);assertThrows(IllegalArgumentException.class,()->RgbaPixels.copy(b,2,2,16,4));
        assertThrows(IllegalArgumentException.class,()->RgbaPixels.copy(b,2,2,7,4));
        assertThrows(IllegalArgumentException.class,()->RgbaPixels.copy(b,3000,2,12000,4));
    }
    @Test public void conditionValidationAndGeometryChanges() throws Exception {
        assertThrows(IllegalArgumentException.class,()->new RealtimeReport("photo","ACCURATE"));
        assertThrows(IllegalArgumentException.class,()->new RealtimeReport("camera","FAST"));
        var r=new RealtimeReport("camera","NONE");r.geometry(640,480);r.geometry(640,480);r.geometry(480,640);
        assertEquals(1,r.json("COMPLETED",100).getInt("geometry_changes"));
    }
}
