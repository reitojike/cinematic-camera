# 通常保存の工程時間記録

通常の保存受付と明示的な公開再照合について、アプリ私有の `noBackupFilesDir/save-timing` に記録する。
記録は診断情報であり、保存・公開・取消・復旧の判定には使わない。記録の作成・書込み失敗は保存側へ伝播させない。
原本、JPEG、署名、DNG展開の方式とメモリ条件は変更しない。

## 形式と保持範囲

UTF-8互換のASCII TSV、1行1イベント、ヘッダなし。列の順は次のとおり。

| 列 | 意味 |
|---|---|
| schema | 現在は `1` |
| attempt | 保存受付ごとのランダム128bit識別子。写真・世代・端末から導出しない |
| version | APKのversionCode。正式/開発の固定署名版は同じ単調増加採番 |
| kind | `SAVE` または明示再照合の `RECHECK` |
| stage | 固定工程名 |
| outcome | 固定結果名 |
| pixels | 検証済み出力幅×高さ。未取得は `NA` |
| nanos | CPU単調時計の経過ナノ秒。未計測は `NA`（0と区別） |

名前、URI、captureId、世代要求、写真fingerprint、画像、例外本文/stack、実時計、端末識別子は記録しない。
同じattemptの寸法確認後のイベントに画素数が入り、展開前や展開直後の寸法確認前は `NA` になる。
公開のみ再試行するときは既存JPEGの出力寸法を使う。生成を行わなかった工程に0msを付けない。

最大1,024イベントかつ256KiB。古いイベントからローテーションし、読み出し中も上限を守る。
固定名の一時ファイルを置換するため、記録領域は通常ファイルと一時ファイルを合わせ最大512KiB。
終了しなかった一時ファイルは抽出しない。再起動・同一IDの通常アップデート後も私有記録を保持するが、
アンインストール、アプリデータ削除、端末喪失、ローテーション後の過去記録は回復を保証しない。
不正schema・不正列・切れた末尾は抽出せず、上限超過ファイルは読取り対象にしない。
複数store/試行の書込みとsnapshotはプロセス内の同じロックで直列化する（アプリは単一プロセス）。

## 計測区間

| stage | 含む処理・限界 |
|---|---|
| NATIVE_DECODE | 既存JNI呼出し `NativeOriginalRaster.decode24`。入力の事前照合・snapshotコピー・事後descriptor close・縮小解析は含まない |
| B1 / B1_QUANTILES / B2 / B3 / B4 / B5 / FINAL | `GpuExportRenderer` の同名boundary。CPU側の帯入力読込み・変換・GL発行・読戻し等と既存のGPU fence完了待ちを含むwall。純GPU timer値ではない。新たなfenceは追加しない |
| GPU_RENDER | GL thread/contextの準備からrenderer実行、RGB16 セッションの LUT 生成・転送、既存の完了待ち、thread/context cleanupまで |
| JPEG_ENCODE | `openOutput`、Bitmap.compress、stream write・digest更新・flush・closeを含む既存区間。JPEGコーデック単体の時間ではない。事後JPEG検査・原本再照合・公開を含まない |
| RESOURCE_CLOSE | 既存資源解放の各呼出し。複数回を別イベントとして保持し、最後の値で上書きしない |
| PUBLICATION | 既存publisherのadvance全体。pending行処理・copy・検証・公開やjournal処理のうち実際にadvanceが実行した範囲。再試行では再利用/再照合のみの場合もある |
| RECONCILIATION | 明示的な既存公開記録の読取りとreconcile。生成とは別イベント |

`COMPLETED` はその区間の正常returnだけを示す。公開成功の証拠は既存journalであり、
記録上の全体結果は `ATTEMPT/PUBLISHED` 等で区別する。`INCOMPLETE` は区間途中の例外・取消等であり、
完了待ちが成功した計測とは扱わない。失敗区間の経過値を成功値の集計へ入れない。
既存のBITMAP_PACKはGPU全体と記録I/Oを内包するため本記録では採用しない。
GPU_RENDERとB1〜FINALも内包関係にあり合算しない。

