# 実表示相関の試作（未完成）

Issue #234。通常のCIでは実行しない。新しい依存は追加していない。
現段階で実機の50msを判定できる完成した試験ではない。

## 現在の部品

- `PreviewGestureDriver`：標準 `UiAutomation.injectInputEvent` を使用するinstrumentation用入力。
  MOVEごとにidle待ちを入れず予定間隔で動かす。実際の注入時刻と遅延を別記録する。
  注入のtrueは配信・表示完了ではない。例外時はCANCELを試す。
- `PreviewGestureDriverAndroidTest`：合成の目盛りへ入力が届くことだけを検査。
  `previewPresentationProbe=true` と `isolated=true` の両方が必要。
- `PreviewPresentationTrace`：`PreviewPresentation` DEBUGとTrace有効の両方が必要。
  pointerの入力→要求→採用bitmap→実画像のdrawへIDを渡す。
  現在のbitmapを古いまま保持する間、新しい要求IDをその画像へ付けない。
  有限な要求表と弱参照のbitmap対応を使用し、写真情報・値・URIをtraceへ出さない。
- `draw_frames.sql`：公式Perfetto v57.2 `android.frames.timeline` から外側doFrameと
  SurfaceFlinger display frameを結ぶ未実行テンプレート。対象packageと画像window layerを
  traceで確認して選ぶ。候補なしはUNRESOLVEDとして残す。結果はまだCANDIDATE_ONLY。

## 完成までに必須の検証

1. Macrobenchmark等のrelease相当/profileable対象で実画像の処理を走らせる合成fixtureを接続。
   現在の合成目盛りだけのテストを現像性能の測定と扱わない。
2. 入力markerのeventTime/アクションをinject markerへ一意対応させる。入力API開始から提示を
   主指標とし、入力受理後の時間とは分離する。双方の時刻は同じPerfetto clockで差し引く。
   MotionEvent uptimeをtrace timestampへそのまま引かない。
3. 同一doFrameに異なる世代が混ざる場合、drop、Unknown、対応不明、被覆/背景状態を拒否する。
   App Actual Timelineの終端はGPU/postであり提示ではない。SFでもinvalid fenceによる
   代替値があるためUnknownを合格にしない。再描画で同じ世代を重複計上しない。
4. ATDはソフトウェア描画の機能試験に限定。hardware renderingとSFの対応を別環境で検証する。
   それが完了するまで、通常の256px処理時間の補助集計を用い実表示50msは未判定とする。
5. 古い画像の再描画、取消、処理失敗、写真切替、exactがdraftより先に完了する場合の
   marker整合と、既定オフでの既存出力・操作仕様不変を検証する。

Trace内のIDや入力注入の名前は診断用契約であり、実機での時刻精度は未検証。
パネル発光までの光学的touch-to-photonではなくOSの提示イベントを終点とする。

公式資料:
- https://developer.android.com/reference/android/app/UiAutomation#injectInputEvent(android.view.InputEvent,boolean)
- https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview
- https://perfetto.dev/docs/data-sources/frametimeline
- https://github.com/google/perfetto/blob/v57.2/src/trace_processor/perfetto_sql/stdlib/android/frames/timeline.sql

測定runは画面の作成/写真切替/背景化/終了で診断対応表を破棄する。
さらに集計では同じtrace内にinject/input/request/adopt/drawが揃ったことを必須にする。
trace開始前の残留世代やtrace停止後の再描画から結果を補完しない。
注入が遅れた場合に同じミリ秒のMOVEが複数生じることがあるため、eventTimeとactionで
一意対応できない入力は未判定として残す。注入API呼出し区間の長さや予定時刻への遅れも記録する。

## オフライン相関器（合成試験のみ検証済み）

Python 3.11以降の標準ライブラリだけを使用する。端末操作・trace収集・自動downloadは行わない。
既に収集した同一traceファイルを、利用者が用意した公式Trace Processor **v57.2**で解析する。

