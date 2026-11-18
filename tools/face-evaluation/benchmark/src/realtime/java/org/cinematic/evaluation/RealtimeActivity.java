package org.cinematic.evaluation;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.FrameMetrics;
import android.view.Window;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.camera.core.*;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Preparation-only probe. Starts only from a local button; never from intent extras. */
public final class RealtimeActivity extends ComponentActivity {
    // Serial across activity recreation: previous native calls drain before a new detector starts.
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private Spinner source, mode;
    private Button start, stop;
    private TextView status;
    private PreviewView preview;
    private SyntheticView synthetic;
    private ProcessCameraProvider provider;
    private Preview cameraPreview;
    private ImageAnalysis analysis;
    private LiveData<CameraState> cameraState;
    private Observer<CameraState> cameraObserver;
    private Window.OnFrameMetricsAvailableListener frameMetrics;
    private Session session;
    private boolean foreground;
    private final class Session {
        final RealtimeReport report;
        final AtomicBoolean busy=new AtomicBoolean();
        volatile boolean active=true;
        Detector detector; // WORKER only, including close
        int calls;
        long initialization, firstInput, lastSynthetic;
        boolean initialized;
        Session(String input, String condition) { report=new RealtimeReport(input,condition); }
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        TextView note=new TextView(this); note.setText("顔検出・独立性能診断（未採用）\n30秒×各条件。画像・顔枠は保存しません。カメラ実行は別承認後。SDK診断通信あり。製品プレビューの性能ではありません。"); root.addView(note);
        source=spinner(root,new String[]{"合成入力（精度・実カメラ性能ではない）","背面カメラ（実行承認後のみ）"});
        mode=spinner(root,new String[]{"検出なし・入力準備あり","ACCURATE / min0.1","ACCURATE / min0.05"});
        LinearLayout buttons=new LinearLayout(this); root.addView(buttons);
        start=new Button(this); start.setText("30秒計測を開始"); buttons.addView(start);
        stop=new Button(this); stop.setText("停止"); stop.setEnabled(false); buttons.addView(stop);
        FrameLayout image=new FrameLayout(this); root.addView(image,new LinearLayout.LayoutParams(-1,0,1));
        preview=new PreviewView(this); preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        preview.setScaleType(PreviewView.ScaleType.FIT_CENTER); image.addView(preview,new FrameLayout.LayoutParams(-1,-1));
        synthetic=new SyntheticView(); image.addView(synthetic,new FrameLayout.LayoutParams(-1,-1));
        ScrollView scroll=new ScrollView(this); status=new TextView(this); status.setTextIsSelectable(true); status.setText("未計測。カメラは開いていません。"); scroll.addView(status);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,(int)(180*getResources().getDisplayMetrics().density))); setContentView(root);
        start.setOnClickListener(v -> startRun()); stop.setOnClickListener(v -> finishRun("CANCELLED"));
    }
    private Spinner spinner(LinearLayout root,String[] choices) {
        Spinner s=new Spinner(this); s.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,choices)); root.addView(s); return s;
    }
    @Override protected void onStart() { super.onStart(); foreground=true; }
    @Override protected void onStop() { foreground=false; finishRun("BACKGROUND_CANCELLED"); super.onStop(); }
    private boolean current(Session s) { return session==s && s.active && foreground; }
    private void startRun() {
        if (session!=null || !foreground) return;
        boolean camera=source.getSelectedItemPosition()==1;
        if (camera && checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA},41);
            status.setText("許可後に、開始ボタンをもう一度押してください。自動では開始しません。"); return;
        }
        String condition=new String[]{"NONE","ACCURATE","ACCURATE_MIN005"}[mode.getSelectedItemPosition()];
        Session s=new Session(camera ? "camera" : "synthetic",condition); session=s;
        start.setEnabled(false); source.setEnabled(false); mode.setEnabled(false); stop.setEnabled(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        preview.setVisibility(camera ? View.VISIBLE : View.GONE); synthetic.setVisibility(camera ? View.GONE : View.VISIBLE);
        status.setText("準備中。最初の入力から30秒。停止時は進行中の検出完了を待ちます。");
        Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
            @Override public void doFrame(long time) {
                if (!current(s)) return;
                s.report.ui(time);
                if (!camera && time-s.lastSynthetic>=33_333_333L) {
                    s.lastSynthetic=time; synthetic.tick++; synthetic.invalidate();
                    firstInput(s);
                    if(s.busy.compareAndSet(false,true)) WORKER.execute(() -> process(s,null)); else s.report.skipped();
                }
                Choreographer.getInstance().postFrameCallback(this);
            }
        });
        main.postDelayed(() -> {if(current(s) && s.firstInput==0) finishRun("NO_INPUT");},15000);
        if(camera) openCamera(s);
    }
    private void firstInput(Session s) { // main only
        if (!current(s) || s.firstInput!=0) return;
        s.firstInput=SystemClock.elapsedRealtimeNanos();
        frameMetrics=(window,metrics,dropped) -> {
            if(current(s)) s.report.windowFrame(metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP),
                metrics.getMetric(FrameMetrics.TOTAL_DURATION),dropped);
        };
        getWindow().addOnFrameMetricsAvailableListener(frameMetrics,main);
        status.setText("計測中（30秒）。停止・背景移行は中断として記録します。");
        main.postDelayed(() -> {if(current(s)) finishRun("COMPLETED");},30000);
    }
    private void openCamera(Session s) {
        var future=ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            if(!current(s)) return;
            try {
                provider=future.get();
                var resolution=new ResolutionSelector.Builder().setResolutionStrategy(new ResolutionStrategy(new android.util.Size(640,480),ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build();
                cameraPreview=new Preview.Builder().setResolutionSelector(resolution).build();
                cameraPreview.setSurfaceProvider(preview.getSurfaceProvider());
                analysis=new ImageAnalysis.Builder().setResolutionSelector(resolution)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                analysis.setAnalyzer(WORKER,image -> {
                    if(!s.active) {image.close();return;}
                    main.post(() -> firstInput(s));
                    process(s,image);
                });
                Camera camera=provider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,cameraPreview,analysis);
                cameraState=camera.getCameraInfo().getCameraState();
                cameraObserver=state -> {if(current(s) && state.getError()!=null) finishRun("CAMERA_ERROR");};
                cameraState.observe(this,cameraObserver);
            } catch(Exception e) { finishRun("CAMERA_UNAVAILABLE"); }
        },getMainExecutor());
    }
    private void process(Session s, ImageProxy image) {
        Bitmap bitmap=null;
        try {
            if(!s.active) return;
            if(!s.initialized) {
                long begin=SystemClock.elapsedRealtimeNanos();
                if(!s.report.mode.equals("NONE")) s.detector=DetectorFactory.create(getApplicationContext(),s.report.mode);
                s.initialization=SystemClock.elapsedRealtimeNanos()-begin; s.report.initialized(s.initialization); s.initialized=true;
            }
            long begin=SystemClock.elapsedRealtimeNanos();
            if(image==null) bitmap=syntheticBitmap();
            else {
                var plane=image.getPlanes()[0];
                int[] pixels=RgbaPixels.copy(plane.getBuffer(),image.getWidth(),image.getHeight(),plane.getRowStride(),plane.getPixelStride());
                bitmap=Bitmap.createBitmap(pixels,image.getWidth(),image.getHeight(),Bitmap.Config.ARGB_8888);
                int rotation=image.getImageInfo().getRotationDegrees();
                if(rotation!=0) {
                    Matrix m=new Matrix(); m.postRotate(rotation);
                    Bitmap upright=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),m,false);
                    if(upright!=bitmap) bitmap.recycle(); bitmap=upright;
                }
            }
            bitmap.setHasAlpha(false); s.report.geometry(bitmap.getWidth(),bitmap.getHeight());
            long prepared=SystemClock.elapsedRealtimeNanos();
            // Hold ImageProxy until the call completes: CameraX then drops to latest without queuing work.
            JSONObject measured=s.detector==null ? null : Measurement.run(s.detector,bitmap,"synthetic-realtime",
                s.calls==0 ? "first-call" : "warm",s.calls,s.initialization);
            s.calls++;
            s.report.record(measured,prepared-begin,SystemClock.elapsedRealtimeNanos()-begin);
        } catch(Exception | OutOfMemoryError e) {
            s.report.failure(); main.post(() -> {if(current(s)) finishRun("FAILED");});
        } finally {
            if(bitmap!=null) bitmap.recycle();
            if(image!=null) image.close();
            s.busy.set(false);
        }
    }
    static Bitmap syntheticBitmap() {
        Bitmap b=Bitmap.createBitmap(640,480,Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(b); c.drawColor(Color.DKGRAY); Paint p=new Paint();p.setColor(Color.CYAN);c.drawRect(100,100,240,300,p);b.setHasAlpha(false);return b;
    }
    private void finishRun(String reason) {
        Session s=session; if(s==null || !s.active) return;
        s.active=false; stop.setEnabled(false);
        if(frameMetrics!=null) {getWindow().removeOnFrameMetricsAvailableListener(frameMetrics);frameMetrics=null;}
        if(cameraState!=null && cameraObserver!=null) cameraState.removeObserver(cameraObserver);
        cameraState=null; cameraObserver=null;
        long ended=SystemClock.elapsedRealtimeNanos();
        if(analysis!=null) analysis.clearAnalyzer();
        if(provider!=null && cameraPreview!=null && analysis!=null) provider.unbind(cameraPreview,analysis);
        analysis=null; cameraPreview=null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        status.setText("入力停止済み。進行中の検出を完了して解放中…");
        WORKER.execute(() -> {
            String summary;
            boolean released=false;
            try {
                if(s.detector!=null) {s.detector.close();s.detector=null;}
                released=true;
                long drained=SystemClock.elapsedRealtimeNanos();
                JSONObject report=s.report.json(reason,s.firstInput==0 ? 0 : drained-s.firstInput);
                report.put("input_window_ms",s.firstInput==0 ? JSONObject.NULL : (ended-s.firstInput)/1e6);
                report.put("drain_ms",(drained-ended)/1e6);
                // No image, boxes, timestamps, URI, device identity or exception text is persisted.
                File dir=new File(getNoBackupFilesDir(),"realtime-aggregates");
                if(!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException();
                summary=report.toString(2);
                Files.write(new File(dir,UUID.randomUUID()+".json").toPath(),summary.getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
            } catch(Exception e) {summary="集計の保存または検出器の解放に失敗。未合格です。";}
            String text=summary;
            boolean canRestart=released;
            main.post(() -> {if(session==s) {if(canRestart) session=null;status.setText(text);start.setEnabled(canRestart);source.setEnabled(canRestart);mode.setEnabled(canRestart);}});
        });
    }
    private final class SyntheticView extends View {
        final Paint paint=new Paint(); int tick;
        SyntheticView() { super(RealtimeActivity.this); }
        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.DKGRAY);paint.setColor(Color.CYAN);
            canvas.drawRect(100,100,240,300,paint);paint.setColor(Color.YELLOW);
            canvas.drawRect(tick%Math.max(1,getWidth()),0,tick%Math.max(1,getWidth())+20,30,paint);
        }
    }
}
