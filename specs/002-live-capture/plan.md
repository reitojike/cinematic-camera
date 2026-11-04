# Implementation Plan: ライブ撮影方針とP0.5検証設計

**Date**: 2026-11-01 (UTC) | **Spec**: [spec.md](spec.md)

**Scope**: 仕様差分・検証計画・次の限定実装案まで。新機能・素材取得・実機撮影・配布は実行しない。

## Summary

既存P0を維持し、ライブ品質を最優先にする。P0証拠整備を継続し、開発の優先順はP0.5撮影/動画/音声の成立 →
P1 Stage Clear → P2共通ルック/previewと残る2ルック → P3非破壊編集/書き出し/製品UI。
依存による順序調整は可能だが、確認済み成果の重複実装や全機能一括導入はしない。

## Technical Context

| 領域 | 維持する方針・導入条件 |
|---|---|
| app/UI | 現行Kotlin / Compose、単一app。作り直し・製品UI化は今回なし |
| camera | CameraX 1.6.2 / 必要部分のCamera2Interop。直接Camera2は再現した問題を根拠に最小比較して別途判断 |
| 動画probe | CameraX VideoCapture / Recorderを候補に、対応するSDRプロファイル1つから検証。現行は未実装 |
| RAW現像 | LibRaw / NDK / C++はP1に必要な範囲のみ。導入前にライセンス、ビルド、画質/メタデータの扱いを検証 |
| 色/動画変換 | OpenGL ES / GLSL / 3D LUT、Media3はP2以降の候補。現在の依存へ先行追加しない |
| 保存/非同期 | MediaStoreを維持。Room / DataStore / Coroutines / Flowは必要な時点で導入し、保存済み原本を移行/削除しない |
| 共有 | Android SharesheetはP3。独自SNS連携・バックエンドは導入しない |
| 対象 | Pixel 9aのみを先行。固定絞りは表示、WB/AF-MF/AE時EV/fpsは能力と実測を前提にする |

**後続RAW現像UX（採用方針・2026-11-04更新案）**: 撮影時は軽量な近似look previewを使い、DNG原本と固定したlook情報を保持する。撮影時の仮JPEGはアプリ内部だけに置き、現像成功後のJPEGを共有カメラロールへ公開する。撮影を最優先とし、処理が邪魔しない条件で自動現像を進める。失敗・中断時もDNGと仮JPEGを保全し、既存JPEG/DNG/MP4を移動・削除しない。詳細な保全・再開・開始条件・公開契約と小さいPRの順序は[spec016](../016-raw-capture-lifecycle/plan.md)で扱う。旧方針の「明示書き出しのみ」「撮影直後のフル現像queueは採用しない」は今回の合意で置き換え、撮影直後に重処理を必ず開始する保証にはしない。縮小表示でもRAW decode負荷は残り、即時停止可能性や実コストは実測前に保証しない。previewは明るさ・色傾向の近似で、NR・細部・飽和復元の厳密一致は保証しない。ギャラリー/編集UIは別Issueであり、今回の設計を既存アプリへの実装済み宣言としない。merge前はDraftの更新案である。

**共有原本の保存方針**: DNGは共有領域の1つを編集・現像から読み取り参照し、恒久的な二重保存を避ける。
撮影/共有失敗時の一時DNGは保全し、共有先の全バイト一致・読取り・公開・台帳記録を確認した後に解放する。
再インストール後は本人の写真/ファイル選択で参照を取得し直す。原本が残ることと、内部の編集情報やアクセス権が戻ることは別である。
外部ギャラリーのDNG/JPEG二重表示を保証・制御しない。詳細と検証範囲は[spec016](../016-raw-capture-lifecycle/spec.md)に従う。

## Constitution Check

- 既存P0の範囲・ACは[001 spec](../001-p0-camera-probe/spec.md)のまま。新方針による遡及変更なし。
- 原本、編集情報、出力、報告と直接計測を区別。画像/音声/未編集ログをGitへ公開しない。
- 標準API優先、1.6.2の実APIで確認し、最新referenceの1.7等の新APIを混ぜない。
- 今回は計画だけ。P0.5/P1の実装開始、署名鍵/Secrets、素材取得、実機操作は別の作業範囲。
- specは決定事項、進捗はIssue/PRコメント、最終検証結果は成果物のレポートに限定する。

## 変更影響と再利用

