# 本来プレビュー FULL/half 統合・合成性能入口（未実行）

Issue #233。製品採用・実機操作・公開・mainマージは範囲外。

## 統合ベース

最新main `6d34d8c` に #455 `8f712d8a`、#456 `50a9e83`、#459 `7dff8100`
を独立ブランチ上で順に統合。Gitの競合マーカーは発生しなかった。
`InitialPreviewSurfaceTimingAndroidTest` は自動統合。元PRのブランチは変更なし。
GitHubのmergeable表示と実際のローカル統合結果は区別する。

新規差分は androidTest と本書のみ。製品既定・保存処理の変更は追加しない。
既存 #459 は品質比較用readback/Bitmap。今回の性能入口とは別である。

## 計測範囲

`PreviewHalfPerformanceAndroidTest` は FULL / HALF_CANDIDATE の本来入力を
ABBA順で実行する。half案内画像後にFULLを作る比較ではない。
#456と同じ `GpuPreviewTiming` を `LaunchedEffect(owner)` 開始時に作り、
`GpuPhotoRollSession` の通常画像 `onPresented`（swap成功）で終了する。
`firstFrameStarted → submit → openPhoto` も既存順序。
チェック・preflight・snapshot・native・BOX・verify・GPU初回を記録し、GUIDE未到達を検証。

ただしrepository/provider/受付/保存状態復元は使わず、私有の合成DNGで代替する。
したがって境界は対応しても製品全体の時間とは同一ではない。物理タップや表示光子、
Pixel速度・実写品質・採用の根拠にはしない。readback/Bitmap/画面撮影は実施しない。
各runで新規GL ownerを作るがOS/driver cacheは未制御。強制GC・warm/coldの断定はしない。
開始/終了thermal statusを併記。合成のみ、端末名・URI・入力画像は出力しない。

## 実行前ゲート

現時点はhost静的確認のみ。compile/lint/unit/AVD回帰は未実施。
Windowsまたは共有AVDの枠は親担当と調整し、実機へは実行しない。
既存 #459 の15合成テスト成功を、この統合head・新規testの合格と読み替えない。

別IDでビルドする `-PinitialPreviewCompare=true` を必須とし、
`initialHalfGuide` と `cinematicHalfCompare` は指定しない。
実行は対象classのみ、instrumentation引数は以下：

- `class=dev.cinematic.p0.photoroll.PreviewHalfPerformanceAndroidTest`
- `isolated=true`
- `previewHalfPerformance=true`
- `previewHalfMegapixels=12`（または固定24）

emulator hardware、DEBUG、別ID、guide無効をコードで検査する。
合成入力は12MP=4000×3000、24MP=6000×4000。ABBAの4runがすべて
OPEN=PRESENTEDになるまで報告はincomplete。失敗時は不明なownerのファイルを再帰削除しない。
出力は評価アプリのexternal files内 `half-performance/synthetic-12mp.json` 等。
これは合成専用であり実写に転用しない。
