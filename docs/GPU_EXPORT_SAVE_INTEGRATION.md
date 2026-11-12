# GPU 原寸保存の接続確認（#286 Draft）

## 対象と依存

#338 の `9e36037` を基点とする stack。帯 renderer / shader は変更しない。
`Original24Attempt` の CPU/GPU 分岐、専用 GL owner、手動保存 owner の失敗と明示 retry、画面表示を対象とする。
JPEG codec、MediaStore publication、原本 fingerprint / snapshot 契約は置き換えない。

保存で保持する元の原寸 Bitmap は従来と同じ。192 MiB は engine の texture 台帳の予算であり、
Bitmap・native decode・JVM・driver を含めたプロセス全体のメモリ上限ではない。

## 再試行と停止

- GPU 画像処理中に失敗し、JPEG 未生成かつ各 owner の close を確認した時だけ `GPU_FAILED`。
- 保存ボタンの「GPU 保存失敗 · 再試行」から明示開始。通常 advance では同じ失敗を返す。
- CPU に切り替えず、開始時に固定したエンジン・同じ設定で原本の強い確認からやり直す。
- intent は維持する。process 再起動、close 不確実、intent 不一致、JPEG 後の失敗は再生成しない。
- 中断は GL 作業が source / Bitmap を使い終えるまで待ち、GL / EGL 終了後に原本と Bitmap を片付ける。
- GPU failure は固定 code と端末全体の累積 count を SharedPreferences に同期記録する。画面の count は当該 owner 内。
  aggregate の永続化失敗も再試行を許可しない。保存先・原本・exception message は記録しない。

## 試験

通常必須確認:

```sh
./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
git diff --check
```

`ManualOriginalTrialSaveOwnerTest` は従来の intent / publication 試験に加え、GPU failure code/count、
明示 retry、同一 private proof の再利用、再起動、取消、世代失効、解放不確実、intent 改変を検証する。
`ManualTrialJpegSaveOwnerTest` は失敗情報を保ち、保存済みと誤表示しないことを検証する。

`Original24GpuOutputAndroidTest` は合成入力だけを使う opt-in。独立アプリ
`original24probe` または `gpudiagnostic`、emulator (`ranchu` / `goldfish`)、
instrumentation argument `gpuExportSave=true` の全条件が必要。実機では実行しない。

- 257×513、strength 0 / .75、warmth 0 / 60、EV 0 / .7 の4条件で、adapter の最終行が直接 renderer と完全一致。
- consumer failure 時の全 close と、JPEG 前の再試行可能な失敗分類。
- caller interruption 時に作業を放置せず drain し、取消として返すこと。

この Android test は DNG decode / JPEG / MediaStore / FGS / preview と保存の同時利用を検証しない。
別のDNG/publicationとGPU editor/FGS fixtureで確認する。host Mesa結果をAndroid実行の代用にしない。

## この Draft の作業時確認

- pure Kotlin/JUnit で保存 owner 36件、固定 failure 表示3件、計39件が成功。
- UI snapshot 表示3件とGPU editor再開方針3件も、該当純粋部分を抽出して6/6成功。
- 実 renderer / GLSL を LWJGL Mesa GLES 3.2 に binding した host で adapter 3試験を実行し成功。
  thread の host binding は Java executor であり Android HandlerThread そのものではない。
- cloud の Android 一式は Gradle 配布取得不能、SDK 未配置のため未実行。公式 PR CI が必要。
- 実機操作、製品 merge は未実施。

## GPU 専用編集画面との接続

#338 に main の #339 / #340 が取り込まれた `5bc474d` に追従した。
#340 の session factory 注入点と、今回の exportPaused ガードを両方維持する。
GPU 専用 editor も同じ save owner の失敗理由・件数を表示し、同じ明示 retry を開始する。
保存開始前に preview session を close し、既存 retired-owner の終了確認を IO 上で待つ。
確認できなければ export を開始しない。プレビュー停止後も保存 handle を保持し、
GPU_FAILED の再試行・同じ publication の再照合に限って表示済みプレビューなしで続行できる。
保存中・HOLD・publication 不明・GPU_FAILEDではプレビューを自動再開せず、READY / PUBLISHED
でだけ明示「プレビューを再開」を許可する。GPU_FAILEDでは同じGPU保存の明示retryに限定する。設定・画像の自動再計算は追加しない。
この接続のCompose / foreground / GPU owner排他は、後述の合成AVD fixtureで限定確認する。

Home / trim 復帰でも停止状態と保存 handle を維持する。Activity 再作成後の入力読込でも、
取得した保存 owner が READY / PUBLISHED 以外なら GPU 写真を開く前に止める。

## 合成 DNG / 公開の追加受入

`Original24GpuSaveInstrumentationTest` は `gpudiagnostic` / `gpuExportSave=true` /
emulator 限定で、実 factory の検証済み合成DNG → GPU → JPEG → MediaStore 公開・再照合を通す。
保存 snapshot 開始後に設定をCPUへ変更しても、その保存がGL current contextで進むことを観測する。
寸法・orientation・sRGB・recipe・EV・原本fingerprint・同一公開URI・資源解放を確認し、
画質閾値は追加しない。成功時だけ確認済みのexact synthetic rowsを削除し、失敗時は保全する。
元の設定は終了時に復元する。`2058c1f` の合成AVDで1/1成功（1.481秒、skip0）。

## 実 GPU 編集画面 / FGS の追加受入