| 領域 | 現行 | 今回の方向・影響 | 今回の変更範囲 |
|---|---|---|---|
| 最優先用途 | RAW/manual成立性、映画色/21:9が背景 | ライブ静止画/動画、暗所/動体/照明/音声/応答を先行 | 002 spec/本plan |
| P0完了 | 中核確認はユーザー成功報告、証拠補完あり | ACを維持し、資料/失敗処理を完了または根拠付きBLOCKEDにする | [Issue #4](https://github.com/reitojike/cinematic-camera/issues/4) |
| P0.5 | 未定義・動画未実装 | STILL/CINE・SDR/音声・ライブ条件を段階検証 | 計画のみ |
| ルック | T&O・customが将来背景 | Stage Clearが最優先、Stage Dramatic / Cinema T&OをP2で補う | 画質評価条件 |
| crop | 21:9が背景、保存は未実装 | ライブは元画角/解像度/RAW保持。横長はguide/後crop | P3の受入軸 |
| 共通ルック | 未実装 | 写真/動画は共通意図＋入力/色空間別変換 | P2設計制約 |
| UI/icon | P0の検証UI | 無機質・メカ感、複数案比較。LIVE/SCENEは未決 | 比較原則のみ |
| 保存 | JPEG/DNGをPictures/Cinematic Camera、ログprivate | 既存経路を維持し原本/編集情報分離へ拡張 | 保存コード変更なし |
| 既存ソース | Preview/RAW/JPEG/manual、token/epoch、保存cleanup、11 tests | 回帰基準として再利用 | 変更なし |

prototype・spec・全状態のIssue/PRを照合した。P0記録は[PR #3](https://github.com/reitojike/cinematic-camera/pull/3)でmainへ反映済み。
P0未完了はIssue #4へ集約。Renovate PR #1は本件と無関係で触らない。概念取り込みのための重複Issueは作らない。

## P0の完了境界

P0の機能成功報告を取り消したり、全項目を再撮影したりしない。Issue #4の証拠補完と未確認条件を扱う。

1. 端末/Android build/camera ID/使用APKを同定し、Camera2能力とCameraX形式を別々に要約する。
2. 既に確認したDNG 5件以上の名前/サイズ・viewer、ISO/時間各2値の指定/実測・誤差/制約を補完する。
3. 権限/明示Stop/Start、非対応/APIエラー、対応時RAW+JPEG等の未確認条件を確認する。
4. 各ACを証拠付きで完了、または実機非対応の根拠付きBLOCKEDへ分類し、P0_REPORTと採否を確定する。
   資料がないだけの場合は未確認であり、BLOCKED/非対応やPASSへ置き換えない。

現在のP1判断案GO_WITH_LIMITATIONSは技術成立性の案であり、P0全AC合格や新フェーズ着手許可ではない。
新順序では画作りの前にP0.5の撮影品質リスクを評価する。P0の未確認が保存/制御の安全性を損なう場合は
関連するP0.5実装を保留する。単なる資料補完との切り分けはIssueで明示する。

## P0.5の検証計画

次は計画上の初回試行案。まだ測定していない。まず利用可能な背面カメラ1つ、対応プロファイル1つから始める。
初期値は端末の実対応能力を優先し、1080p30等の低値に固定しない。SDRのUHD/60fpsを初期候補とし、
カメラ・dynamic range・fps・use-caseの組合せで利用可能か照会/確認する。UHDの対応だけで60fps/HDR対応まで推定しない。
非対応ならUHD30等の成立する代案へ移り、要求・適用・実出力と理由を表示する。ユーザーの変更はmode別に保持する。

| ID | 条件・初回試行案 | 比較・記録 | 完了/停止の判断 | spec対応 |
|---|---|---|---|---|
| V01 | STILL→CINE→STILLを冷間/温間それぞれ5往復 | mode別ISO/時間/fps、AE解除、切替待機/blackout、画角/焦点変化 | 無条件引継ぎなし。維持不能なら再bind等の制約を記録 | FR-002, FR-003 / SC-002 |
| V02 | 中立照明から暗所へ、範囲内ISO低/高の2条件 | 同じ露光/画角、DNG/JPEGのノイズ/blur/階調、実測ISO/時間 | ノイズと露光不足/被写体ブレを混同しない | FR-004 / SC-001 |
| V03 | 同じ距離移動/横移動を各3回、明/暗条件 | AF追従/迷い、背景抜け、合焦復帰、撮影時の結果 | 顔検出対応と追従品質を分ける。失敗を隠す固定MF化はしない | FR-003, FR-004 / SC-001 |
| V04 | 赤/青/紫/マゼンタ＋中立、スポット/逆光 | 肌、channel飽和、明暗差、WB/AEの変動、原本 | 色照明を消さず、飽和復元不能を記録 | FR-004, FR-005 / SC-003 |
| V05 | 同じLED条件で対応範囲内の露光時間3値、AE/手動を区別 | バンド幅/変動、fps/frame duration、利用可能なanti-banding設定 | 商用電源周波数だけでPWM照明を説明しない。全条件解消を保証しない | FR-004 / SC-001 |
| V06 | SDR動画を音声on/offで各3本（初回30秒） | Finalize結果、URI/サイズ、再生、fps/time、音声有無、無音/歪み/clip、同期 | 失敗出力を成功扱いにしない。音声拒否/エラーを明示 | FR-003, FR-004 / SC-001 |
| V07 | 同一構図で静止画10回、連続動画を1→3→5分へ段階化 | capture入力→完了、保存時間、fps/drop、thermal status、状態回復 | 保存/録画エラー、OSの熱警告/停止で中断。冷却後の復帰を記録 | FR-004 / SC-001 |
| V08 | 録画停止後と録画中それぞれに背景→復帰、権限拒否、保存/Finalize失敗 | state/token、録画の停止/Finalize、音声解放、保存結果、残存row/stream、復帰後の撮影 | 背景録画は行わず、録画中の背景化では停止/Finalizeと結果表示を確認。P0回帰を確認 | FR-010, FR-013 / SC-001 |

初回baselineは秒/ms、fps、成功回数、条件ごとに残す。構図維持・応答/画質の最終閾値は測定後かつ
改善実装前に合意して固定する。上表の回数・尺を満たしただけで「ライブ品質合格」にしない。
熱状態はOSのstatusとして扱い、未取得の端末温度を℃で捏造しない。

## P0.5の最小実装案と停止条件

次に勧める限定作業は **P0.5-A: 既存P0を維持するSDR動画/音声とSTILL/CINEの最小probe**。
ライブ適性の前提である録画・音声・切替を先に測れ、RAW現像/GPUを混ぜずに保存/lifecycleの制約を切り分けられる。

- カメラ1つ・対応SDRプロファイル1つ、Preview + VideoCapture/Recorderを候補にする。
  静止画/RAWとの同時bindを当然とせず、必要ならmode切替時の再bindを測って比較する。
- 静止画も元画角を保ち、APIで利用可能な高解像度を優先する。公称48MPを強制したり、画素数だけで画質を保証したりしない。
  動画/静止画の要求解像度と実出力metadataを記録し、未対応なら理由と代案を示す。
- dynamic rangeは実カメラから照会する。安定SDRが先行だが、標準APIで限定的に成立する対応HDR modeのみ
  選択可能とする案を含める。HDRの実機未検証を明示し、無理な保証や全面Camera2移行はしない。
- 1操作のmode切替、mode別ISO/シャッター保持、動画fps/シャッター/音声on-off/エラーの最小表示。
  時間と角度は `角度 = 360 × fps × 露光秒` で対応させ、requestedと実fpsを混同しない。
- 動画のmanual設定は1.6.2実API・有効範囲・CaptureResultで成立を確認する。
  AE時EV、WB、AF/MFは対応範囲を先に記録し、一度に全マニュアルUIを作らない。
- 音声が必要な時だけRECORD_AUDIOを要求する。拒否時は無音状態を明示し、許可されたと仮定しない。
  音量自動補正やマイクgain操作、音割れ復元の保証は追加しない。入力段の飽和限界も評価する。
- MP4は標準MediaStore Videoの共有保存を候補とする。`Movies/Cinematic Camera/`を初回提案とし、
  JPEG/DNGの現行Pictures経路は維持する。正確な保存仕様は実装taskで確定する。
- Record/Finalize、保存失敗、背景復帰を測り、既存RAW/manual/共有保存の回帰を確認する。
  GPU、ルック、高度なHDR処理、編集/ギャラリー、バックグラウンド録画service、固定署名導入は含めない。
- CameraX/Interop競合、非対応出力、音声/保存/熱の失敗は再現条件を記録して止める。
  自動でCamera2全面移行や無関係な独自回避へ広げず、代案/品質/応答のtradeoffを提示する。

この文書PRではコード・依存を変更しない。後続実装は限定taskごとに行い、ビルド・自動テスト・独立レビューで
確認可能な範囲を進める。実機確認待ちだけを停止理由にせず、未確認をPASSにも置き換えない。
重大な欠陥/テスト失敗、権限判断、大幅な範囲拡大が必要なら停止して報告する。

### 限定実装の分割案

| 順 | 作業単位 | 確認範囲・境界 |
|---|---|---|
| A1 | 最小SDR動画/音声・共有MP4保存、能力に基づく初期値 | 許可、Record/Finalize、保存cleanup、対応quality/fps/rangeと実出力。P0回帰を維持 |
| A2 | STILL/CINEとmode別設定 | ワンタップ切替、露出受付/解除、ユーザー設定保持、静止画高解像度、切替/背景復帰race |
| B | 露出・AF・フリッカーの検証導線 | 能力/要求/実測を表示して比較。AF追従やフリッカー解消の実機合格は自動で宣言しない |

各単位を小さなPRで独立レビュー・CI・回帰確認する。具体的な実装taskの範囲を明示し、
合意した作業範囲内ではレビュー/CI成功を条件に反映する。製品全体の一括実装へ範囲を広げない。

### B1: AF/AE・照明と熱状態の観測

[Issue #13](https://github.com/reitojike/cinematic-camera/issues/13)では、AF/AWB/anti-bandingとAE補正の能力・
CaptureResultを追加する。center AF/AEはCameraX標準FocusMeteringActionを対応照会後に使い、APIの合焦結果を
動体追従/画質の合格にしない。EVはAUTO受付済みの範囲内に限定し、mode別の希望を復元する。
focus/EVのFutureはsession世代で隔離し、bind/stop時は適用済み表示を初期化する。

PowerManagerのthermal statusを観測し、SEVERE以上では録画停止/Finalizeへ進める。
温度℃・安全温度・実機性能の証拠には読み替えず、closeでlistenerを解除する。
WB/MF/anti-bandingの手動上書きUI、フリッカー解消アルゴリズム、実機のAF/熱/画質評価はこの単位へ抱き込まない。

## Stage Clear評価設計（P1）

この評価設計に沿ったPC基準の比較と、ユーザーが選んだv1の定義（既定の強度0.5）は[014 Stage Clear v1](../014-stage-clear-v1/plan.md)に置く。

### サンプル提案

最初は以下6条件を、原本＋固定した基準処理で揃える。現在は素材未取得であり、撮影/ダウンロードしない。

| 条件 | 提案する素材 | 見る点 |
|---|---|---|
| S01 中立光 | 肌・髪・衣服の細部、gray/白の参照を含む静止人物 | WB、赤み/黄ばみ、立体感、基準色 |
| S02 赤い光 | 赤いステージ照明相当、肌と衣服 | 赤channel飽和、濁り、照明と肌の関係 |
| S03 青い光 | 同じ構図の青照明、明部/暗部 | 青channel、暗部ノイズ、肌の不自然な暖色化 |
| S04 紫/マゼンタ | 同じ構図で紫とマゼンタを各比較 | 色偏り、肌分離、照明雰囲気の消失 |
| S05 スポット/逆光 | 顔の明部＋暗い背景、逆光縁/髪 | highlight roll-off、階調、halo、silhouette |
| S06 暗所高ISO＋動体 | 範囲内ISO2条件、同じ動き/距離 | ノイズ/平滑化/ディテール、AF/blurとの切り分け |

本人が利用を許可した非公開素材、または撮影・処理・比較に使える権利を確認した検証素材を提案する。
肌の色や衣装の異なる少数素材を用い、一律の白さへ正規化しない。初回は手元の既存素材で足りるか確認し、
ライブ参加/実写撮影/素材購入/写真公開を自動で行わない。
合成色パッチは飽和診断の補助に使えるが、人物の肌・高ISO・動体品質の代用にしない。

### 比較方法と受入軸

同じDNG原本を基準処理とStage Clearの強度0/中/最大で比較する。初期比較値として0/0.5/1を提案するが、
正式なUIや最終パラメータは未決。基準現像の版、WB/露光、トーン、解像度、出力色空間を固定し、
ルックと一緒に露光を変えて良く見せない。全体表示と肌/髪/衣服/暗部の同じcropを用いる。

| 評価項目 | 比較・記録 | 不合格/制約として扱う例 |
|---|---|---|
| 肌の明るさ・透明感 | 同じ領域の中間調、赤み/黄ばみ/濁り、ユーザーの1〜5評価 | 一律白塗り、顔の立体感消失、照明を無視した肌色 |
| 肌ディテール・ノイズ | 同倍率で肌/髪/衣服、粒状感、色ノイズ、平滑化 | wax状の肌、細部の消失、強い色斑や人工的な輪郭 |
| highlight/階調 | 元の飽和と処理後のclipを分離し、soft roll-offを比較 | 復元可能域を処理で白飛びさせる、色転び、halo |
| 照明色・暗部 | 赤青紫マゼンタを条件別評価、影が重すぎないか | 色光を中立化して雰囲気を消す、黒つぶれで情報を失う |
| 安定性・再現性 | 同じ原本/定義/強度の再export比較、処理時間/メモリ | 同設定で意図しない差、失敗/長時間処理を隠す |

各条件の評価と許容できない欠点を記録し、総合平均だけで赤/青/マゼンタの失敗を隠さない。
Stage Clearで肌・質感・照明を両立できる処理をユーザーが比較選択してから、Stage Dramatic/Cinema T&Oへ広げる。
すでに飽和したchannelは元情報が欠落している可能性があり、他channel推定やtone処理で完全復元できるとは約束しない。

## P2/P3とデータ設計の境界

- ルック定義の版/強度/色意図は共通にするが、RAW線形データと動画のYUV/RGB、
  primaries/transfer/range、WB/露光/トーン処理順を入力別に明記する。
  同じ3D LUTを未変換の異なる色空間へそのまま適用しない。
- P2はSDRで写真/動画/preview/exportを比較してからStage Dramatic/Cinema T&Oを追加。
  P0.5で試した動画を作り直さず再利用し、独自のHDR加工やHDR/SDR変換は後続にする。
- 原本参照と編集情報（ルック版/強度、crop、出力設定）を分離し、原本から再現像/再exportする。
  P1の比較用JPEGと、P3の製品としてのJPEG/MP4 export UIは別の範囲。
- P3は非破壊crop、SNS比率、標準Sharesheet、製品UI/ギャラリー。
  原本の画角/解像度を保存前cropで失わない。preview guideと保存cropを明確に区別する。
- LIVE/SCENEは切替/overlay/既定ルック案など少なくとも2案を比較する。STILL/CINEとの階層を先に増やさない。
  UI・アイコンは無機質/メカ感を軸に、ユーザーの最終選択前に1案へ固定しない。
- custom editor、ユーザーpreset、LUT入出力/共有は後続。バックエンドや全モジュールを先に導入しない。

## 文書・Issue・次の判断

spec/planと要件品質checklistだけを追加する。研究判断・評価protocol・データ境界を本planへ統合し、
同じ内容のresearch/data-model/quickstartや実装tasksを先に増やさない。公式skills/テンプレートは改変しない。

進捗と証拠補完はIssue #4、今回の方針レビューは本Draft PRで管理する。新しい実装Issueは、
P0残件との重複を確認し、P0.5-Aの範囲を承認した時点で必要最小限に切り出す。
この計画を理由に新しいIssue群・実装・素材公開・Releaseを作らない。

## P0.5-B2: 対応WB preset・MF・AE anti-banding（Issue #15）

FR-003/004の限定実装として、Camera2 available modesとrequest keysの両方でWB/anti-bandingを判定する。
WB OFF/gains/transform/Kelvinは含めず、既知の標準presetだけを提示する。
MFはMANUAL_SENSOR、AF OFF、両request key、finiteなminimumFocusDistance > 0を条件とし、
0..maximumのlens unitsを指定する。校正がUNCALIBRATED/UNKNOWNならdioptersやメートルを保証しない。

露出と追加controlを一つのsnapshotで置換し、個別操作で他の制御キーを消さない。
MF変更前はCameraX cancelFocusAndMetering完了を待ち、AF復帰はAF_MODE/LENS_FOCUS_DISTANCEを除外する。
MF中のCenter操作はAUTO AEがあればAEだけとし、AF要求を重ねない。
`AUTO reset all`は全Interopとfocus要求を解除し、EVをneutralへ戻す明示操作とする。
mode再bindでは一度clearし、対応する保存control、EV、必要なら保存済みMANUAL露出を直列復元する。
非対応の保存値は項目別にCameraX defaultへfallbackし、理由をログ/UIへ示して希望値を保持する。

AE anti-bandingはMANUAL AEで効果を持たない。指定値を保存・表示できてもLED/PWM除去を保証しない。
request/accepted/CaptureResult（WB、focus lens units、anti-banding、scene flicker）を別に扱う。
録画中は変更せず、Futureはbusy/epoch/closedで隔離する。新権限・新依存・独自Camera2 sessionは追加しない。
検証は入力能力/範囲/fallback unit、build/lintと独立レビュー。実機ではmode復元、AUTO全解除、
WBの色光比較、MF/AF往復、50/60Hzと手動shutter比較を別途行い、未実施を合格にしない。

## P0.5-B3: 検証レポートの取得（Issue #17）

実機証拠を端末から取り出すため、標準SAF CreateDocument(text/plain)を明示ボタンで起動する。
ユーザーが選んだ文書だけへUTF-8で書き、追加のstorage permission/persistable grantは要求しない。
mainで状態/要求/実CaptureResult/能力をfreezeし、process IO executor上で現在ログの末尾最大128KiBを読む。
tailは最初の部分行/UTF-8断片を捨てて切断を示し、完全なsession logと誤認させない。
写真/動画/音声本体・GPS・serialは追加取得せず、現像/共有製品機能へ広げない。

書込/flush/close完了後だけ成功表示。provider/cancel/Activity終了を扱い、失敗文書が部分的な可能性を示す。
外部文書や原本を自動削除しない。録画/Finalize中と重複保存は抑止し、完成後も本人が内容確認してから共有する。
自動送信や公開Issueコメントは実装しない。UTF-8上限・書込/close失敗のunit、build/lintと独立レビューを行い、
端末のSAF許可/拒否/取消/読み戻しは実機未確認として残す。

## UI比較候補（Issue #19）

FR-012/SC-006へ、無機質メカ系の計器型・ファインダー型・下段操作型の3案を自己完結HTML/SVGで用意する。
原本画角/解像度を保持する方針と21:9のguideのみを共通にし、同じ合成stage/例値で配置を比較する。
STILL/CINE・mode別ISO/時間・focus・lookラベル・仮撮影を操作できるが、camera/mic/色処理は行わない。
数値やUHD60表示は端末能力の証拠ではない。アイコン3方向も仮案で、ユーザー写真/外部asset/通信/保存を使わない。
候補メモはページ内だけで送信しない。desktop/狭幅のrender・keyboard・overflow・simulation状態を確認し、
本人の暗所視認性/到達性/最終選択は別に残す。正式選択後に別の限定IssueでAndroidへ実装する。

## P0.5-B4: 標準tap AF/AE（Issue #21）

中央ボタンとpreview内tapを同じFocusMetering処理へ統合する。標準GestureDetectorでtapを認識し、
scroll/drag/long pressをfocus操作へ変換しない。中央ボタンはaccessibility用にも維持する。
view内のfinite座標/寸法を検証し、COMPATIBLE previewのSTREAMINGを待ってから
PreviewView.meteringPointFactoryで作った単一測光点をCameraInfo.isFocusMeteringSupportedで照会する。
MeteringPointの座標getterは1.6.2でRestrictedApiのため使用せず、独自のsensor変換も実装しない。

CameraXの照会はAF/AE等のいずれか有効部分があればtrueであり、全group適用の保証ではない。
またfull sensor上でvalidな点が表示crop外/黒帯へ対応する可能性があり、全黒帯の拒否は保証しない。
UIで表示画像内へのtapを案内し、実機の境界/mapping比較を残す。独自のcrop/matrix回避策は追加しない。
要求はview座標/要求flags、実側はAF/AE regionsとAF state/resultを分離して示す。
MFではAFを要求せず、MANUAL AEかつMFなら理由を出す。busy/録画/Finalizeでは新要求を抑止し、
5秒autoCancel・epoch/closedを維持する。新依存/権限/use-case/独自追従は追加しない。
実機では中央/四隅、FIT_CENTER黒帯、回転/異なるlens/mode、drag、MF/AUTO制約と実regionを確認する。
view座標のunitを実カメラのsensor mappingや動体合焦の証拠にしない。

## P0.5-B5: 一時的AE/AWB lock（Issue #23）

照明変化に対するAUTOの変動を比較するため、available特性とrequest keyの双方で対応したlockだけを操作する。
ロックは現在のbindingの一時要求でpreferencesへ保存しない。camera/mode/rebind/StopStart/アプリ再生成、
AUTO全解除ではemptyへ戻し、次のbindingへ古いlockを暗黙復元しない。
同一bindingの背景復帰では要求が残る場合があるため、実CaptureResult確認を残す。

AE lockはAUTO露出のみ、AWB lockはAUTO WBのみ。MANUAL露出へ変更時はAEを、固定WBへ変更時はAWBを解除し、
他のeligible lock・override・保存希望は保持する。単一request snapshotへtrueのlock keyを合成し、falseは上書きを除外する。
処理中/録画/Finalizeでは変更不可。Futureはepoch/closedを維持し、失敗は状態unknownとAUTO resetへの導線を示す。

requested/ACCEPTEDとactual AE/AWB lock・AE/AWB stateを別に記録する。AE lock中もEV補正は露出を変え得る。
AE OFFでのAWBの挙動には端末差があり、色の安定やちらつきのない移行を保証しない。実機の照明/EV/手動/preset/
再bind/背景/動画の組合せは未検証として残す。新権限/use-case/AF lock/色gain/持続設定は追加しない。

## P0.5-B6: 応答段階と周期サンプル（Issue #25）

既存のcallback/保存とCaptureResultを使い、SystemClock.elapsedRealtimeの差分でアプリ内の段階を記録する。
写真は要求→各形式file callback→全file完了→IO queue/metadata/publication→UI通知を分離し、
RAW+JPEGや失敗/遅延callbackもtokenを維持する。動画は要求→Start通知、Stop要求→terminal通知、
terminal→IO完了→UI通知を区別する。出力/cleanup・gate/epochを変えない。

単一CaptureResultのSENSOR_FRAME_DURATIONが正なら逆数を周期換算fpsとして表示し、null/非正値はunknown。
既存の1秒sample throttle後にだけ換算する。独自FPS estimator、毎frame画像処理、新use-case/権限は追加しない。
アプリ時間はsensor shutter lag/codec遅延/Gallery indexing時間ではなく、周期サンプルはpreview描画や
保存MP4のsample timestamp由来fpsを保証しない。Pixel 9aの条件別応答/周期/熱との関係は未確認とする。

## 公式資料による採用判断

- [CaptureResultのframe duration](https://developer.android.com/reference/android/hardware/camera2/CaptureResult#SENSOR_FRAME_DURATION)：
  センサー周期の値を単一サンプルとして扱う。アプリcallbackやpreview/保存動画のFPSへ読み替えない。
- [PreviewViewの測光点](https://developer.android.com/reference/androidx/camera/view/PreviewView#getMeteringPointFactory())：
  ScaleType/layoutに従う標準変換を使い、未準備のinvalid点を安全に扱う。1.6.2実artifactでは
  full sensor範囲の判定と表示crop境界が別であることも確認し、全黒帯拒否を保証しない。
- [SAF/文書Intent](https://developer.android.com/guide/components/intents-common#Storage)：
  ACTION_CREATE_DOCUMENTでユーザーが選んだDocumentProviderのcontent URIへ一度だけ書く。
  写真の取得/送信Intentやバックエンド、継続grantの取得へ広げない。
- [CameraX動画構成](https://developer.android.com/media/camera/camerax/video-capture)：
  Recorder/VideoCapture、音声の明示有効化とFinalize結果を出発点にする。
  現行referenceには1.7のQuality.QHD等も含まれるため、例をそのままコピーせず1.6.2で照合する。
- [CaptureRequest](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest)：
  AF/AE/anti-banding等は能力とmodeを照合し、要求受付だけで動体/LED品質を保証しない。
- [Thermal API](https://developer.android.com/games/optimize/adpf/thermal)：
  OSの熱状態を応答・fps低下と併記する。固定の安全な温度や性能保証を推定しない。
- [Pixel 9a仕様](https://store.google.com/product/pixel_9a_specs?hl=en-US)：公称の背面4K30/60、48MP wide等は候補を
  選ぶ参考にする。CameraXで利用可能な同時構成や実解像度の証拠に置き換えず、displayのHDR対応を動画HDR収録と混同しない。

これらは設計調査であり、Pixel 9a実機結果や新APIのcompile証拠ではない。後続実装時は固定版artifactで確認する。
