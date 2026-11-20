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
    private String hex(byte[] bytes) {
        StringBuilder out=new StringBuilder();for(byte value:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",value & 255));return out.toString();
    }
    private void awaitLatch(java.util.concurrent.CountDownLatch latch) {
        try { if(!latch.await(20,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Transaction fixture timeout"); }
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
    }
    private long pngCount() {
        File[] files=directory().listFiles();assertNotNull(files);
        return java.util.Arrays.stream(files).filter(f->f.getName().endsWith(".png")).count();
    }
    @Test public void cancelAfterPngAndSerializeOwnerReplacement() throws Exception {
        gate();assertTrue(!directory().exists() || directory().list().length==0);assertFalse(marker().exists());
        File source=image("comparison-synthetic-transaction.png",640,320,false);
        var app=(android.app.Application)context.getApplicationContext();
        for(boolean clear:new boolean[]{false,true}) {
            var reached=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
            AtomicReference<ComparisonModel> old=new AtomicReference<>(), next=new AtomicReference<>();
            try {
                instrumentation.runOnMainSync(()->old.set(new ComparisonModel(app,()->{reached.countDown();awaitLatch(release);})));
                assertEquals(0,ready(old.get()).samples().length());
                instrumentation.runOnMainSync(()->old.get().add(Uri.fromFile(source)));
                assertTrue("Must reach actual PNG-finished boundary",reached.await(20,java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(1,pngCount());
                if(clear) {
                    instrumentation.runOnMainSync(()->{old.get().onCleared();next.set(new ComparisonModel(app));});
                    assertTrue("New owner recovery is queued behind old transaction",state(next.get()).busy());
                    assertEquals("New owner must not delete old pending PNG",1,pngCount());
                } else instrumentation.runOnMainSync(()->old.get().cancel());
                release.countDown();
                assertEquals(0,ready(clear?next.get():old.get()).samples().length());
                assertEquals("Cancelled uncommitted PNG is removed",0,pngCount());
                assertFalse(new File(directory(),"session.json").exists());
            } finally {
                release.countDown();
                instrumentation.runOnMainSync(()->{if(!clear && old.get()!=null)old.get().onCleared();if(next.get()!=null)next.get().onCleared();});
            }
        }
        assertTrue(source.delete());assertTrue(directory().delete());
    }
    @Test public void privateRunnerRejectsVerifiedDuplicatePixels() throws Exception {
        gate();File root=new File(context.getFilesDir(),"evaluation-input");assertFalse(root.exists());assertTrue(root.mkdir());
        String[] ids={"evaluation-1111111111111111","evaluation-2222222222222222"};
        File output=new File(context.getFilesDir(),"evaluation-results-3333333333333333.jsonl");assertFalse(output.exists());
        byte[] pixels={1,2,3,(byte)255};String hash=hex(MessageDigest.getInstance("SHA-256").digest(pixels));
        try {
            org.json.JSONArray samples=new org.json.JSONArray();
            for(String id:ids){Files.write(new File(root,id+".rgba").toPath(),pixels);samples.put(new JSONObject().put("id",id).put("width",1).put("height",1).put("sha256",hash));}
            byte[] manifest=new JSONObject().put("schema",1).put("geometry","upright-srgb-argb8888-v1").put("revision","a".repeat(64)).put("samples",samples).toString().getBytes(StandardCharsets.UTF_8);
            Files.write(new File(root,"manifest.json").toPath(),manifest);
            android.os.Bundle args=new android.os.Bundle();args.putString("datasetRunApproved","true");args.putString("runId","3333333333333333");
            args.putString("manifestSha256",hex(MessageDigest.getInstance("SHA-256").digest(manifest)));
            IllegalArgumentException error=assertThrows(IllegalArgumentException.class,()->PrivateDatasetRun.run(context,args));
            assertEquals("Duplicate pixel content",error.getMessage());assertFalse("Rejected before output construction",output.exists());
        } finally {
            for(String id:ids)assertTrue(new File(root,id+".rgba").delete());assertTrue(new File(root,"manifest.json").delete());assertTrue(root.delete());
            if(output.exists())assertTrue(output.delete());
        }
    }
}
