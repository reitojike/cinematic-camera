# プレビュー計測の補助集計

## 現在使える範囲

`summarize_processing.py` は既存 `RawPreviewTiming` の処理セッション時間を集計する。
入力のDRAFT/EXACTはログの処理区分であり、現在の製品GPUで同じ経路を選べることを意味しない。出力は処理のほか受付待ち・入力確認・Bitmap化・後片付けを含むログの `totalNs` である。
**操作から画面提示までの時間ではなく、50msの応答目標の合否には使わない。**
現行製品のGPU診断入口は [プレビューの診断と計測](../../docs/PREVIEW_DIAGNOSTICS.md) を参照する。

## 入力の準備

- このツールは既に採取された形式対応のログを読むだけで、端末操作や現在のGPUアプリへのログ取得設定を行わない。
- ビルド、入力、操作別（強さ・色温度・明るさ）、設定、セッションを分ける。観測途中の再起動・画面離脱・入力切替は別セッションとする。
- 空ログ・対象kindの正常記録なしを計測結果としない。捨て計測の回数は事前に決める。実写・生ログ・個人用パスを公開しない。

```text
python tools/preview-timing/summarize_processing.py capture.log --kind DRAFT --warmup 1
python -m unittest discover -s tools/preview-timing -v
```

`--warmup` は対象kindの正常処理から先頭N件を除く。失敗は別件数で、中央値に混ぜない。
p95はnearest-rank。工程合計とtotalNsの不一致、未知形式、重複キーは不正として数える。
対象サンプルなし、または不正行ありは終了コード2。計測ごとの標本数不足や欠落率は、
操作回数と併せて別に評価する。正常ログだけから未記録の要求数は復元できない。
同じログを重複連結しない。sequenceはセッションごとに再利用されるため、これだけで
重複排除や異なる写真/セッションの照合はしない。

## 実表示計測へ進む条件

自動操作には標準Macrobenchmark/UI Automatorを優先し、入力ID→要求世代→対応画像draw→
Perfettoのアプリフレーム→SurfaceFlingerのdisplay frameを関連付ける。
現在この補助スクリプトは自動操作もtrace相関も行わない。
FrameTimingMetric、UI状態採用、FrameCommitの時刻を提示時刻の代わりにしない。
欠測・ドロップ・対応不明は未判定とし、ATDのソフトウェア描画で提示精度を保証しない。

- https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics
- https://perfetto.dev/docs/data-sources/frametimeline
- https://developer.android.com/reference/android/view/ViewTreeObserver#registerFrameCommitCallback(java.lang.Runnable)

## 入力ログに含まれるworker数による分離

```text
python tools/preview-timing/summarize_processing.py capture.log --kind DRAFT --workers 1 --warmup 1
python tools/preview-timing/summarize_processing.py capture.log --kind DRAFT --workers 2 --warmup 1
python tools/preview-timing/summarize_processing.py capture.log --kind EXACT --workers 2 --warmup 1
```

`per_render_ms`は対象設定/kindの正常記録を記録順に並べ、先頭の慣らしを除いた各回の時間。`median_ms`/`p95_ms`は同じ集合の集計である。workers未指定で1/2や旧形式が混ざった場合は集計せず、`mixed_worker_configurations=true`と終了コード2を返す。kindとmaxEdgeの不整合も不正とする。切替のための同じ値の再計算や初回表示を、指を離した後の操作と混ぜない。