- `all_markers.sql`は未完了sliceも含む全診断markerと、drawのancestor doFrameを出す。
  SF候補がない世代も混在検査から除かない。
- `correlate.py`が両SQLを同じtraceへ適用し、読取前後のSHA-256が変わった場合は停止する。
  CLIは `trace_processor query <trace> <SQL>` のCSV出力を読み、外部Python依存は不要。
  process名の一致ではなく、明示したapp/injectorのupidを使用する。
- eventTime/actionの双方で一意なinject/inputだけを結び、request/adoptは世代ごとに各１個必要。
  時刻の近さ・行順・予定時刻による補完はしない。入力API終了前にinputが届くことは許容する。
- 最初のdrawフレームだけを使う。同一doFrameの異なる世代、欠落、重複、時系列不整合、
  SQL側でdrop/Unknown等によりUNRESOLVEDになった候補は未判定にする。
  最初のフレームが不明な場合、後続の正常な再描画で救済しない。
- 同一世代の再描画は重複計上しない。DRAFT/EXACTは別の世代として保持し、速い方を選ばない。
- JSONには全注入、観測input、request/adopt/drawの世代の和集合を残す。入力未観測・要求なしも消さない。
  `LINKED`は入力間の対応候補で、世代の表示成立や注入APIの成功を表すものではない。
- 世代の出力は `CORRELATED_CANDIDATE` または理由付き `UNRESOLVED`。
  全体は常に `CANDIDATE_ONLY`。候補の `latency_ns` はSF提示時刻からinject marker開始を引いた整数ns。
  `input_latency_ns` はinput marker開始からの別指標。50ms合否、成功率、percentileは出さない。

### 実行例

まずtrace内のprocessのupidと画像windowのlayerを確認する。以下の20/10は説明用で実測値ではない。
同一process内instrumentationならapp/injectorのupidを同じ値にする。
layerにはtraceで確認した正確な名前を指定し、同時に複数のアプリ画面や注入元を測らない。

```text
python -B tools/preview-presentation/correlate.py --trace captured.perfetto-trace --trace-processor /path/to/trace_processor --app-upid 20 --injector-upid 10 --layer "exact image window layer"
python -B -m unittest discover -s tools/preview-presentation -p "test_*.py" -v
```

Windowsでも引数は同じ。Trace Processorの実行ファイルとtraceのパスをその環境に合わせる。
JSONが必要なら標準出力をローカルファイルへ保存する。trace・出力・端末情報はGitへ追加しない。
このdirectoryの `.private/` はGit対象外。traceのhashとlayerを含む出力も公開前に確認する。
形式不正、想定外のprocess、別のTP version、解析中のtrace変更はJSON候補を出さず終了code 2。
UNRESOLVEDがある通常の集計は終了code 0だが、計測の合格ではない。

### 検証境界

合成unitはSQL出力を模した行を使う。drop/UnknownのfixtureはSQLのUNRESOLVED出力を模しており、
実際のFrameTimeline parserやSQL述語を検証したものではない。CLI接続もsubprocess mockによる試験で、
**両SQLの実行、公式binaryとの実接続、実traceとの照合は未実施**。

今後、収集設定・trace欠落検出・公式SQLの列/NULL・token/layer対応を収集済み実traceで検証する必要がある。
画面の被覆、背景状態、複数window、実際の画像内容、run境界、注入APIの戻り値も、この相関器だけでは証明しない。
収集開始前の残留世代を補完せず、同一trace内でもrun境界を跨がないことをfixture側で確認する。
単独で完全な「実表示50ms試験」にはならず、従来の処理時間/採用時間の意味も変更しない。

CLI仕様の参照:
- https://github.com/google/perfetto/blob/v57.2/src/trace_processor/shell/query_subcommand.cc
- https://github.com/google/perfetto/blob/v57.2/src/trace_processor/shell/query.cc
