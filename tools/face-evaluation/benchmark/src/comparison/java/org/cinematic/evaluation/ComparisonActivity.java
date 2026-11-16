package org.cinematic.evaluation;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.lifecycle.ViewModelProvider;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

/** Evaluation-only native UI. This Activity has no image sharing or per-image export path. */
public final class ComparisonActivity extends ComponentActivity {
    private ComparisonModel model;
    private LinearLayout content;
    private int detector;
    private String pendingAggregate;
    private Bundle drafts = new Bundle();
    private String draftKey;
    private EditText draftMissed;
    private ArrayList<CheckBox> draftChoices;
    private void captureDraft() {
        if (draftKey == null || draftMissed == null) return;
        drafts.putString(draftKey, draftMissed.getText().toString());
        ArrayList<Integer> selected = new ArrayList<>();
        for (int i = 0; i < draftChoices.size(); i++) if (draftChoices.get(i).isChecked()) selected.add(i);
        drafts.putIntegerArrayList(draftKey + "-false", selected);
    }
    private final ActivityResultLauncher<Intent> choose = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() == RESULT_OK && result.getData() != null && result.getData().getData() != null) model.add(result.getData().getData());
    });
    private final ActivityResultLauncher<String> export = registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"), uri -> {
        String data = pendingAggregate; pendingAggregate = null;
        if (uri == null || data == null) return;
        // Only the immutable aggregate allowlist, never any selected photo or source JSON.
        new Thread(() -> {
            try (var out = getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new IllegalStateException();
                out.write(data.getBytes(StandardCharsets.UTF_8));
                runOnUiThread(() -> toast("集計のみを書き出しました"));
            } catch (Exception e) { runOnUiThread(() -> toast("集計を書き出せませんでした")); }
        }, "aggregate-export").start();
    });
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (saved != null) { detector = saved.getInt("detector", 0); pendingAggregate = saved.getString("aggregate"); Bundle restored = saved.getBundle("drafts"); if (restored != null) drafts = restored; }
        model = new ViewModelProvider(this).get(ComparisonModel.class);
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(16 * getResources().getDisplayMetrics().density);
        content.setPadding(pad, pad, pad, pad); scroll.addView(content); setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            var bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        model.state().observe(this, this::render);
    }
    @Override protected void onSaveInstanceState(Bundle saved) {
        captureDraft(); saved.putBundle("drafts", drafts); saved.putInt("detector", detector); saved.putString("aggregate", pendingAggregate); super.onSaveInstanceState(saved);
    }
    private void toast(String value) { android.widget.Toast.makeText(this, value, android.widget.Toast.LENGTH_LONG).show(); }
    private void text(String value) { TextView t = new TextView(this); t.setText(value); t.setTextSize(17); content.addView(t); }
    private void button(String value, boolean enabled, Runnable click) {
        Button b = new Button(this); b.setText(value); b.setEnabled(enabled); b.setOnClickListener(v -> click.run()); content.addView(b);
    }
    private void render(ComparisonModel.State state) {
        captureDraft(); draftKey = null; draftMissed = null; draftChoices = null;
        content.removeAllViews();
        text("顔検出の比較（評価専用・製品未接続）");
        text("写真・枠・判定は端末内のみ。ML Kit 同梱版は診断情報を通信する場合があります。Play 版はこの画面に含めません。静止画の検出比較です。");
        text(state.message());
        button("端末内の JPEG / PNG を1枚選ぶ", !state.busy(), () -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/jpeg", "image/png"}).putExtra(Intent.EXTRA_LOCAL_ONLY, true);
            choose.launch(intent);
        });
        if (state.busy()) button("処理を取り消す（SDK 完了後に停止）", true, model::cancel);
        button("集計だけを書き出す", !state.busy() && state.samples().length() > 0, () -> {
            try { pendingAggregate = ComparisonRecords.aggregate(state.samples()); export.launch("face-evaluation-aggregate.json"); }
            catch (Exception e) { toast("集計を作れません。書き出していません"); }
        });
        text("記録 " + state.samples().length() + " / 64 枚（同じ画素は重複しません）");
        if (state.selected() < 0) return;
        button("前の画像", !state.busy() && state.selected() > 0, () -> model.select(state.selected()-1));
        button("次の画像", !state.busy() && state.selected()+1 < state.samples().length(), () -> model.select(state.selected()+1));
        for (int i = 0; i < 3; i++) {
            final int index = i;
            button((detector == i ? "● " : "○ ") + ComparisonModel.MODES[i], !state.busy(), () -> { detector = index; render(state); });
        }
        try {
            JSONObject sample = state.samples().getJSONObject(state.selected());
            JSONObject result = sample.getJSONArray("results").getJSONObject(detector);
            JSONArray boxes = result.getJSONArray("boxes");
            text("画像 " + (state.selected()+1) + "・" + result.getString("status"));
            BoxView view = new BoxView(state.image(), boxes);
            content.addView(view, new LinearLayout.LayoutParams(-1, (int)(320 * getResources().getDisplayMetrics().density)));
            if (!"SUCCESS".equals(result.getString("status"))) { text("検出未成功のため判定できません。顔がないとは数えません。"); return; }
            text("検出 " + boxes.length() + " 個・SDK " + result.getLong("sdk_ns")/1_000_000.0 + " ms・adapter " + result.getLong("adapter_ns")/1_000_000.0 + " ms・" + result.getString("phase"));
            JSONObject old = sample.getJSONObject("labels").optJSONObject(Integer.toString(detector));
            text(old == null ? "未評価：ゼロ件の場合も明示して保存してください" : "判定保存済み（変更後は再度保存）");
            text("見逃した顔の数");
            EditText missed = new EditText(this); missed.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); missed.setHint("0 も明示入力");
            if (old != null) missed.setText(Integer.toString(old.getInt("missed")));
            missed.setEnabled(!state.busy()); content.addView(missed);
            ArrayList<CheckBox> choices = new ArrayList<>();
            JSONArray previous = old == null ? new JSONArray() : old.getJSONArray("false_indices");
            text("誤検出の枠にチェック（チェックなし = 合っている）");
            for (int i = 0; i < boxes.length(); i++) {
                CheckBox check = new CheckBox(this); check.setText("枠 " + (i+1) + " は誤検出"); check.setEnabled(!state.busy());
                for (int n = 0; n < previous.length(); n++) if (previous.getInt(n) == i) check.setChecked(true);
                choices.add(check); content.addView(check);
            }
            draftKey = sample.getString("id") + "-" + detector; draftMissed = missed; draftChoices = choices;
            if (drafts.containsKey(draftKey)) {
                missed.setText(drafts.getString(draftKey));
                ArrayList<Integer> checked = drafts.getIntegerArrayList(draftKey + "-false");
                for (int i = 0; i < choices.size(); i++) choices.get(i).setChecked(checked != null && checked.contains(i));
            }
            button("この検出器の判定を端末内に保存", !state.busy(), () -> {
                String value = missed.getText().toString().trim();
                if (!value.matches("[0-9]{1,5}")) { missed.setError("0以上の整数を入力してください"); return; }
                int count = Integer.parseInt(value);
                if (count > 10000) { missed.setError("10000 以下にしてください"); return; }
                ArrayList<Integer> falseBoxes = new ArrayList<>();
                for (int i = 0; i < choices.size(); i++) if (choices.get(i).isChecked()) falseBoxes.add(i);
                model.label(detector, count, falseBoxes);
            });
        } catch (Exception e) { text("記録の形式を確認できません。判定・書出しを中止してください。"); }
    }
    private final class BoxView extends View {
        private final Bitmap bitmap;
        private final JSONArray boxes;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        BoxView(Bitmap bitmap, JSONArray boxes) { super(ComparisonActivity.this); this.bitmap = bitmap; this.boxes = boxes; setContentDescription("選択した写真と番号付きの検出枠"); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); canvas.drawColor(Color.BLACK); if (bitmap == null) return;
            float scale = Math.min(getWidth()/(float)bitmap.getWidth(), getHeight()/(float)bitmap.getHeight());
            float left = (getWidth()-bitmap.getWidth()*scale)/2, top = (getHeight()-bitmap.getHeight()*scale)/2;
            paint.setStyle(Paint.Style.FILL); paint.setFilterBitmap(true);
            canvas.drawBitmap(bitmap, null, new RectF(left, top, left+bitmap.getWidth()*scale, top+bitmap.getHeight()*scale), paint);
            canvas.save(); canvas.clipRect(left, top, left+bitmap.getWidth()*scale, top+bitmap.getHeight()*scale);
            paint.setColor(Color.GREEN); paint.setStrokeWidth(3); paint.setTextSize(22);
            try {
                for (int i = 0; i < boxes.length(); i++) {
                    JSONArray b = boxes.getJSONArray(i);
                    float x = left+(float)b.getDouble(0)*scale, y = top+(float)b.getDouble(1)*scale;
                    paint.setStyle(Paint.Style.STROKE);
                    canvas.drawRect(x, y, x+(float)b.getDouble(2)*scale, y+(float)b.getDouble(3)*scale, paint);
                    paint.setStyle(Paint.Style.FILL); canvas.drawText(Integer.toString(i+1), Math.max(left,x), Math.max(top+22,y+22), paint);
                }
            } catch (Exception ignored) { /* Invalid records are blocked by the model before use. */ }
            canvas.restore();
        }
    }
}
