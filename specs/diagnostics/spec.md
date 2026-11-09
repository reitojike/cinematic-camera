# 設定・診断・対応範囲の現行仕様

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

- [GPU合成診断](../../docs/GPU_DIAGNOSTIC.md) は製品未接続の独立 `gpuDiagnostic` アプリ。`cinematicGpuDiagnostic=true` の明示ビルドと画面からの明示起動だけで動く。GPU部分は合成専用。統合入口には既存RAW保存比較とCPUプレビューを含むが、GPUを製品処理へ接続しない。写真一覧権限は持たず、CPU側の入力はFilesで明示的に選ぶ。専用instrumentationも `gpuPixelProbe=true` と `isolated=true` がなければGPUを初期化しない。
- GPU合成診断の1024pxは全LookのCPU差、12MPはぼかし/Orton単体の負荷を測る。12MP単体や背景FGS試験の成功を、全保存出力・製品保存復旧の採用条件の達成に読み替えない。
- GPU合成診断は合成入力ごとに同一owner threadでEGL/contextと2 shaderを一度準備し、初回と再利用後を区分する。30msの目安は1024pxの `phase=reused` の対象2工程 `sumNs` だけに適用し、準備と初回処理を足した時間は別に記録する。upload/同期付きdraw/readbackのCPU wall内訳を分けるが、純粋GPU演算時間・非同期pipeline・GPU常駐入力の性能とは扱わない。通常製品へ接続せず、原本/goldenを変えない。
- 再利用はcontext/shaderに限り、転送・CPU変換・一時texture/FBO・readbackは処理時間に含む。初回/失敗/取消や別sessionをwarm反復へ混ぜず、文脈喪失時に自動再生成しない。合成試験で毎回作り直す版とのfloat完全一致とCPU許容差、owner/lifetime/回収を確認する。手順と内訳の境界は[GPU診断](../../docs/GPU_DIAGNOSTIC.md#初回と反復の計測境界257)を参照する。

- GPU の割当・転送分離は既定 OFF の詳細診断（`split-allocation-v1`）で行う。CPU 明示確保・変換・入力準備・検査、GL 資源確保・転送・draw・readback・解放と残差を非重複で記録し、実際の呼出し/pass数と論理bytesを併記する。追加の storage 要求・同期があるため、従来モード `baseline-v1` の速度・30ms判定へ混ぜない。CPU wall値を純粋なGPU演算/DMA/物理確保の時間とせず、2サイズの結果だけで固定費やGPU採否を確定しない。詳細と未分離部分は[GPU診断](../../docs/GPU_DIAGNOSTIC.md#割当転送を分ける詳細診断259)を正本とする。

## プレビュー診断の契約

- PreviewTimingは要求受付からUI状態採用、RawPreviewTimingはEXACT/DRAFT別のセッション計算を記録する。通常版の計測は明示DEBUG時だけで保存経路には接続しない。統合診断版の実編集では両ログをINFOで出し、並列設定とDRAFT/EXACTを識別する。前者の状態採用と後者の計算完了はいずれもOSの提示完了とは別。
- 原本・URI・素材名・編集値・個人識別子は含めず、非重複の区間合計・取消/失敗を記録する。RawPreviewTimingの任意出力の失敗は現像結果や後片付けを変えない。
- Draft提案（#267）: 統合診断のCPU編集だけで、R7StageTimingをFOUNDATION内の非重複区間として記録する。測光/ROI/色温度とR7本体を分け、localToneの画素計算・BOX/BICUBIC・分位統計を区別する。通常preview・保存は計測器を生成しない。保持済み/未訪問を0msの実測とせず、外側FOUNDATIONと二重集計しない。時計/ログ障害は出力や取消を変えず、実機未計測と合成検証を分離する。詳細は[プレビュー診断](../../docs/PREVIEW_DIAGNOSTICS.md#r7工程を分ける診断計測issue-267draft提案)を参照する。
- PreviewSerialはMANUAL_TRIALの診断専用で既定オフ。通常のdraft/exact排他と混同しない。詳細設計と明示有効化・停止手順は [プレビュー診断](../../docs/PREVIEW_DIAGNOSTICS.md) を参照する。

## 詳細契約と手順

- [設定の詳細](../039-settings-design/spec.md)、[診断の詳細](../040-diagnostics-design/spec.md)、[共通表示](../046-design-commonization/spec.md)。
- 統合診断の実機確認は [当日手順](../../docs/RAW4_DEVICE_CHECKLIST.md) を入口とし、実施版・計測結果・採否はIssueに記録する。端末の共有運用は [MANAGED_DEVICES](../../docs/MANAGED_DEVICES.md)、配布/PR運用は [OPERATIONS](../../docs/OPERATIONS.md)、必須検証は現行CIを参照する。
- 画面の現行色・書体・寸法の実装は CaptureDesign を参照する。PNGの被写体・数値・ログは実機の状態の根拠にしない。

- 統合診断には、合成1024pxのFoundation・Orton・Characterを独立に直列/2並列比較する明示入口を含む。raw-bit一致と時間を記録し、通常経路には接続しない。開くだけで実行せず、離脱時は取消し、worker終了までbusyを保持する。実写真の全プレビュー・操作遅延の検証とは区別する。専用テストも `previewParallelProbe=true` に限定する。

- プレビュー入力と提示相関の試作は独立した `previewDiagnostic` variantに限る。release継承・非debuggable・profileableの非公開hostを使い、製品・統合診断Hub・通常CIには接続しない。合成fixtureは実screen/rendererを使うが、リソース条件は固定する。処理完了・draw・OS提示候補を区別し、FrameTimeline相関が未検証の間は50msを合格扱いにしない。手順と制約は [提示相関の診断](../../tools/preview-presentation/README.md) を参照する。

- 統合診断版のCPU編集画面では、既定OFF（製品と同じ直列）とON（既存Foundation・Orton・Characterの画素処理を2並列）を明示切替できる。調整パネルを閉じ、確定表示と保存の停止を確認してから切り替え、同じ値で再計算する。設定は編集画面内だけで保持し、プロセス/画面再生成でOFFに戻る。通常版・原寸保存の既定を変えない。
- 診断設定は各要求の開始時に固定し、切替時に旧要求を失効させる。実行中workerは既存IO所有者が終了待ちしてから解放する。診断では表示Bitmap cacheの返却を避ける一方、縮小入力・Foundation・Glow・判定値の通常のセッション保持は維持する。計測の慣らし・操作種別・順序をそろえて比較する。
- RawPreviewTimingは各DRAFT/EXACT処理にworkersとmaxEdgeを付ける。最大256pxの各回のセッション時間を取り出せるが、要求前処理・Before生成・OS提示完了を含まない。計算時間から50ms提示合否や実機速度向上を断定しない。

## 保存安全確認の診断profile

- RAW診断の `SAVE_SAFETY` はGlow / Mist / R7 toneの1対2並列を、両armの色融合OFFとFoundation2 / Orton4 / Character2固定で確認する診断専用profile。専用入口でMemory A/B各1回と候補Bの取消2境界を分け、速度反復や製品既定へ接続しない。
- 旧 `RAW4` のBは色融合ONのまま維持し、既存reportと速度集計の意味を変えない。新profileはreportのcomparisonで区別する。取消はnative decode完了後 / Foundation完了後の境界確認であり、3工程のworker実行中の取消保証ではない。手順・未検証範囲は [RAW診断](../../docs/RAW_DIAGNOSTIC_BUILD.md#保存採用候補の安全確認profile261) を参照する。

- 保存安全profileの手動取消観測は候補B単発の別modeとし、UIのCancel受付→render退出・close試行退出・owner解放・harness静止を区別する。物理タッチ時刻や純粋なworker演算終了時刻を測ったとしない。受付時のGlow/Mist区間、成功/取消/失敗、離脱、未到達/未解放nullを残し、既存取消checkpointは増やさない。手順は [手動取消観測](../../docs/RAW_DIAGNOSTIC_BUILD.md#手動cancel受付から停止解放までの観測) を参照する。
