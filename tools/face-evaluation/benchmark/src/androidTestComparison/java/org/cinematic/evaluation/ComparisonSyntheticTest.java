package org.cinematic.evaluation;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Generated geometry only. Explicit emulator gate. Never enumerates shared/user media. */
@RunWith(AndroidJUnit4.class)
public final class ComparisonSyntheticTest {
    private final android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final android.content.Context context = instrumentation.getTargetContext();
    private File directory() { return new File(context.getNoBackupFilesDir(), "face-comparison-v1"); }
    private File marker() { return new File(context.getNoBackupFilesDir(), "comparison-synthetic-marker"); }
    private void gate() throws Exception {
        assertEquals("true", InstrumentationRegistry.getArguments().getString("syntheticComparisonApproved"));
        assertTrue("Emulator only", Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        assertEquals("org.cinematic.evaluation.comparison", context.getPackageName());
    }
    private ComparisonModel model(ActivityScenario<ComparisonActivity> scenario) {
        AtomicReference<ComparisonModel> ref = new AtomicReference<>();
        scenario.onActivity(a -> ref.set(new ViewModelProvider(a).get(ComparisonModel.class))); return ref.get();
    }
    private ComparisonModel.State state(ComparisonModel model) {
        AtomicReference<ComparisonModel.State> ref = new AtomicReference<>(); instrumentation.runOnMainSync(() -> ref.set(model.state().getValue())); return ref.get();
    }
    private ComparisonModel.State ready(ComparisonModel model) throws Exception {
        long until = android.os.SystemClock.elapsedRealtime()+90000;
        while (android.os.SystemClock.elapsedRealtime() < until) {
            var s = state(model); if (!s.busy()) return s; Thread.sleep(50);
        }
        fail("Comparison did not become idle"); return null;
    }
    private File image(String name, int width, int height, boolean jpeg) throws Exception {
        File file = new File(context.getCacheDir(), name);
        Bitmap bitmap = Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        canvas.drawColor(Color.BLUE); android.graphics.Paint paint = new android.graphics.Paint();paint.setColor(Color.RED);
        canvas.drawRect(0,0,width/2f,height,paint);
        try(var out=new FileOutputStream(file)){assertTrue(bitmap.compress(jpeg?Bitmap.CompressFormat.JPEG:Bitmap.CompressFormat.PNG,95,out));}
        bitmap.recycle(); return file;
    }
    private byte[] sha(File f) throws Exception { return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f.toPath())); }
    private EditText findEdit(View view) {
        if(view instanceof EditText)return (EditText)view;
        if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++){EditText found=findEdit(group.getChildAt(i));if(found!=null)return found;}
        return null;
    }
    private View findBox(View view) {
        if(view.getClass().getSimpleName().equals("BoxView"))return view;
        if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++){View found=findBox(group.getChildAt(i));if(found!=null)return found;}
        return null;
    }
    private void verifyGeneratedOverlayAndCorruptRecord() throws Exception {
        File db=new File(directory(),"session.json");byte[] original=Files.readAllBytes(db.toPath());
        try {
            // Known geometric rectangle is a rendering fixture, NOT an SDK accuracy result.
            JSONObject fixture=new JSONObject(new String(original,StandardCharsets.UTF_8));
            fixture.getJSONArray("samples").getJSONObject(0).getJSONArray("results").getJSONObject(0)
                .put("boxes",new org.json.JSONArray().put(new org.json.JSONArray(new int[]{40,80,80,120})));
            Files.writeString(db.toPath(),fixture.toString(),StandardCharsets.UTF_8);
            try(ActivityScenario<ComparisonActivity> scenario=ActivityScenario.launch(ComparisonActivity.class)) {
                var state=ready(model(scenario));assertEquals(1,state.samples().length());
                scenario.onActivity(a->{
                    View boxes=findBox(a.getWindow().getDecorView());assertNotNull(boxes);assertTrue(boxes.getWidth()>0 && boxes.getHeight()>0);
                    Bitmap drawn=Bitmap.createBitmap(boxes.getWidth(),boxes.getHeight(),Bitmap.Config.ARGB_8888);
                    boxes.draw(new android.graphics.Canvas(drawn));
                    float scale=Math.min(boxes.getWidth()/320f,boxes.getHeight()/640f);
                    int x=Math.round((boxes.getWidth()-320*scale)/2+40*scale);
                    int y=Math.round((boxes.getHeight()-640*scale)/2+140*scale);
                    boolean green=false;
                    for(int dx=-3;dx<=3;dx++){int value=drawn.getPixel(Math.max(0,Math.min(drawn.getWidth()-1,x+dx)),y);if(Color.green(value)>200 && Color.red(value)<60 && Color.blue(value)<60)green=true;}
                    drawn.recycle();assertTrue("Box edge must align with image fit viewport",green);
                });
            }
            JSONObject corrupt=new JSONObject(new String(original,StandardCharsets.UTF_8));
            corrupt.getJSONArray("samples").getJSONObject(0).getJSONArray("results").getJSONObject(1).put("width",160);
            Files.writeString(db.toPath(),corrupt.toString(),StandardCharsets.UTF_8);
            try(ActivityScenario<ComparisonActivity> scenario=ActivityScenario.launch(ComparisonActivity.class)) {
                ComparisonModel model=model(scenario);long until=android.os.SystemClock.elapsedRealtime()+10000;boolean rejected=false;
                while(android.os.SystemClock.elapsedRealtime()<until){var state=state(model);if(state.busy() && state.message().contains("上書きしません")){rejected=true;break;}Thread.sleep(25);}
                assertTrue("Dimension-corrupt records must lock instead of export",rejected);
            }
        } finally {Files.write(db.toPath(),original);}
    }
    @Test public void generatedImageUiRotationCancelAndPersist() throws Exception {
        gate();
        File root=directory();
        assertTrue("Refuse pre-existing evaluation data", !root.exists() || root.list().length==0);
        assertFalse("Refuse unknown previous session", marker().exists());
        Files.writeString(marker().toPath(),new JSONObject().put("fixture","generated-comparison-v1").put("pid",android.os.Process.myPid()).toString(),StandardCharsets.UTF_8);
        File original=image("comparison-synthetic-rotate.jpg",640,320,true);
        ExifInterface exif=new ExifInterface(original);exif.setAttribute(ExifInterface.TAG_ORIENTATION,Integer.toString(ExifInterface.ORIENTATION_ROTATE_90));exif.saveAttributes();
        byte[] before=sha(original);
        try(ActivityScenario<ComparisonActivity> scenario=ActivityScenario.launch(ComparisonActivity.class)) {
            ComparisonModel model=model(scenario);assertEquals(0,ready(model).samples().length());
            instrumentation.runOnMainSync(()->model.add(Uri.fromFile(original)));
            var result=ready(model);assertEquals(1,result.samples().length());assertEquals(320,result.image().getWidth());assertEquals(640,result.image().getHeight());
            int top=result.image().getPixel(160,80), bottom=result.image().getPixel(160,560);
            assertTrue(Color.red(top)>200 && Color.blue(top)<40);assertTrue(Color.blue(bottom)>200 && Color.red(bottom)<40);
            assertArrayEquals("Original remains byte identical",before,sha(original));
            for(int d=0;d<3;d++)assertEquals("SUCCESS",result.samples().getJSONObject(0).getJSONArray("results").getJSONObject(d).getString("status"));
            scenario.onActivity(a->{EditText input=findEdit(a.getWindow().getDecorView());assertNotNull(input);input.setText("3");});
            scenario.recreate();assertSame(model,model(scenario));
            scenario.onActivity(a->assertEquals("3",findEdit(a.getWindow().getDecorView()).getText().toString()));
            assertEquals(1,ready(model).samples().length());
            instrumentation.runOnMainSync(()->model.add(Uri.fromFile(original)));assertEquals(1,ready(model).samples().length());
            for(int d=0;d<3;d++){final int detector=d;instrumentation.runOnMainSync(()->model.label(detector,0,List.of()));ready(model);}
            JSONObject summary=new JSONObject(ComparisonRecords.aggregate(ready(model).samples()));
            assertEquals(1,summary.getJSONArray("detectors").getJSONObject(0).getInt("scored"));
            assertFalse(summary.toString().contains("evaluation-"));assertFalse(summary.toString().contains("sha256"));
            File large=image("comparison-synthetic-cancel.png",3000,2000,false);
            instrumentation.runOnMainSync(()->{model.add(Uri.fromFile(large));model.cancel();});
            assertEquals("Immediate cancellation does not add image",1,ready(model).samples().length());
            assertTrue(large.delete());
            JSONObject owned = ready(model).samples().getJSONObject(0);
            Files.writeString(marker().toPath(),new JSONObject().put("fixture","generated-comparison-v1").put("pid",android.os.Process.myPid())
                .put("id",owned.getString("id")).put("sha256",owned.getString("sha256")).toString(),StandardCharsets.UTF_8);
        } finally { assertTrue(original.delete()); }
        // Record remains ONLY for the explicitly separate force-stop/restart phase below.
    }
    @Test public void persistedRecordAfterProcessRestart() throws Exception {
        gate();JSONObject ownership=new JSONObject(Files.readString(marker().toPath(),StandardCharsets.UTF_8));
        assertEquals("generated-comparison-v1",ownership.getString("fixture"));
        assertNotEquals("Must run in a fresh process after explicit force-stop",ownership.getInt("pid"),android.os.Process.myPid());
        try(ActivityScenario<ComparisonActivity> scenario=ActivityScenario.launch(ComparisonActivity.class)) {
            var result=ready(model(scenario));assertEquals(1,result.samples().length());assertEquals(320,result.image().getWidth());assertEquals(640,result.image().getHeight());
            assertEquals(ownership.getString("id"),result.samples().getJSONObject(0).getString("id"));
            assertEquals(ownership.getString("sha256"),result.samples().getJSONObject(0).getString("sha256"));
            for(int d=0;d<3;d++)assertEquals(0,result.samples().getJSONObject(0).getJSONObject("labels").getJSONObject(""+d).getInt("missed"));
            assertEquals(1,new JSONObject(ComparisonRecords.aggregate(result.samples())).getJSONArray("detectors").getJSONObject(0).getInt("scored"));
        }
        verifyGeneratedOverlayAndCorruptRecord();
        // Only this marked synthetic session, never an unmarked/private dataset.
        for(File file:directory().listFiles()) {
            assertTrue(file.getName().equals("session.json") || file.getName().equals(ownership.getString("id")+".png"));
            assertTrue(file.delete());
        }
        assertTrue(directory().delete());assertTrue(marker().delete());
    }
}