`GpuSaveLifecycleAndroidTest` は `gpudiagnostic` / `isolated=true` / `gpuExportSave=true` /
emulator 限定。実 preview と実保存 owner・FGSを使い、previewの終了確認がFGS開始より先であること、
Home / 復帰で同じ保存を維持すること、バックグラウンド公開後にpreviewが自動再開しないこと、
明示再開と同じ公開URIの再照合を検証する。queue barrierは時機の制御だけに使い、保存処理をfakeに置換しない。
`2058c1f` の合成AVDで1/1成功（53.076秒、skip0、再実行なし）。
この1caseは全失敗・取消・process再起動の実GPU結合を網羅しない。

## 大判比較の範囲

12MPの実CPU対64/256行GPUは最終8bit最大差1・p99.9=0で当時の条件では合格し、両帯幅はビット一致した。
24MPはnear-white countがCPUに対して2画素異なり、旧条件で停止した。後にAVD1600×1200の既存全画面GPUでも
count差1が出た。その後の最新ユーザー判断でCPU差（count/Mist/最終8bit/分位点）はすべて参考記録とし、
停止条件にしない。Gate2合否はユーザーの実写の見た目で決める。自動試験は帯対全画面GPU一致、shader compile、
原本保護・再試行・資源解放を維持する。
[Phase 2 検証計画](GPU_EXPORT_PHASE2.md)に理由・旧結果・最新の判断を残す。追加24MP試験は後回しとし、
未実施の24MP最終8bitや全体受入を完了扱いしない。1600×1200はCPU診断記録とGPU同士の自動判定を分けて再開する。

## AVD確認の時系列

`2058c1f` でGPU出力adapter3件、実DNG公開1件、実GPU editor/FGS1件、GPU解析4件、renderer4件の計13件が成功。
その後1600×1200の最初のCPU対全画面GPUのcount旧条件で停止した。旧失敗を消さず、
最新方針へ改訂したheadでCPU差を参考記録し、帯64/256と全画面GPUの一致・残るFGS/owner回帰を確認する。
小型64×48の実保存結合、1600×1200のengine受入、12MPのhost画質を区別し、実12MP DNG保存や実機合格に読み替えない。

## 今回の移行範囲と残る旧試験

#341での参考値化はexport対象の `GpuExportParityAndroidTest` / `GpuExportRendererAndroidTest` に限定した。
その時点で旧assertが残った `StageClearGpuPipelineAndroidTest` / `GlowMistParityAndroidTest` 等は、#290前段Aで広範なCPU差の参考記録化を扱う。CPU差をGate2条件へ戻す意味ではない。
GPU基準画像回帰は現行GPU出力を対象にAndroidで先行し、既定切替を待たず並行して進める。基準採用は本人目視確認後とし、GPU/ルック変更PRのマージ前とCPU削除・リファクタリングの各PR前に手動実行する。
v0.1.0前のD0でMesaの全工程呼び出しと基準画像比較のCI移行を扱う。環境版・差分・承認の詳細は [GPUエンジン契約](GPU_ENGINE.md#gpu基準画像回帰-android先行とd0のmesa移行) を参照する。
純粋なcount集計関数自体の正しさ試験は別物として保持する。

## #350: 保存中の別写真入力と中断の受入

GPU の別写真入力は保存の serial queue と別の単一 IO owner / admission 枠で準備する。
保存世代・intent・publication は従来の保存 owner に残し、入力表示の完了と保存可能状態を分ける。
A の保存中に B の入力・表示が成立しても、B の同一原本・確定 recipe に対応する保存 handle の
準備が終わるまで保存可能にしない。同じ A が保存用に閉じた preview の READY/PUBLISHED 時の
明示再開、未確定 owner の保持、CPU の入力・描画・保存、RAW 展開と画素式は変更しない。

`GpuExportActiveOwnershipAndroidTest` の追加受入は小型合成 DNG を使う。
- A を実 GPU B1 境界に保持したまま B の persisted recipe の提示を確認する。A 解放後だけの
  B 成功は同時動作の合格にしない。固定11秒条件でも B の10秒 admission期限は変更しない。
- A の実 JPEG / 原本指紋 / recipe / publication を検証し、B の READY・intent未生成を経て
  明示保存し、B の実 GPU / JPEG / 異なる原本・公開URIを検証する。
- B の実 preflight snapshot/FD を保持した準備境界で、Cへの切替とbackgroundを別々に試験する。
  取消要求と全資源・token解放を区別し、Bの遅延結果が表示されず、Aは独立して公開できることを確認する。
- B 表示中のC切替、background、engineから注入する初回描画/表示後context lossを独立試験とし、previewの
  texture/window解放とAのintent不変・GPU資源解放・公開をそれぞれ確認する。

- B preflight中はB自身のGL contextは未作成なので、その境界ではA export側context lossを注入する。
  AのGPU_FAILED・未公開・解放と、B snapshot/tokenの継続保持・解放・表示・保存可能を確認する。
  このケースだけ固定reason CONTEXT_LOSTの累積増分1件を要求し、失敗記録を消去しない。

試験結果は [PR #351](https://github.com/reitojike/cinematic-camera/pull/351)、初回失敗の追跡は [#352](https://github.com/reitojike/cinematic-camera/issues/352) に記録する。OSによる実context loss、
実12MP同時decode/保存の資源量はこの小型試験では未確認。既存context-loss明示retry試験と
CPUのA→B・取消/retry・原本保護回帰を維持する。実機操作の許可は別途必要。