実際の分岐で省略したB2/B3/B5は `SKIPPED`。失敗前に未到達の工程は `NOT_REACHED`。
収集失敗や成功した描画の記録欠落は `UNAVAILABLE`。未取得を0で補間しない。
GPU工程の結果はGPUスレッド内で収集し、既存Future完了後にqueue IOでメモリ記録へ加える。
帯・画素・工程ごとのディスク書込みは行わない。

### 終了時の一括記録（dev.6 向け）

保存試行中は最大1,024イベントのメモリ内バッファだけを更新し、既存の保存処理・公開・資源解放が
終了した時点で、成功・失敗・取消・保留のいずれでも1回だけファイルを読み取り、一時ファイルへ書いて置換する。
複数試行の一括書込みとsnapshotは同じロックで直列化する。二重finishや終了後のイベントは書き込まない。
既存dev.5のschema 1とSTARTED行はそのまま読み取り・保持し、新規のSTARTED行だけを出さない。
通常のGPU新規保存は15行（GPU境界7、展開・GPU全体・JPEG・公開各1、資源解放2、
未到達の照合1、最終結果1）。省略・未到達の値はNAのままで、0を捏造しない。
再照合・失敗・再試行では行数が異なる。

各工程の終了時刻はメモリへのイベント追加より先に採る。最終ATTEMPTのnanosは
beginTimingから既存保存処理が戻った直後までのwall timeで、最終的な記録のファイルI/Oは含まない。
中に実行される小さいメモリ記録処理や受付・後処理は含む。純GPU時間や各工程の単純合計とは扱わない。
dev.5のATTEMPTには全体経過値がないので、全体時間を直接比較できない。
dev.5のGPU_RENDERには内包する工程の記録I/Oが含まれるため、版間の差には記録方式の改善も混ざる。
旧版の時間を、記録方式だけを補正した値として推定しない。

終了前にOSに終了された試行はメモリ内の記録が失われる。開始行は永続化しない。
記録がないことを保存失敗・取消・OS終了の証拠にはしない。保存状態の復旧は既存journal（#377）が担う。
記録は耐障害journalではなく、fsyncによる電源断耐性は保証しない。書込み失敗も保存へ伝播しない。

### dev.5の保持回数と比較手順

通常のGPU新規保存が毎回15行の場合、dev.6で68回保存すると1,020行、69回では1,035行になる。
新規記録だけで上限を超える69回目には、残っていたdev.5記録は全てローテーションされる。
ただし「68回までdev.5全件が残る」という意味ではない。アップデート時点の有効行数をNとすると、
既存記録を1行も落とさず追加できる通常保存回数は floor((1,024 - N) / 15)。
既に満杯なら最初の保存から古い行が消える。再照合や失敗等も行数を消費するため、
保存回数だけでは厳密な残存量を判定できない。固定schemaの最大行長でも1,024行は256KiB未満なので、
正常な記録ではイベント数の上限が先に効く。Pixelの現在のNは抽出するまで不明。

1. dev.6導入前にdev.5の記録をUSBで回収する。抽出はアプリ側の記録を消さない。
2. 抽出ファイルを保管し、versionとattemptで版・試行を区別する。繰り返し抽出の重複を別試行として数えない。
3. 比較対象はNATIVE_DECODE、B1〜FINAL（B1_QUANTILESを含む）、JPEG_ENCODE、PUBLICATIONに限る。
   同じ画素数・工程・成功状態で比較し、INCOMPLETE、NA、SKIPPED等を成功の0msにしない。
   展開直後はpixels=NAなので、NATIVE_DECODEの画素数は同一attemptの後続行から照合する。
   対応する画素数が取得できない試行は画素数別の比較に混ぜない。
