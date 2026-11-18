package org.cinematic.evaluation;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import android.graphics.Bitmap;
import java.util.List;
import androidx.test.core.app.ActivityScenario;
import androidx.lifecycle.Lifecycle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import java.util.concurrent.atomic.AtomicReference;

/** No camera, private input, network calls, or model inference. Not device performance evidence. */
@RunWith(AndroidJUnit4.class)
public class RealtimeSyntheticTest {
    private static View find(View root,String text) {
        if(root instanceof TextView t && t.getText().toString().startsWith(text)) return root;
        if(root instanceof ViewGroup g) for(int i=0;i<g.getChildCount();i++) {View result=find(g.getChildAt(i),text);if(result!=null)return result;}
        return null;
    }
    @Test public void launchDoesNotStartAndBackgroundDrainsControlRun() throws Exception {
        try(var scenario=ActivityScenario.launch(RealtimeActivity.class)) {
            scenario.onActivity(a -> {
                View root=a.getWindow().getDecorView();
                assertNotNull(find(root,"未計測"));assertFalse(find(root,"停止").isEnabled());
                ((Button)find(root,"30秒計測を開始")).performClick();
            });
            // Stop after input has actually started, not after an arbitrary fixed sleep.
            AtomicReference<Boolean> started=new AtomicReference<>(false);
            long deadline=android.os.SystemClock.uptimeMillis()+5000;
            while(!started.get() && android.os.SystemClock.uptimeMillis()<deadline) {
                scenario.onActivity(a -> started.set(find(a.getWindow().getDecorView(),"計測中")!=null));Thread.sleep(20);
            }
            assertTrue("synthetic first input",started.get());
            scenario.moveToState(Lifecycle.State.CREATED);
            scenario.moveToState(Lifecycle.State.RESUMED);
            AtomicReference<String> summary=new AtomicReference<>();deadline=android.os.SystemClock.uptimeMillis()+5000;
            while(summary.get()==null && android.os.SystemClock.uptimeMillis()<deadline) {
                scenario.onActivity(a -> {
                    View root=a.getWindow().getDecorView();View value=find(root,"{");
                    if(value instanceof TextView t) summary.set(t.getText().toString());
                });Thread.sleep(20);
            }
            assertNotNull("drained aggregate",summary.get());
            var result=new org.json.JSONObject(summary.get());
            assertEquals("synthetic",result.getString("source"));assertEquals("NONE",result.getString("mode"));
            assertEquals("BACKGROUND_CANCELLED",result.getString("status"));assertEquals(0,result.getInt("success"));
            scenario.onActivity(a -> {View root=a.getWindow().getDecorView();assertTrue(find(root,"30秒計測を開始").isEnabled());assertFalse(find(root,"停止").isEnabled());});
        }
    }
    @Test public void syntheticInputFlowsThroughExistingMeasurement() throws Exception {
        Bitmap input=RealtimeActivity.syntheticBitmap();
        try {
            Detector fake=new Detector() {
                public String id(){return "synthetic-fake";}
                public List<float[]> detect(Bitmap b){assertFalse(b.hasAlpha());assertEquals(640,b.getWidth());return List.of();}
                public void close(){}
            };
            var report=new RealtimeReport("synthetic","ACCURATE");
            for(int n=0;n<2;n++) {
                var m=Measurement.run(fake,input,"synthetic-realtime",n==0?"first-call":"warm",n,0);
                assertEquals("SUCCESS",m.getString("status"));report.record(m,0,m.getLong("total_ns"));
            }
            assertFalse(input.isRecycled());assertEquals(2,report.json("COMPLETED",1000).getInt("success"));
        } finally {input.recycle();}
    }
}
