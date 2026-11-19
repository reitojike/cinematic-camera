# 設定・診断・対応範囲の現行仕様

メッセージの枠・優先順位・トーン・文言・アクセシビリティは [共通メッセージ仕様](../ui-messages/spec.md) を参照する。
共通部品の追加だけで既存画面を切り替えず、画面適用は別PRで行う。

## 対応範囲

- 最低対応は Android 13（API 33）。Android 12以前への新規インストールは対象外。SDK下限の宣言で既存インストールを削除・変更しない。SDK下限は GPU の製品採用を意味しない。対応宣言の根拠は [Android uses-sdk](https://developer.android.com/guide/topics/manifest/uses-sdk-element)、実値は app/build.gradle.kts を参照する。
- 標準Android API / Jetpack / CameraXの管理境界を守る。標準から外れる理由は対応する技術設計に記録する。
- 対応端末であってもRAW・手動制御・動画等の能力は実際の照会結果で判定し、モックの値を能力として表示しない。

## 設定

- 設定は固定プレビューの上のシートで開き、設定内のタップを背後に通さない。
- STILLの形式・解像度・カメラと、CINEの動画組合せ・音声などを区別する。再接続、即時反映、録画開始時などの効くタイミングを表示する。
- 録画・処理中や非対応の操作は既存のガードに従う。選択・送信中を成功や実測と混同しない。
- AE-Lは露出AUTO、AWB-LはWB AUTOの条件を守り、その接続に限る要求として扱う。
- 下端からリセット・診断に到達できる。全リセットと露出だけAUTOは別操作。狭い画面ではスクロールで必要な操作に到達できる。

## 診断と記録

- 制御・出力・能力・ログのタブで、要求・受付履歴・実測、接続世代や期限、出力と能力を区別する。タブ切替自体はカメラ制御を送らない。
- Preview開始/停止、レポート保存、設定へ戻る経路を維持する。ログを選択・コピーできる。
- 性能計測や候補切替は、明示的な診断設定・ビルドの境界を守る。製品の通常動作で自動計測・自動公開しない。
- 写真本体、撮影情報、個人用パス、端末固有識別子、秘密情報を公開のIssue/PRへ含めない。
- 合成試験、エミュレーター、実機GPU、個人写真の比較は異なる証拠であり、代替しない。エミュレーター時間は実機の速度保証ではない。
- [GPU 帯比較基盤](../051-gpu-band-probe/spec.md) は製品未採用。専用引数 gpuBandProbe=true の明示時だけ合成エミュレーター試験を実行し、通常 CI・合成 UI では起動しない。製品接続は #212 の実機 GPU と実写での再判定を条件とする。

## GPU・プレビューの計測と回帰試験

- 通常アプリは GPU 経路を使用し、計測や診断設定を理由に CPU へ自動回避しない。
- 通常の Android 計測は `GpuFullPipelineTimingAndroidTest.measure1024FullRecompute`（1024px）を使う。`GpuStageTap` は試験側で使い、引数 `allowDevice` / `measuredFrames`（既定3）の境界を守る。実機操作は毎回本人承認を要する。
- PreviewTiming は要求受付から UI 状態採用までを記録し、OS のフレーム提示とは区別する。計測は明示的な診断設定に限定する。
- 原本・URI・素材名・編集値・個人識別子を計測結果に含めない。任意ログ出力の失敗が現像結果や後片付けを変えないようにする。
- PreviewSerial は MANUAL_TRIAL の診断専用で既定オフ。通常の draft/exact 排他とは別。現行入口と境界は [プレビュー診断](../../docs/PREVIEW_DIAGNOSTICS.md) を参照する。

## 詳細契約と手順

- [設定の詳細](../039-settings-design/spec.md)、[診断の詳細](../040-diagnostics-design/spec.md)、[共通表示](../046-design-commonization/spec.md)。
- 実施版・計測結果・採否は Issue / PR に記録する。端末の共有運用は [MANAGED_DEVICES](../../docs/MANAGED_DEVICES.md)、配布/PR運用は [OPERATIONS](../../docs/OPERATIONS.md)、必須検証は現行CIを参照する。
- 画面の現行色・書体・寸法の実装は CaptureDesign を参照する。PNGの被写体・数値・ログは実機の状態の根拠にしない。

- 統合診断には、合成1024pxのFoundation・Orton・Characterを独立に直列/2並列比較する明示入口を含む。raw-bit一致と時間を記録し、通常経路には接続しない。開くだけで実行せず、離脱時は取消し、worker終了までbusyを保持する。実写真の全プレビュー・操作遅延の検証とは区別する。専用テストも `previewParallelProbe=true` に限定する。

- プレビュー入力と提示相関の試作は独立した `previewDiagnostic` variantに限る。release継承・非debuggable・profileableの非公開hostを使い、製品・統合診断Hub・通常CIには接続しない。合成fixtureは実screen/rendererを使うが、リソース条件は固定する。処理完了・draw・OS提示候補を区別し、FrameTimeline相関が未検証の間は50msを合格扱いにしない。手順と制約は [提示相関の診断](../../tools/preview-presentation/README.md) を参照する。

- 統合診断版のCPU編集画面では、既定OFF（製品と同じ直列）とON（既存Foundation・Orton・Characterの画素処理を2並列）を明示切替できる。調整パネルを閉じ、確定表示と保存の停止を確認してから切り替え、同じ値で再計算する。設定は編集画面内だけで保持し、プロセス/画面再生成でOFFに戻る。通常版・原寸保存の既定を変えない。
- 診断設定は各要求の開始時に固定し、切替時に旧要求を失効させる。実行中workerは既存IO所有者が終了待ちしてから解放する。診断では表示Bitmap cacheの返却を避ける一方、縮小入力・Foundation・Glow・判定値の通常のセッション保持は維持する。計測の慣らし・操作種別・順序をそろえて比較する。
- RawPreviewTimingは各DRAFT/EXACT処理にworkersとmaxEdgeを付ける。最大256pxの各回のセッション時間を取り出せるが、要求前処理・Before生成・OS提示完了を含まない。計算時間から50ms提示合否や実機速度向上を断定しない。

## 保存安全確認の診断profile

- RAW診断の `SAVE_SAFETY` は、A＝候補なし（Glow1 / Mist1 / tone1）、B＝候補あり（Glow2 / Mist2 / tone2）を比較する保存診断専用profile。両armの色融合OFFとFoundation2 / Orton4 / Character2を固定する。このA/Bは保存profileの構成を表し、記録にも候補なし/ありを併記する。連続Memory ABは同一sessionのJPEG一致と原本確認に残し、単発Memory A/Bは冷却・開始条件を照合する別入口とする。単発は構成間JPEG一致を判定せず `crossArmJpegComparison=NOT_AVAILABLE_SINGLE_ARM` を明示する。既存100ms managed/native観測最大と前後PSSは真peakではない。候補Bの取消2境界とは分け、速度反復や製品既定へ接続しない。
- 旧 `RAW4` のBは色融合ONのまま維持し、既存reportと速度集計の意味を変えない。新profileはreportのcomparisonで区別する。既存の自動取消2境界はnative decode完了後 / Foundation完了後の確認であり、3工程のworker実行中の取消保証ではない。手順・未検証範囲は [RAW診断](../../docs/RAW_DIAGNOSTIC_BUILD.md#保存採用候補の安全確認profile261) を参照する。

- 保存安全profileの手動取消観測（#273）はA/Bと狙うGLOW/MISTを開始前に固定する4つの単発入口とし、UIのCancel受付→render退出・close試行退出・owner解放・harness静止を区別する。狙いと実受付stage、stage開始から受付までの経過を残すが、物理タッチ時刻・同じworker進行位置・純粋なworker演算終了時刻を測ったとしない。狙い違い、成功/取消/失敗、離脱、未到達/未解放nullを区別し、既存取消checkpointは増やさない。手順は [手動取消観測](../../docs/RAW_DIAGNOSTIC_BUILD.md#手動cancel受付から停止解放までの観測) を参照する。
- 保存候補の取消は条件をそろえたAに対するBの停止時間・資源解放の非悪化で判断し、2秒/5秒は参考記録とする。メモリも同じ入力/APK/構成差・サンプル方法・開始条件で比較する。条件差・欠測・回収不明を合格へ補完せず、単発完了や固定のcomparisonDecisionを自動採用判定にしない。比べた差が微妙、または条件が不揃いなら未判定とし、その項目だけ追加測定するかを本人へ相談してから決める。後付けの許容値や無断の再測定は追加しない。反復数・順序・事前budgetと限界は [統合当日手順](../../docs/RAW4_DEVICE_CHECKLIST.md#今回の限定メニュー270) に定める。
