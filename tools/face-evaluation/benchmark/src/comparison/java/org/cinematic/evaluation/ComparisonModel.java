package org.cinematic.evaluation;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.SystemClock;
import android.util.AtomicFile;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** A single serial owner. No URI/path is persisted, logged, or passed to any SDK. */
public final class ComparisonModel extends AndroidViewModel {
    static final String CONFIG = ComparisonRecords.CONFIG;
    static final String[] MODES = {"FAST", "ACCURATE", "MULTI"};
    public record State(JSONArray samples, Bitmap image, int selected, boolean busy, String message) {}
    private final MutableLiveData<State> state = new MutableLiveData<>(new State(new JSONArray(), null, -1, true, "読み込み中"));
    // One process/store queue, including old-owner drain and new-owner recovery/cleanup.
    private static final ExecutorService STORE_WORKER = Executors.newSingleThreadExecutor();
    private static ComparisonModel activeOwner; // Accessed only on STORE_WORKER.
    private final Runnable beforeImageCommit;
    private volatile boolean retired;
    private final File directory;
    private final AtomicFile database;
    private final Detector[] detectors = new Detector[3];
    private final int[] calls = new int[3];
    private final long[] initialization = new long[3];
    private JSONArray samples = new JSONArray();
    private Bitmap displayed;
    private int selected = -1;
    private volatile boolean cancel, cleared;
    private boolean busy = true; // main-thread submission gate; worker never changes it
    public ComparisonModel(Application app) { this(app, () -> {}); }
    // Package-private deterministic transaction seam for generated instrumentation fixtures only.
    ComparisonModel(Application app, Runnable beforeImageCommit) {
        super(app);
        this.beforeImageCommit = beforeImageCommit;
        directory = new File(app.getNoBackupFilesDir(), "face-comparison-v1");
        database = new AtomicFile(new File(directory, "session.json"));
        STORE_WORKER.execute(() -> {
            if (cleared) return;
            if (activeOwner != null && activeOwner != this) {
                activeOwner.retired = true;
                activeOwner.publish("別の評価画面へ引き継ぎました。この画面は更新できません", true);
            }
            activeOwner = this;
            try {
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException();
                if (database.getBaseFile().exists() || new File(database.getBaseFile().getPath() + ".bak").exists()) {
                    if (database.getBaseFile().length() > 8_000_000) throw new IllegalStateException();
                    JSONObject saved = new JSONObject(new String(database.readFully(), StandardCharsets.UTF_8));
                    if (saved.getInt("schema") != 1 || !CONFIG.equals(saved.getString("config"))) throw new IllegalStateException();
                    samples = saved.getJSONArray("samples");
                    ComparisonRecords.validate(samples);
                    for (int i = 0; i < samples.length(); i++) { load(i); displayed.recycle(); displayed = null; }
                    if (samples.length() > 0) load(0);
                }
                cleanOrphans();
                publish("端末内評価専用・未評価は集計しません");
            } catch (Exception | OutOfMemoryError e) { publish("記録を読み込めません。既存データは上書きしません", true); }
        });
    }
    private void executeOwned(Runnable operation) {
        STORE_WORKER.execute(() -> {
            if (cleared || retired || activeOwner != this) return;
            operation.run();
        });
    }
    public LiveData<State> state() { return state; }
    private boolean begin(String message) {
        if (busy || cleared || retired) return false;
        busy = true; cancel = false;
        State old = state.getValue();
        state.setValue(new State(old.samples(), old.image(), old.selected(), true, message));
        return true;
    }
    private void publish(String message) { publish(message, false); }
    private void publish(String message, boolean locked) {
        try {
            State next = new State(new JSONArray(samples.toString()), displayed, selected, locked, message);
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (!cleared) { busy = locked; state.setValue(next); }
            });
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    public void cancel() { cancel = true; }
    public void select(int index) {
        if (!begin("読み込み中")) return;
        executeOwned(() -> {
            try { load(index); publish("枠を確認して、検出器ごとに判定を保存してください"); }
            catch (Exception | OutOfMemoryError e) { publish("画像を検証できません。集計する前に再選択してください", true); }
        });
    }
    private static String hash(Bitmap b) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(ByteBuffer.allocate(8).putInt(b.getWidth()).putInt(b.getHeight()).array());
        int[] row = new int[b.getWidth()];
        ByteBuffer bytes = ByteBuffer.allocate(row.length * 4);
        for (int y = 0; y < b.getHeight(); y++) {
            b.getPixels(row, 0, row.length, 0, y, row.length, 1);
            bytes.clear(); for (int v : row) bytes.putInt(v); digest.update(bytes.array());
        }
        StringBuilder out = new StringBuilder();
        for (byte v : digest.digest()) out.append(String.format(java.util.Locale.ROOT, "%02x", v & 255));
        return out.toString();
    }
    private File imageFile(JSONObject sample) throws Exception {
        String id = sample.getString("id");
        if (!id.matches("evaluation-[0-9a-f]{16}")) throw new IllegalStateException();
        return new File(directory, id + ".png");
    }
    private void load(int index) throws Exception {
        JSONObject sample = samples.getJSONObject(index);
        File source = imageFile(sample);
        // AtomicFile restores any interrupted legacy backup before ImageDecoder opens the base.
        try (var recovery = new AtomicFile(source).openRead()) { /* recovery only */ }
        Bitmap b = ImageDecoder.decodeBitmap(ImageDecoder.createSource(source), (d, info, src) -> {
            if ((long) info.getSize().getWidth() * info.getSize().getHeight() > 4_000_000) throw new IllegalArgumentException();
            d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            d.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
        });
        if (!hash(b).equals(sample.getString("sha256"))) { b.recycle(); throw new IllegalStateException(); }
        for (int i = 0; i < 3; i++) {
            JSONObject result = sample.getJSONArray("results").getJSONObject(i);
            if ("SUCCESS".equals(result.getString("status")) &&
                (result.getInt("width") != b.getWidth() || result.getInt("height") != b.getHeight())) {
                b.recycle(); throw new IllegalStateException();
            }
        }
        displayed = b; selected = index;
    }
    private void cleanOrphans() throws Exception {
        var known = new java.util.HashSet<String>();
        for (int i = 0; i < samples.length(); i++) known.add(imageFile(samples.getJSONObject(i)).getName());
        File[] files = directory.listFiles();
        if (files == null) throw new IllegalStateException();
        for (File file : files) {
            if (file.getName().matches("evaluation-[0-9a-f]{16}\\.png(?:\\.new|\\.bak)?") && !known.contains(file.getName())) {
                if (!file.delete()) throw new IllegalStateException();
            }
        }
    }
    private void save(JSONArray replacement) throws Exception {
        ComparisonRecords.validate(replacement);
        byte[] bytes = new JSONObject().put("schema", 1).put("config", CONFIG).put("samples", replacement).toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 8_000_000) throw new IllegalStateException();
        var stream = database.startWrite();
        try {
            if (cancel || cleared || retired) throw new java.util.concurrent.CancellationException();
            stream.write(bytes);
            if (cancel || cleared || retired) throw new java.util.concurrent.CancellationException();
            database.finishWrite(stream);
        }
        catch (Exception e) { database.failWrite(stream); throw e; }
        samples = replacement;
    }
    public void add(Uri uri) {
        if (!begin("画像を読み込み、3方式で検出しています")) return;
        executeOwned(() -> {
            Bitmap input = null, decoded = null;
            AtomicFile pendingImage = null;
            boolean committed = false;
            String failure = null;
            try {
                if (samples.length() >= 64) { publish("上限64枚です"); return; }
                long start = SystemClock.elapsedRealtimeNanos();
                decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(getApplication().getContentResolver(), uri), (d, info, src) -> {
                    if (!List.of("image/jpeg", "image/png").contains(info.getMimeType())) throw new IllegalArgumentException();
                    int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                    if (w <= 0 || h <= 0) throw new IllegalArgumentException();
                    double scale = Math.min(Math.min(1, Math.sqrt(4_000_000.0 / ((long) w * h))), Math.min(8192.0/w, 8192.0/h));
                    d.setTargetSize(Math.max(1, (int)(w * scale)), Math.max(1, (int)(h * scale)));
                    d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    d.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
                });
                input = Bitmap.createBitmap(decoded.getWidth(), decoded.getHeight(), Bitmap.Config.ARGB_8888);
                new Canvas(input).drawColor(Color.BLACK);
                new Canvas(input).drawBitmap(decoded, 0, 0, null);
                decoded.recycle(); input.setHasAlpha(false);
                long decodeNs = SystemClock.elapsedRealtimeNanos() - start;
                String sha = hash(input);
                for (int i = 0; i < samples.length(); i++) {
                    if (sha.equals(samples.getJSONObject(i).getString("sha256"))) { load(i); publish("同じ画素の画像は追加せず、既存の判定を表示します"); return; }
                }
                String id = "evaluation-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                JSONArray results = new JSONArray();
                for (int i = 0; i < MODES.length; i++) {
                    if (cancel || cleared) { publish("取消しました。未完了の画像は記録しません"); return; }
                    if (detectors[i] == null) {
                        long init = SystemClock.elapsedRealtimeNanos();
                        detectors[i] = DetectorFactory.create(getApplication(), MODES[i]);
                        initialization[i] = SystemClock.elapsedRealtimeNanos() - init;
                    }
                    results.put(Measurement.run(detectors[i], input, id, calls[i]++ == 0 ? "first-call" : "warm", 0, initialization[i]));
                }
                if (cancel || cleared) { publish("取消しました。未完了の画像は記録しません"); return; }
                JSONObject sample = new JSONObject().put("id", id).put("sha256", sha).put("decode_ns", decodeNs)
                    .put("results", results).put("labels", new JSONObject());
                AtomicFile image = new AtomicFile(imageFile(sample)); pendingImage = image;
                var out = image.startWrite();
                try {
                    if (!input.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IllegalStateException();
                    image.finishWrite(out);
                } catch (Exception e) { image.failWrite(out); throw e; }
                beforeImageCommit.run();
                if (cancel || cleared || retired) { failure = "取消しました。未完了の画像は記録しません"; return; }
                JSONArray next = new JSONArray(samples.toString()); next.put(sample); save(next); committed = true;
                displayed = input; selected = samples.length() - 1;
                publish("検出完了。未評価のままでは集計されません");
            } catch (java.util.concurrent.CancellationException e) { failure = "取消しました。未完了の画像は記録しません"; }
            catch (OutOfMemoryError e) { failure = "メモリ不足のため中止しました。既存の判定は保持しています"; }
            catch (Exception e) { failure = "読込・検出・保存に失敗しました。既存の判定は保持しています"; }
            finally {
                if (decoded != null && !decoded.isRecycled()) decoded.recycle();
                if (input != null && input != displayed && !input.isRecycled()) input.recycle();
                boolean cleanupFailed = false;
                if (!committed && pendingImage != null) {
                    try {
                        pendingImage.delete();
                        File base = pendingImage.getBaseFile();
                        cleanupFailed = base.exists() || new File(base.getPath()+".new").exists() || new File(base.getPath()+".bak").exists();
                    } catch (RuntimeException e) { cleanupFailed = true; }
                }
                if (cleanupFailed) publish("未完了画像を消去できません。処理を停止しました。再起動時に後始末を再確認します", true);
                else if (failure != null) publish(failure);
            }
        });
    }
    public void label(int detector, int missed, List<Integer> falseBoxes) {
        if (detector < 0 || detector > 2 || missed < 0 || missed > 10000 || !begin("判定を保存中")) return;
        executeOwned(() -> {
            try {
                JSONArray next = new JSONArray(samples.toString());
                JSONObject sample = next.getJSONObject(selected);
                JSONObject result = sample.getJSONArray("results").getJSONObject(detector);
                if (!"SUCCESS".equals(result.getString("status"))) throw new IllegalArgumentException();
                var seen = new java.util.HashSet<Integer>();
                for (int i : falseBoxes) if (i < 0 || i >= result.getJSONArray("boxes").length() || !seen.add(i)) throw new IllegalArgumentException();
                sample.getJSONObject("labels").put(Integer.toString(detector), new JSONObject().put("missed", missed).put("false_indices", new JSONArray(falseBoxes)));
                save(next); publish("判定を端末内に保存しました");
            } catch (Exception e) { publish("判定を保存できません。保存済みの判定は変わっていません"); }
        });
    }
    @Override protected void onCleared() {
        cleared = true; cancel = true;
        // Never interrupt native work or recycle its bitmap until it has drained.
        STORE_WORKER.execute(() -> {
            for (Detector d : detectors) if (d != null) d.close();
            if (activeOwner == this) activeOwner = null;
        });
    }
}
