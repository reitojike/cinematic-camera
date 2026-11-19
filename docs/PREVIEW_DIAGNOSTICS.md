# プレビューの診断と計測

製品の契約は [編集とプレビュー](../specs/develop/spec.md)、診断の公開境界は [設定・診断](../specs/diagnostics/spec.md) を参照する。実機の操作は毎回の承認範囲内に限る。写真・URI・素材名・編集値・端末固有識別子・未加工ログは公開しない。

## 通常のGPU経路

- [GpuSurfaceProbe](../app/src/main/java/dev/cinematic/p0/gpu/preview/GpuPreviewRenderer.kt) は明示した `log.tag.GpuSurfaceProbe=DEBUG` で、surfaceの接続・破棄、lifecycle、描画、EGLエラー等を調べる。既定では調査用DEBUGを有効にしない。実機で承認済みの調査を行うときは対象アプリのPIDと対象タグに絞り、終了時に変更したタグを元へ戻す。
- `frame.drawn` はレンダラーの描画イベントで、ディスプレイの提示や物理入力からの経過時間を証明しない。調査ログの取得を速度の比較走行に重ねない。
- 通常の Android 計測入口は [GpuFullPipelineTimingAndroidTest.measure1024FullRecompute](../app/src/androidTest/java/dev/cinematic/p0/gpu/GpuFullPipelineTimingAndroidTest.kt)。合成1024×768の再計算を、GPU完了待ち・readbackを含む wall 時間として記録する。写真を開く時間、EGL準備・解放や画面提示とは分ける。端末条件と引数は [GpuTimingOptions](../app/src/androidTest/java/dev/cinematic/p0/gpu/GpuTimingOptions.kt) に従う。
- [GpuPreviewObserverSafetyAndroidTest](../app/src/androidTest/java/dev/cinematic/p0/gpu/GpuPreviewObserverSafetyAndroidTest.kt) は `isolated=true` と `gpuPreviewObservers=true` の両方を指定したエミュレーター専用の合成試験。試験内で受付gateや時間観測を差し込んでも出力・原本が変わらず、owner資源が解放されることを確認する。製品GPUが同じ計測タグを出すという意味ではない。
- [PreviewPresentationTrace](../app/src/main/java/dev/cinematic/p0/diagnostics/PreviewPresentationTrace.kt) のイベントは `PreviewPresentation` のDEBUG指定かつ実際のTrace収集中だけ有効。入力・要求・Bitmap採用・対応するdrawを区別する。Bitmap観測のマーカーを通常のGPU surfaceの全フレームやOS提示とみなさない。[オフライン相関器](../tools/preview-presentation/README.md#オフライン相関器合成試験のみ検証済み) の結果は候補に留まり、50msや光学的な提示の合格を示さない。

## 計測データの境界

- [PreviewTiming](../app/src/main/java/dev/cinematic/p0/photoroll/PreviewTiming.kt) は対応経路の要求受付からUI状態採用までの観測であり、粗表示ではactor受信より前、正確表示ではeffect開始より前を含まない。通常のGPU表示の工程測定として `PreviewTiming` のタグを案内しない。
- [PreviewRequestGate](../app/src/main/java/dev/cinematic/p0/photoroll/PreviewRequestGate.kt) は標準coroutinesのMutexを使う。既存の `PreviewSerial` のDEBUG指定は、対応するMANUAL_TRIALの受付だけを直列化する。通常GPU経路を切り替える設定ではない。同じ画面内の排他とは別で、取消済みの待機を描画へ進めない。futureの取消だけをownerの解放証拠にしない。
- [RawPreviewTiming](../app/src/main/java/dev/cinematic/p0/raw/RawPreviewTiming.kt) と [R7StageTiming](../app/src/main/java/dev/cinematic/p0/raw/R7StageTiming.kt) の計測型およびテストは保持する。現行の通常GPUアプリからこれらの処理ログを採取できるという案内ではない。
- RawPreviewTiming の `totalNs` は対応するセッション内の受付・処理・後片付けを含む。同名区間への再訪は加算し、未採用・取消・失敗を成功に数えない。要求側の経過時間、比較画像の生成、OS提示とは別。[補助集計](../tools/preview-timing/README.md) はすでに得られた対応ログの解釈だけに使う。
