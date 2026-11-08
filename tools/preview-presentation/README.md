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