4. GPU_RENDERとATTEMPTはdev.5/dev.6比較から除外する。旧版の記録I/O影響や計測値欠如があるため。
   GPUの各境界は別途収集してFuture完了後に記録し、展開・JPEG・公開は開始記録後から終了記録前まで
   時計を取る既存区間を維持する。この比較でも普段使いの入力・端末状態の違いは別要因として扱う。

## USBからの抽出

非debuggableの通常releaseでも、USBデバッグを本人が許可したPCのadb shellからだけ読み取れる。
`DUMP`権限とshell UIDを確認する固定URIのread-only ContentProviderを使用する。
URI grant、任意path、書込み、写真や他の私有ファイルへのアクセスは提供しない。
shellのDUMP権限は [AOSP Shell manifest](https://android.googlesource.com/platform/frameworks/base/+/master/packages/Shell/AndroidManifest.xml)、
pipeによるread-only読出しは [ContentProvider.openFile](https://developer.android.com/reference/android/content/ContentProvider#openFile(android.net.Uri,java.lang.String)) に従う。

本人の実機操作承認後、接続先を確認して次を実行する。placeholderを実際の許可対象へ置き換える。
取得した記録はローカルで保持し、写真や端末識別子を公開ログへ加えない。

```text
adb -s <authorized-device> exec-out content read --uri content://dev.cinematic.p0.save-timing/records
```

標準出力をホスト側でファイルへ保存する。`run-as`、root、アプリのdebuggable化は不要。
開いている保存を停止せずsnapshotを取得でき、取得失敗を保存失敗とみなさない。
抽出は記録を消さない。実機での取得可否は、合成AVDの受入結果と区別して記録する。


## 詳細内訳の追加（Issue #457、提案）

既存の時間行は schema 1 の8列を維持する。開始・終了の環境行だけ schema 2 の10列で、
stage は `ENVIRONMENT_START` / `ENVIRONMENT_END`、nanos は NA、末尾に visibility と thermal を追加する。
visibility は `FOREGROUND` / `BACKGROUND` / `UNKNOWN`。Application.ActivityLifecycleCallbacks の
started Activity 数が1以上なら前面、0なら背面とする。onPauseのみでは背面にしない。
これは画面の可視状態であり foreground service の状態やプロセス優先度ではない。
thermal は PowerManager.currentThermalStatus の0〜6、取得不能は NA。途中の最大値は取得しない。
Activity名、端末情報、温度、写真、URIは記録しない。開始取得時間は ATTEMPT に含み、終了取得時間は含まない。

既存 span の SOURCE_VERIFY_BEFORE / SOURCE_SNAPSHOT / DECODE_CLOSE / ANALYSIS_REDUCE /
CONTROL_PREPARE / JPEG_INSPECT / SOURCE_VERIFY_AFTER / SCRATCH_CLEANUP を保存記録へ接続し、
既存 Bitmap.createBitmap 呼び出しだけを BITMAP_ALLOC で囲む。処理順や画像計算は変更しない。
これらは重なりや計測外の制御処理を含むため、差分が必ず0になる保証はない。

B1 / B2 / B3 / B4 / B5 / FINAL ごとに以下を追加する。

- INPUT_WAIT: GL側が入力帯の準備を待つwall時間。
- INPUT_READ_CONVERT は reader の行走査全体から後続の空き buffer 待ちだけを引いた wall 時間。従来の RGB16 経路では読込み・Float 変換・RGBA 詰め替えを含み、LUT 経路では RGB16 の直接読込み・帯充填を含む。Float 入力の RGBA 詰め替え、行検証、帯投入、EOF 検査・close も該当経路で計上する。初回 buffer 取得、帯 buffer 確保、reader thread の起動・join は範囲外。従来と同じキーで収集し、純読込み時間や純 CPU 時間とは区別する。
- UPLOAD は帯の GL 転送 API 呼出しの CPU wall 時間。RGB16 入力は RGB16UI（6 B/画素）、Float 入力は RGBA32F（16 B/画素）を転送する。LUT の初期化は GPU_RENDER に含み、帯の GPU 変換は既存の完了待ちに反映される。
- FENCE_WAIT: renderer と B4/B1_QUANTILES 内部の既存明示fence待ち。ドライバ内部の暗黙待ちは別項目にも残り得る。

B1_QUANTILES_FENCE_WAIT、B1_QUANTILES_READBACK、B4_READBACK、FINAL_READBACK、FINAL_ROW_PROCESS も記録する。
GPUタイマー問い合わせ、新たなfence、glFinishは追加しない。reader とGLは並行するため単純加算禁止。
readback APIに含まれる暗黙同期はREADBACK側に残る。not reached / skipped / unavailable と0を区別する。
FINAL_ROW_PROCESS は帯内の行ループ全体（取消チェック、RGBA8→ARGB変換、帯 consumer による Bitmap.setPixels）をまとめたwall時間。行順・取消チェック位置は変更しない。
INPUT_PACK と行単位の変換/consumer分離は未配布の詳細案から削除し、帯・走査単位だけで計測する。
時計取得失敗・負の差分はNA、失敗した途中区間はINCOMPLETE。到達しなかったbuffer待ちだけは差引き0とする。
ディスクI/Oは従来どおり終了後の一括書込み。

記録上限1,024行・256KiBは変更しない。上記の15行/68回は旧形式の例であり、追加後の保持回数には使わない。
既存N行、新規1回R行なら既存行を削らずに追加できる回数の目安は floor((1024-N)/R)。
失敗・省略・再照合によりRは変わる。古いschema 1行は引き続き読める。

公式API: [ActivityLifecycleCallbacks](https://developer.android.com/reference/android/app/Application.ActivityLifecycleCallbacks)、
[PowerManager.getCurrentThermalStatus](https://developer.android.com/reference/android/os/PowerManager#getCurrentThermalStatus())。

## 実Activity lifecycle の debug 限定回帰

`SaveTimingLifecycleAndroidTest` は `androidTestDebug`、空の probe Activity と manifest は `debug` のみに置く。
release の本体・manifest に probe を含めず、試験コードからも debug Activity の型を直接参照しない。

既存の認可済み emulator に debug 本体と test APK を導入した後、通常の AndroidJUnitRunner に次を渡す。
adb は必ず選択済み emulator serial を `-s` で指定し、他の Activity 試験やUI操作と同時実行しない。

```text
-e isolated true -e timingLifecycle true
-e class dev.cinematic.p0.metrics.SaveTimingLifecycleAndroidTest
```

完全一致の class/method selector と opt-in を必須にし、複数classをまとめた実行は開始前に拒否する。
各確認時には対象 Application 内の他Activityも検出し、干渉を通常の前面/背面結果に読み替えない。
開始・pause・stop・再開・再作成・終了で状態を断定し、同一attemptの開始/終了の環境行も確認する。
providerの自動登録を試すため、試験から `install()` は呼ばない。写真・カメラ・GPU・実保存は使わない。

起動後の安定・crash/ANRゲートと試験前後のinstance照合を行う。ATDで発生したBluetooth native abortは
アプリ導入前の環境阻害として記録し、ログ消去や無条件再試行で合格にしない。既存AOSPでの代替確認とは分ける。
この試験は実機のthermal変化、保存速度、OSによる抑制を保証しない。

## `ANALYSIS_REDUCE` 一致診断・`NATIVE_DECODE` 区間別計測（`.pubdiag` 一時診断）

通常ビルドでは `BuildConfig.ANALYSIS_PARITY_DIAGNOSTIC`・`BuildConfig.DECODE_TIMING_DIAGNOSTIC`・
CMake `CINEMATIC_ENABLE_DECODE_TIMING` のすべてが既定無効（`false` / `OFF`）であり、
追加計測・追加 TSV 行・`libcinematic_dng.so` 内計測コードは一切含まれない。
`-PcinematicPublicationDiagnostic=true` を付けた計測版 release ビルド（`dev.cinematic.p0.pubdiag`）
に限り両診断を有効化し、1 回の実機計測で次を同時に確認する。

- ビルド（`CINEMATIC_SIGNING_MODE` 等の署名用環境変数を解除した状態、debug 署名使用）:
  ```bash
  ./gradlew :app:assembleRelease -PcinematicPublicationDiagnostic=true
  ```
- 並置インストール（本人の実機操作承認後、`dev.cinematic.p0` を上書きせず並置し、ランチャーから `Cinematic Camera Probe (PubDiag)` を起動）:
  ```bash
  adb -s <authorized-device> install -r app/build/outputs/apk/release/app-release.apk
  ```
  ※ `dev.cinematic.p0.pubdiag` では通常の新規 GPU 保存を計測対象とし、コールド復元・明示復旧の試験には使わない。
- 抽出:
  ```bash
  adb -s <authorized-device> exec-out content read --uri content://dev.cinematic.p0.pubdiag.save-timing/records > records-pubdiag.tsv
  ```

### `ANALYSIS_REDUCE_PARITY`（新旧スカラービット一致診断）

- `BuildConfig.ANALYSIS_PARITY_DIAGNOSTIC && saveTiming != null` のときのみ、`SOURCE_VERIFY_AFTER` 完了後・`RESOURCE_CLOSE` 直前（原寸 `Bitmap` 解放後かつ `source-linear.rgb16` 削除前）に実行する。
- `ANALYSIS_REDUCE`・`CONTROL_PREPARE`・`GPU_RENDER`・`JPEG_ENCODE` の計測区間の外で独立のローカル `OriginalRasterFile` を開き、旧経路（`OriginalFields.reduce().reducedRgb()` → `R7PreviewInputs.prepare()`）と新経路の `externalBaseEv.toRawBits()`・`centralP97.toRawBits()`・`centralSampleCount` が全一致するか検証する。例外・取消は `timingBestEffort` で隔離し保存結果へ影響させない。
- 出力は schema 1（8 列・匿名性厳守、EV 値やファイル名は記録しない）。`finish()` の自動 `NOT_REACHED` 対象には含めない:
  - `COMPLETED` / 旧経路経過 ns（時計取得不能時は `NA`）: 3 項目すべてビット一致
  - `FAILED` / 旧経路経過 ns（時計取得不能時は `NA`）: 1 ビットでも不一致（即停止・原因調査対象）
  - `INCOMPLETE` / 中断まで ns または `NA`: 旧経路実行中の取消・I/O 例外
  - `SKIPPED` / `NA`: 到達時点で既に停止要求済み
  - `UNAVAILABLE` / `NA`: 診断前提不成立等（0 を捏造しない）
- **重要（`ATTEMPT` 時間の扱い）**: `ANALYSIS_REDUCE_PARITY` は `Original24Attempt.render()` 内（`ATTEMPT` 外枠の内側）で走るため、`.pubdiag` の `ATTEMPT` には旧経路分の時間（`ANALYSIS_REDUCE_PARITY` の `nanos`）が上乗せされる。`.pubdiag` の `ATTEMPT` を通常版の `ATTEMPT` と直接比較せず、中央領域最適化の効果確認は `ANALYSIS_REDUCE`（および `CONTROL_PREPARE`）単体値で行い、`ATTEMPT - ANALYSIS_REDUCE_PARITY` は参考値として区別しつつ、集計時に `ANALYSIS_REDUCE` の短縮量との整合確認に用いる。

### `DECODE_*` 10 区間（`NATIVE_DECODE` 内訳・LibRaw 0.22.3 無改変）

LibRaw 0.22.3 は無改変のまま、既存の `progress` コールバックと `clock_gettime(CLOCK_MONOTONIC)` により `decode24`（通常保存）の 10 区間を分離計測し、`saveTiming?.pixels(width, height)` 実行直後に schema 1 の 10 行として記録する（`finish()` の自動 `NOT_REACHED` 対象外）。未到達・欠損・負の差分は `UNAVAILABLE` / `NA` とし、0 を捏造しない。

| stage | JSON キー（`timingNs.*`） | 計測区間 |
|---|---|---|
| `DECODE_PREAD` | `pread` | `fstat` / `encoded` 確保直前〜`pread` ループ完了 |
| `DECODE_OPEN_PARSE` | `openParse` | `pread` 完了〜`raw.unpack()` 直前（`open_buffer` / TIFF 検証 / WB・params 設定） |
| `DECODE_UNPACK` | `unpack` | `raw.unpack()` 直前〜`raw.dcraw_process()` 直前（展開・`applyCrop`・WB 検証） |
| `DECODE_RAW2IMAGE` | `raw2image` | `raw.dcraw_process()` 直前〜`progress(SCALE_COLORS, 0)` |
| `DECODE_SCALE_COLORS` | `scaleColors` | `progress(SCALE_COLORS, 0)`〜`progress(SCALE_COLORS, 1)` |
| `DECODE_PRE_INTERP` | `preInterp` | `progress(PRE_INTERPOLATE, 0)`〜`progress(PRE_INTERPOLATE, 1)` |
| `DECODE_AHD` | `ahd` | `progress(PRE_INTERPOLATE, 1)`〜`progress(CONVERT_RGB, 0)` |
| `DECODE_CONVERT_RGB` | `convertRgb` | `progress(CONVERT_RGB, 0)`〜`progress(CONVERT_RGB, 1)` |
| `DECODE_MAKE_IMAGE` | `makeImage` | `dcraw_make_mem_image()` 直前〜直後（回転コピー差を含む） |
| `DECODE_WRITE` | `write` | `raw.recycle()` 直前〜`OriginalRasterSink::write()` 完了・`image.reset()` 直後 |

### 保持回数と集計条件

- schema 1・2 および上限（1,024 行・256 KiB）を維持する。独立 `.pubdiag` 初期状態（N = 0）で通常保存 55 行を基準にすると、`DECODE_*` 10 行 + `ANALYSIS_REDUCE_PARITY` 1 行の計 66 行/保存で完全保持 15 試行（990 行）、`PUB_*` 8 行と合流した場合は計 74 行/保存で完全保持 13 試行（962 行）を 1 回の抽出で回収できる。
- 集計対象は同一 `attempt` で `SAVE`・`ATTEMPT/PUBLISHED`・`NATIVE_DECODE/COMPLETED`・`ANALYSIS_REDUCE/COMPLETED`・`CONTROL_PREPARE/COMPLETED`・`GPU_RENDER/COMPLETED`・`DECODE_*` 全 10 キー `COMPLETED`・`ANALYSIS_REDUCE_PARITY/COMPLETED` が揃い、通常温度帯（`thermal ∈ {0, 1}`）にある試行とする。12MP・24MP の横向き・縦向きを含め、全試行で `ANALYSIS_REDUCE_PARITY == COMPLETED`（`FAILED` 0 件）であること、および残差 `NATIVE_DECODE - Σ DECODE_*` を確認する。
- 効果の評価では、`GPU_RENDER` と `B1`〜`FINAL` の `*_INPUT_READ_CONVERT` / `*_INPUT_WAIT`（同一画素数・通常温度帯の `COMPLETED` 区間どうし。省略された `SKIPPED` 帯は 0ms 扱いせず除外）を変更前と比較し、悪化していないことを確認する（旧経路の全量読込みが後段のページキャッシュを温めていた可能性への備え）。
- 併せて、`ATTEMPT` から `ANALYSIS_REDUCE_PARITY` を除いた値（変更前の通常版は `ATTEMPT` のまま）の短縮量が、`ANALYSIS_REDUCE` の短縮量と整合することを確認する。
