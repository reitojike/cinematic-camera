# P0.5 検証レポート

対象: `dev.cinematic.p0` / `0.0.10-p05` / CameraX **1.6.2**

関連: [Issue #8](https://github.com/reitojike/cinematic-camera/issues/8) / [spec](../specs/002-live-capture/spec.md) / [plan](../specs/002-live-capture/plan.md)

**実装候補と自動検証の結果であり、Pixel 9aの動画・音声・fps・HDR・画質・熱の実機合格ではない。**
P0のユーザー成功報告と不足資料は[P0_REPORT](P0_REPORT.md)とIssue #4を維持する。

## 実装した範囲

- 同じPreviewView上のSTILL/CINE切替。録画はRecord操作でだけ開始し、モード切替・起動だけでは記録しない。
- CameraXの具体的dynamic range、quality、Preview+VideoCapture組合せ別fpsとsession対応を照会。
  未設定時は対応SDRの最高解像度→高fpsを優先する。保存済みユーザー候補が有効なら優先し、
  非対応/失敗時は希望値を残したまま代案と理由を示す。
- HDRは列挙された具体的rangeと対応sessionだけを候補にする。Pixel 9aでHDRが使えたとは判断していない。
  Quality.QHDやRestrictTo API、1.7の新APIは使用しない。
- STILLは利用可能な高解像度とsensor aspectを優先し、bind失敗時は同じ出力形式のCameraX defaultへfallbackする。
  公称48MPや最高画質は保証しない。保存前の横長cropは追加していない。
- mode/camera別のISO/シャッター、カメラ/出力形式、動画候補、音声/解像度の希望をローカル保持。
  bind時はInteropを解除してから、当該modeで受付済みの設定を復元する。未適用の入力draftを勝手に適用しない。
- 動画は選択fpsに対して長すぎる手動露光を拒否する。実センサーのframe durationと保存MP4のsample timestampは
  要求値とは別に記録する。
- マイク権限は音声ありのRecord操作時に要求。拒否時は録画を開始せず、許可または明示的な音声offを案内する。
  音声state/amplitudeを表示するが、振幅は音割れがない証拠ではない。
- MP4はMediaStoreの`Movies/Cinematic Camera/`へpending row/descriptorで保存。
  Finalize後にdescriptorを閉じ、非ゼロbyte・寸法・duration・video sampleを検査してから公開する。
  失敗時は今回作ったrowだけをcleanupし、cleanup失敗も報告する。
- JPEG/DNGは既存の`Pictures/Cinematic Camera/`を維持。既存原本や旧内部データの移行/削除なし。

## 状態・出力と制約

Recorderの完了と、MP4検査/MediaStore公開完了は別の状態。Finalizeと出力処理が終わるまで次の録画や
カメラ/mode/候補変更を抑止する。録画中の背景化では停止を要求し、背景録画は行わない。
close/unbindに伴うsource inactive等は通常成功へ混ぜず、結果とcleanupをログに残す。
遅れた旧tokenのイベントは新しい録画状態を書き換えない。

音声source/encoderが途中で失敗しても有効な映像と音声trackが残る場合がある。
そのためエラー・source silencedを録画単位で保持し、最終UI/logへAUDIO_WARNINGとして残す。
音声失敗だけを理由に有効な映像を必ず削除する設計にはしていない。

要求したquality/fps/range、bindで選ばれた構成、保存MP4の寸法/回転/duration/codec/color、
最大30,000 video samplesのtimestampから求めた平均fpsを区別する。
sample metadata検査は全フレームのdecode・再生品質・音声品質を証明しない。
JPEGはheader寸法、DNGはExif寸法を記録するが、DNG全体のdecode証明には使わない。

## 機械的検証

- ローカル `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug` を実行。
- unit **40件、failure/error/skip 0**。既存11件に、能力候補選択・希望値fallback・録画状態/旧token隔離・
  音声途中失敗/無音警告・出力close/検証/公開/cleanupの12件、EV/thermalの3件、override能力/範囲/fallback/保存保持の4件、
  report UTF-8/tail境界とopen/write/flush/close失敗の3件、view座標変換/非finite/範囲/未準備の1件、
  AE/AWB lockの能力/AUTO条件と不適用時解除の2件、周期換算/unknownの1件、保存済み露出pairの復元境界3件を追加。
- lint **error 0 / warning 10**。Gradle更新案内1、任意のKTX提案6、metadata記録に使う標準ExifInterfaceへの
  Jetpack推奨3。baselineや抑制は追加しない。Exif読み込みは素材の現像/有効性判定に使用しない。
- API1.6.2の実artifactでcompileし、MediaStore/音声/SessionConfigの署名を確認。
- 独立レビューで適用済み表示のsession分離と音声警告の保持を確認する。最終headのcanonical結果・CI・threadは
  PR/Issueの証跡を使い、古いheadの結果を再利用しない。

unitは実カメラ・Android MediaStore provider・マイク・encoderの代用ではない。
実機操作、追加GMD、実写素材取得、NDK/GPU/現像/製品UIは行っていない。

## ユーザーが確認する実機条件

| 対応 | 確認内容 | 現状 |
|---|---|---|
| V01 | STILL/CINE往復、mode別ISO/時間、設定保持、切替の待機/blackout/画角 | 未確認 |
| V06 | Record/Stop、音声許可/拒否/off、MP4再生、実寸法/fps/range、音声/同期/歪み | 未確認 |
| V08 | 録画中/停止直後の背景化、回転による停止、Finalize/音声解放/保存、復帰後の再録画 | 未確認 |
| P0回帰 | JPEG/RAW/RAW+JPEG、AUTO/MANUAL、既存共有保存と実測ログ | 新実装での再確認は未実施 |
| 高能力/HDR | 実カメラの対応一覧、初期選択、4K60等の実出力、HDR codec/transferと閲覧 | 未確認 |
| V02〜V05/V07 | 暗所/動体AF/色照明/LED/応答/発熱の品質 | 未確認、後続の限定検証 |

エラー時はState/Recent logの種類・選択候補・再現手順を控える。写真/音声本体・未編集ログをGitへ公開しない。
実機未確認を理由に合意済みの次の限定開発を止めないが、重大欠陥・テスト失敗・権限判断・大幅scope変更時はHOLDする。

## B1: 標準AF/AEと照明・熱テレメトリー

[Issue #13](https://github.com/reitojike/cinematic-camera/issues/13)の範囲で、Camera2のAF/AWB/anti-banding mode、
AF/AE領域、AE補正範囲/stepと、CaptureResultのAF/AWB状態・focus lens units・EV・anti-bandingを追加した。
値が取得できなければnull/unknownとし、能力・要求・結果を別に扱う。

- Center AF/AEはPreviewViewのmetering pointとCameraX標準APIを使用。対応照会後にユーザー操作で要求し、
  Futureの合焦結果を動体追従や画像品質の合格にしない。MANUAL AEではAFだけを要求し、AF非対応なら理由を示す。
- EVは受理済みAUTO・対応範囲内だけで操作。mode別希望を保持し、AUTO clear→EV受付→必要なら保存済みMANUALの
  復元を直列化する。無効な保存値はneutral fallbackと理由を示し、希望値を黙って書き換えない。
- focus/EVのFutureはepoch/closedで隔離し、bind/stop時は現在sessionの表示を初期化する。
- PowerManager thermal statusを観測し、SEVERE以上で録画停止/Finalize、新規録画の抑止を行う。
  listenerはcloseで解除する。OS severityを温度℃や安全温度へ変換しない。

B1では新依存・新権限、WB/MF/anti-bandingの手動上書き、フリッカー解消アルゴリズムは追加していない。
26件のunitとbuild/lintは成功したが、AF追従・LED・EV反映・熱応答の実機条件は未確認。

## B2: WB preset・MF・AE anti-banding

[Issue #15](https://github.com/reitojike/cinematic-camera/issues/15)の範囲で、available modes/request keysに対応する
標準WB presetとAE anti-banding、MANUAL_SENSOR/AF OFF/focus key/有限rangeを満たすMFを追加した。
WB OFF/gains/transform/Kelvin指定は含めない。MFはlens unitsで、未校正値からメートルを保証しない。

露出・WB・MF・anti-bandingは一つのInterop snapshotを置換する。個別変更で他の受付済み制御を保持し、
MF変更前はCameraX cancelFocusAndMetering完了を待つ。AF復帰時はAF_MODE/LENS_FOCUS_DISTANCEを除外する。
MF中はCenterからAFを要求せず、AUTO AEが有効な場合だけAE測光する。
明示的な`AUTO reset all`はfocus/Interop全解除とneutral EV。mode復帰はclear→対応する保存control→EV→
必要なら保存MANUAL露出の順で受理を待つ。非対応の保存値は項目別fallbackと理由を示し、希望値を保持する。
変更中/録画中/Finalize中は次のcontrol操作を抑止し、古いFutureはepoch/closedで隔離する。
個別操作はその項目だけを保存し、他項目が非対応fallback中でも元の保存希望を消さない。

要求WB/focusと実結果、scene flickerを観測する。scene flickerはLED/PWM除去の証拠ではなく、
AE anti-bandingはMANUAL AEで効果を持たない。30件のunit/build/lint成功と実機品質を区別する。

追加の手動検証は、STILL/CINEごとのWB/MF/anti-banding復元、WB変更後もmanual ISO/時間が残ること、
MF/AF往復、AUTO全解除、色照明下のpreset比較、50/60Hzとmanual shutter比較。
すべて実機未確認であり、原本/素材の自動撮影・公開はしていない。

## B3: 検証レポートのSAF保存

[Issue #17](https://github.com/reitojike/cinematic-camera/issues/17)の範囲で、Save verification reportから
標準CreateDocument(text/plain)を起動し、本人が選んだ文書へUTF-8で保存する。新permission/persistable grantはない。
main上の状態/要求/実結果/能力snapshotと、IO上で後から読む現在session log tailを区別して時刻を示す。
tailは最大128KiB、最初の部分行/UTF-8断片を捨て、省略と不完全なlogであることを明記する。

出力はIO executor上で行い、write/flush/closeすべて完了してから成功表示する。
provider/書込/close失敗は部分文書が残る可能性を表示し、文書や写真原本を勝手に削除しない。
取消・picker起動失敗・重複・録画/Finalize中を扱い、旧Activity終了後はUIを書き換えずログへ結果を残す。

写真/動画/音声本体・GPS・端末serialを追加取得しない。レポートは機種/build/設定/出力URI等を含むため、
本人が内容を確認してから共有する。アプリからIssue/クラウドへの自動送信はない。
SAFでの保存/取消/権限失敗/読み戻し、録画中の抑止、端末での内容確認は未検証。

## B4: preview tapによる標準AF/AE

[Issue #21](https://github.com/reitojike/cinematic-camera/issues/21)で、標準GestureDetectorの単一tapを
中央ボタンと同じFocusMetering処理へ接続した。drag/long pressを生のACTION_UPでfocusへ変換しない。
accessibility用の中央ボタンを残す。操作中/録画/Finalize中は新しい要求を行わない。

有限view座標・寸法とCOMPATIBLE previewのSTREAMINGを確認し、公開factoryと対応照会だけを使う。
MeteringPointのRestricted getter、独自matrix/顔検出/追従、新permission/use-caseは追加していない。
CameraX判定はfull sensor上の有効点/一部group対応の判定であり、AF/AE全groupや表示crop境界の保証ではない。
FIT_CENTER黒帯がsensor範囲内へ変換される場合は受理され得るため、表示画像内へのtapを案内する。

要求はviewNormalized/flagsとして記録し、sensor座標と呼ばない。実request/result AF/AE regionsを別に観測する。
MFはAFを要求せず、AUTO AEが有効ならAEだけ。MANUAL AEかつMFでは理由を表示する。
Futureはepoch/closedで隔離し、5秒autoCancelを維持する。

view座標のunitとbuild/lintは成功したが、中央/四隅/黒帯・lens/mode/回転ごとのmapping、
スクロールとのgesture競合、実region、動体合焦品質は実機未確認。
回転時はActivity再生成により録画を停止する設計で、継続録画を保証しない。回転→保存完了→再録画をV08へ含める。

## B5: 現在bindingの一時AE/AWB lock

[Issue #23](https://github.com/reitojike/cinematic-camera/issues/23)で、Camera2 available特性とrequest keyを
照合したAE/AWB lockをAUTO条件でだけ操作する。既存snapshotへlock keyを合成し、他のcontrol/保存希望を維持する。
MANUAL露出へ変更時はAE lock、固定WB presetへ変更時はAWB lockだけを解除する。

preferencesへ保存せず、camera/mode/rebind/StopStart/アプリ再生成とAUTO全解除で両lockを解除する。
同一bindingの背景復帰では要求が残り得るため、状態と実結果を再確認する。録画/Finalize/処理中には変更しない。
Futureはepoch/closedで隔離し、同期/非同期失敗は状態unknownとAUTO resetへの導線を示す。

requested/ACCEPTEDとactual AE/AWB lock・AE/AWB stateを別に観測し、記録開始時のlock希望もログに残す。
AE lock中もEVは露出を変え得る。AE OFF時のAWBは端末依存で、色安定・フリッカーなしの移行を保証しない。
36件のunit/build/lint成功と、照明変化・EV/手動/preset・mode/rebind・背景・動画の実機結果は別であり、後者は未確認。

## B6: アプリ応答段階とsensor周期サンプル

[Issue #25](https://github.com/reitojike/cinematic-camera/issues/25)で、単調clockのelapsed差を既存のcallback/
保存処理へ追加した。写真は要求→各形式file callback→全callback→IO queue/metadataと公開→UI通知を分ける。
RAW+JPEGは両callbackを記録し、失敗/遅延callbackでもtokenに対応した時間を残す。
動画は要求→Start event、最初のStop要求→terminal event、terminal→IO/検証/公開完了→UI通知を分ける。
次のrecordingでStop時刻を初期化し、既存gate/token/epoch/cleanupを変えない。

時間はアプリ操作/配送/IOを含む段階の観測であり、sensor shutter lagやcodecの処理遅延を分離計測したものではない。
MediaStore公開のAPI戻り時刻は、Googleフォト等が一覧へ索引/表示する時刻でもない。
CaptureResultの正のSENSOR_FRAME_DURATIONだけを逆数fpsへ換算し、null/非正値はunknownとする。
既存1秒sample throttle後だけに換算し、preview描画fpsやMP4のsample timestamp由来fpsと区別する。
新しい毎frame処理・独自推定・画像分析・use-case/permissionはない。

37件のunit/build/lint成功は、Pixel 9aでの実際の応答時間・period/MP4/表示fps・熱との関係の検証ではない。
同じカメラ/モード/照明/露出・形式で比較し、要求/実period/出力MP4/各app段階を別に記録する。実機条件は未確認。

## 統合品質と自動検証の保証境界

[Issue #29](https://github.com/reitojike/cinematic-camera/issues/29)の対象は、個別機能間のsession/保存/復元境界。
独立の横断静的監査では、連続mode切替、Record/Stopと音声許可拒否、背景/Destroy、旧callback隔離、
pending保存とcleanup、UIのrequested/accepted/actual分離を確認した。実際のActivityResult配送やCameraX/MediaStore実行を再現した検証ではない。

修正した境界：以前はmanual flagと受付済みISO/時間を別々に保存し、片方が欠けた復元時に入力draftで補っていた。
部分保存状態から未適用値を適用できるため、flagと受付済みpairを単一editorで保存する。
復元は完全かつ現在の範囲内のpairだけを使い、欠落/不正なら保存希望を維持してcontrolReady=falseとAUTO reset案内を出す。
中断時に必ず欠落するという主張でも、SharedPreferencesの永続化完了を保証する変更でもない。

| unit対象 | 件数 | 確認する境界 | 確認しないもの |
|---|---:|---|---|
| Exposure | 8 | 単位/範囲/対応条件、周期unknown、受付済み完全/欠落/不正pair | CameraProbeの全非同期列、センサー実反映、SharedPreferencesの実disk書込み |
| PhotoPublisher | 7 | byte不変コピー、両stream close後の公開、insert/open/write/close/publish失敗と自分のrowのrollback | CameraX撮影callback配送、Android MediaStore provider、DNG全decode |
| VideoPolicy / RecordingGate / AudioQualityNotes | 7 | 候補優先/fallback、開始中Stop・重複/旧token、公開待ちgate、音声警告保持 | 権限画面/lifecycle配送、native encoder、実音声/同期/歪み |
| VideoPublication | 5 | close→metadata検査→公開、Recorder/close/validate/publish/delete失敗 | MediaExtractorの実ファイル処理、全frame decode、実provider cleanup |
| ProbeControls | 4 | EV範囲/AUTO条件、view座標/非finite、OS thermal severity判定 | 実tapのsensor mapping、EV反映、実温度/発熱安全性 |
| SensorOverrides / AutoLocks | 6 | 支持能力/値/独立fallback/保存項目、AUTO条件と不適用lock解除 | Camera2とCameraXの実競合、AF追従、実AWB/lock/LED効果 |
| VerificationReport | 3 | UTF-8/tail上限、open/write/flush/close失敗と完了判定 | Android SAF picker/provider、実端末の文書読み戻し |

37件の基準に受付済みpairの回帰3件を加えた40件。静的監査と純粋unitは相互補完であり、
実カメラやAndroid非同期処理の統合テストではない。mock基盤のための大規模変更や追加GMDは導入しない。

lintの分類：

| 警告 | 件数 | 判断 |
|---|---:|---|
| AndroidGradlePluginVersion | 1 | Gradle 9.8.2の更新案内。現行9.8.1でbuild成功、Renovate別PRで独立レビューする |
| UseKtx | 6 | Uri変換3、SharedPreferences edit3の任意style提案。原子的保存のeditor追加で1件増加。新依存や抑制を追加しない |
| ExifInterface | 3 | AndroidX実装の推奨。現行は標準ExifでDNG寸法を診断用に読むだけで、失敗は記録して撮影原本の公開を続ける。RAW有効性/現像品質を判断しない。P1で必要性を再評価 |

### 朝の実機確認（5項目）

1. 最新Releaseの版を確認する。一時debug署名は旧版と異なり上書き不可。必要なアプリ内ログ/設定/媒体を退避し、再インストールの判断は本人が行う。
2. STILLでJPEG/DNGを保存して共有フォルダーから開く。STILL/CINEに異なるISO/時間をApplyし、draftだけ編集して往復し、Desiredの受付値と実CaptureResultを確認する。
3. CINEでマイク拒否→録画されないこと、明示audio off→短いMP4保存/再生、許可後audio on→再生と音声を確認する。要求候補と保存metadataは別に確認する。
4. 録画中の背景化/回転→停止/保存完了→再録画を確認する。継続録画は保証しない。クラッシュ/無応答/保存失敗時は後続操作を控え、表示エラーと再現条件を残す。
5. Save verification reportで文書保存・取消・読み戻しを確認する。共有前に内容を確認し、写真/音声や未編集のログをpublic Gitへ置かない。

| 優先度 | 実機残件の管理先 |
|---|---|
| 最優先 | #8: 切替/設定復元・録画/権限・MP4再生・背景/回転後の再録画。#17: 証拠文書の保存/取消/読み戻し |
| 次 | #13/#15/#21/#23: AF追従/測光位置・WB/MF・lock・LED/色照明・熱。#25: app応答/センサー周期とMP4/描画fpsの比較 |
| 証拠補完 | #4: P0成功報告の端末/build/camera ID・個別値/ファイル等の不足資料。P0.5の成功へ流用しない |

実機結果がない項目は未確認のまま。Stage Clear等のルック/RAW現像/製品UIはこの統合修正に含めない。
