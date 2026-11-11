# Gate 1 第2部の実機手順（準備中）

> **後継手順の準備案**: §1〜§8は #326 でマージした記録として保持する。後続判断の「GPU判定・CPU参考」「GPUプログラム入力主・タッチ補助」は§9を参照する。未確定欄を解消し #285へ最終条件を追記するまで、準備完了・実機実施許可とは扱わない。

正本: [#285](https://github.com/reitojike/cinematic-camera/issues/285)、基準 [#276 §2.2](https://github.com/reitojike/cinematic-camera/issues/276)。
本書は第1部後のユーザー判断を受けた第2部専用手順。旧第1部の条件・結果を遡って変更せず、
旧 #270 の操作・許可も再利用しない。**本書の準備・マージは APK の配布や実機操作の許可ではない。**

## 1. 実施版の選択と開始条件

クリティカルパスは **#319 のマージ → APK 固定と許可 → 第2部 → 結果整理 → ユーザーの Gate 1 判断**。
エンジン改善 [#323](https://github.com/reitojike/cinematic-camera/issues/323)（B）を待って第2部を遅らせない。
APK 固定時点で次のすべてが確認できれば B を含める。1つでも未完なら #319 マージ後の現エンジンを使う。

- 最終候補の Mesa と Android エミュレーターの一致試験が通り、改善前と出力が変わらない。
- 1024×768、768×1024、1024×576、886×886 の台帳 peak がすべて 128MiB 以下（後続のユーザー判断。下記§2）。
- 写真を閉じる・trimMemory・surface loss 後の台帳が 0。フレーム間の保持量も4形状で記録済み。
- CI と canonical review が通っている。B のマージ自体には別途、main 同期・独立レビュー・未解決0等の通常条件も必要。

2026-11-10 の準備状況: [第2部の登録](https://github.com/reitojike/cinematic-camera/issues/285#issuecomment-6772026000)で4条件を確認し、Bありを選択済み。#319・#328・#327 はmainへ取り込まれた。その後の #321・#325 も含むmainは `b3ac4e5beafe3085f23bee4213899ac39a819920`。これはソースの確認であり、最終GPU/試験APKの固定、実機の計測入口・操作・PSS境界の完成、配布/実施許可を意味しない。下表のBなしは事前登録した代替ケースの履歴として残す。

| APK 固定時の選択 | 第2部の項目 | ①の扱い |
| --- | --- | --- |
| B を含む | 同じ DNG の CPU/GPU 操作、DNG④、PSS参考、準備できた③、§3の合成連続走行 | §3の完了待ち込み時間を正式①へ対応させる |
| B を含まない | 同じ DNG の CPU/GPU 操作、DNG④、PSS参考、準備できた③ | GPU速度は参考のみ。Bマージ後に①だけ別途事前登録・配布/実機許可を得る |

保存並列化の出力同等性確認は §7 の別枠とし、準備/許可/実施の有無を明示する。
③や保存試験が未準備ならその項目を未実施とし、第2部を行ったことだけで全 Gate 1 合格にしない。
両ケースを先に #285 に登録し、APK 固定時に採用ケースと根拠を確定する。結果を見て選び直さない。

### APK 固定票（配布前に全欄を埋める）

- GPU: #319 マージ済み source commit/tree、B有無と該当head、APK SHA-256/bytes、variant/ABI、package/version、公開署名fingerprint、build/最適化条件。
- B採否にかかわらず、最終APKのプレビュー台帳上限128MiB・書き出し192MiBを確認する。B不採用なら予算だけの独立変更を通常CI/review後に取り込み、#319の検証を途中で止めず、旧96MiBコードのまま最終固定しない。
- CPU: `1ce63154bd886d50b7669ccfdcfd01a8e192f83e`、tree `1b9ca54e1309ae350cc6f2d10c220037abcc3ac0`。実APKの固定値は下表。改善前の値や後続 main を対照にしない。
- 試験 APK を使う場合: source/tree・差分・APK SHA-256・target/runner・全method/引数・署名一致。未公開の試験sourceを公開commitリンクで表さない。
- 第1部本体 `7a5eac72a81051f9a9afa7f211e4d21e479c1518` との差分、および CPU 基準との差分を記録する。共有観測 metadata の追加と、CPU の既存状態の意味・通知時期・100ms再試行が変わらない根拠を最終sourceで照合する。ソースの機能上の根拠を性能同一の証明にしない。
- B の未マージheadを使う場合も全識別を固定し、後のマージcommitとの差分を記録する。
- 診断設定: `GpuSurfaceProbe` / `DngInputProbe` の調査用 DEBUG は無効。速度走行に画像読戻し・PSS採取・目視画像の書出しを重ねない。
- 正式①のエンジンは `GpuFrameMode.FULL_RECOMPUTE` を明示する。`DefaultStageClearGpu(egl, ledger, GpuFrameMode.FULL_RECOMPUTE)` または同モードを渡す `ConnectedStageClearGpuPipeline` を写真単位で保持し、frameごとに作り直す旧試験と区別する。製品通常の `NORMAL` はR7/Glow結果再利用を維持するため①へ混ぜない。GPU表示診断はFULLであり、最終試験method/引数にもモードを記録する。
- GPU/CPUの署名・package・導入順を確認する。同じpackageの入替えが必要なら許可に含める。署名不一致や削除が必要な場合、既存データを勝手に消さない。

| CPU固定票（build済み、未配布・未導入） | 値 |
| --- | --- |
| APK SHA-256 / bytes | `8cfc16cf5c60e29a3c67618402a9913be7a24a7f5367e5bd101c362e8b4bf745` / 36,462,513 |
| variant / ABI | `gpuDiagnostic` / `arm64-v8a`, `x86_64` |
| package / version | `dev.cinematic.p0.gpudiagnostic` / code 10、name `0.0.10-p05` |
| 署名 | 既存 `ephemeral_debug`、公開証明書SHA-256 `0e6fc2b5e12b4bcf5142592992f3b4879b44ca849c6c26c14464003ca0a35a8c`、新規鍵なし |
| 条件 | release由来、debuggable/JNI debug/minify/shrink=false、profileable shell=true、EDITOR_PREVIEW_DIAGNOSTIC=true、native RelWithDebInfo（-O2 -g -DNDEBUG） |
| source / 入口 | tracked製品source差0。Hub「CPUプレビューの確認（Filesから選択）」→PhotoRollActivity。PreviewSerial DEBUGは直列化を変えるためOFF |

**未確定**: GPU/試験APKの最終source・実bytes/hash、DNG入力と実寸法の一致、正式①/DNG④の最終入口・試験名、CPU/GPUの同じ物理操作と値の推移、PSSの観測境界・回数、総所要枠。③は表示取得入口が未準備で、未実施。
固定票と手順書のマージが揃ったら、配布する APK・Pixel 9a・範囲・所要見込みをユーザーへ示し、改めて許可を求める。
不足を推定値で埋めず、許可前に配布・インストール・実機操作をしない。

## 2. 判定基準と記録の境界

基準は #285 範囲1に従う。①〜③は不変、④のみ下記のユーザー判断で変更する。

**第1部と資源再利用案のMesa結果を見た後のユーザー判断**として、プレビュー台帳上限を96MiBから128MiBへ上げる。
資源持越しを認めた①の方針に沿うテクスチャ再利用案でpeakが約96MiBとなり、旧上限に対する余裕がほとんど残らないことが理由。
ユーザーはPixel 9aのメモリ量に対して128MiBをプレビュー用として無理のない量と判断した。
`GpuMemoryLedger`のプレビュー上限も合わせるが、コードと4形状の検証結果は別途確認する。書き出し192MiBと①〜③は変えず、旧96MiB走行の基準/結果を書き換えない。

1. ① 1024px入力の毎フレーム全工程。強さの中央値は16.7ms以下（最低でも33ms以下）、色温度・明るさは33ms以下。初回準備を除き、p95も記録する。
2. ② 表示8bitの最大差2以下、99.9パーセンタイル差1以下。Orton境界対象は Kotlin の MIST 入力のいずれかのRGBが1.0から±1e-5以内の画素だけ。GPU値で選ばない。対象画素数×10000≤全画素数、超過は不合格。対象の全RGBを最大差からだけ除き、p99.9には含める。対象数（0含む）・位置・Kotlin/GPU入力/出力・表示差を各試験で記録する。Mistの0.005、分位点の1e-4、他工程の判定は不変。画像を公開せず、写真別数値を公開記録へ混ぜない。
3. ③ 同じルックに見えるかをユーザーが目視する。自動一致や担当者の判断で代替しない。
4. ④ `GpuMemoryLedger` の全工程peakが128MiB以下（134,217,728 bytes）。現在量だけのUI表示をpeakとしない。

PSS/managed/native は参考記録で合否に使わない。第1部で通過した②合成/④合成と、今回の未実施・欠測を分ける。
材料が揃った後の継続・中止はユーザーの判断であり、単一の試験完走を Gate 1 合格と呼ばない。

## 3. B を含む場合の正式①

**第1部の結果を見た後のユーザー判断**として、すべての画像（R7・Glow・Mist・Orton・Character・肌の保護・shoulder・表示）、場、および下記の写真解析を除く判定値・分位点を毎フレーム計算し直す。
前フレームの画像・場・フレーム内の判定結果は再利用しない。スライダー値に依存しないテクスチャ/FBOの確保、寸法/filter依存の係数表、program、EGL/contextは持ち越してよい。
**ユーザーによる写真解析の範囲の明確化**: 加工前の写真由来のp97・基準EVは、写真を開いたときに1回だけ計算して保持する。この写真解析は元の①の初回準備として測定から除き、第1部でも持ち越していた。毎frameの再解析と、そのためのCPU側の写真コピーの保持は行わない。
「判定値・分位点も使い回さない」は、スライダー値で変わるR7局所トーンの分位点、Mistの判定等を指す。これらと全画像・場は毎frame再計算する。先の入力時解析も毎frameへ移す案は採用しない。実装と計測器がこの範囲を満たす根拠を固定票へ付け、旧結果は書き換えず、旧値との差を資源再利用だけの効果としない。
資源再利用の効果は未測定。第1部の同期/追加pass/欠測を含む工程別値を、通常GPU全時間や確保コストの確定値にしない。

- 入力は `MIXED 1024×768, seed=20261110`。順序は強さ→色温度→明るさ。
- 各軸は慣らし60frame＋採取120frame。計540frameのうち360を採用する。EGL/シェーダー等の初回準備は除く。
- 強さは `[0.25, 0.5, 0.75, 1]` を反復。色温度は `[-60,-30,0,30,60,30,0,-30,-60,-30,0,30]` を反復。明るさは `[-1,-0.5,0,0.5,1,0.5,0,-0.5,-1,-0.5,0,0.5]` を反復。対象外は強さ0.5/色温度0/EV0に固定する。
- 16,666,667nsの描画開始周期を目標とし、超過したら次frameを直ちに開始する。60fps達成やGPUクロック上昇を仮定しない。
- **判定列は CPU 時計による描画開始からGPU完了までの wall**。通常描画と完了同期を含む。旧連続試験と同じ境界で `glEndQuery + glFinish` まで、query回収/swap/記録は外。最終実装で区間を確認し、必要な処理を計時外へ移さない。
- CPU提出、GPU timer、実描画開始間隔は別の参考列。GPU timer欠測を0で補完しない。開始前disjointは履歴clear、終了後flagで区間を判定し、before/after/available/欠測理由を残す。timerが欠測でも正式wallが完全なら、結果後に別列へ変更しない。
- 通常処理に必要なhistogram/quantile/Mist scalarの読戻しを含める。診断画像のreadback/CPU見本/PSS/tap追加pass/フレーム間artifact出力を混ぜない。
- 軸ごとに中央値・nearest-rank p95・最大・n/欠測、warmup除外数、実開始間隔/超過数を残す。ImageReader等の出力なら実画面への提示FPSとは呼ばない。
- 同条件のDNG連続走行を追加する場合も、範囲と所要を許可前に固定し、参考列へ分離する。合成の正式①へ混ぜない。

## 4. DNG と CPU の比較

公開素材または合成DNGだけを使う。写真名・写真ごとの数値・個人用パスを記録しない。
入力のbytes・向き・provider・recipeと、実際のnative→AS_SHOT寸法/bytesの一致を準備時に照合し、公開は素材種別/寸法等の匿名集計にする。
CPU基準は長辺1024制限、GPUは長辺1024かつ786432画素の入口上限がある。正方形では寸法が揃わないため、4:3または16:9を候補にして**実寸法を確認してから固定する**。
786432は入力資源の上限であり、新しい画質/速度の合否閾値ではない。例の寸法や未検証のDNGを実測入力にしない。

- CPUの通常保持/再利用、プレビュー候補2並列OFF、追加直列受付OFFは基準版のまま。第2部のCPU計測前に #292 等のCPU高速化を入れない。
- 同じDNG・同じ開始/終了値・方向・800msの操作で、各軸慣らし1回＋測定4回を初期案とする。CPU/GPU各15調整。実現できる操作端点、経路順、総回数を計測前に #285 へ確定し、結果後に延長しない。
- **同じ物理操作は未確定**。CPUは相対ルーラー、GPUは絶対Sliderであり、同じ座標への800ms swipeだけでは同じ値の推移にならない。初期強度もCPU0.5/GPU1.0を揃え、開始/終了値・方向・時間に加えて途中の値の対応を検証してから固定する。値を直接指定するハーネスの成功を、このUI操作の準備完了と扱わない。
- CPU/GPUの導入切替・計測境界・熱を揃えた実施順を固定する。異なる日のCPU値や改善前の数字を代入しない。
- DRAFT256とEXACT1024を分け、初回OPEN・慣らし・値戻し・写真切替・cold/warmを通常調整と分ける。
- CPU `PreviewTiming` 要求側、`RawPreviewTiming` 処理側、GPU完了時間/受付間隔の区間を分ける。互いに内包する時間を足さず、境界の違う値から高速化率を作らない。
- 各操作/軸/kindの中央値・p95・最大・nと取消/失敗/欠測を記録。操作ごとの中央値も併記し、多くrenderした操作だけを重くしない。少数標本のp95は参考であり母集団保証ではない。
- DNG④は初回・調整中・終了時の台帳current/peak/保持量を取得し、閉じた後0を確認する。peakを観測できない入口では④を未測定とし、現在量から推定しない。

## 5. 導入から終了まで

下表の時間は準備用の計画値で、実施許可の所要枠ではない。最終APK/入口/入力数を確認後、
冷却・導入切替・③・保存の追加を含む総見込みを #285 に固定して提示する。

| 順番 | 操作 | 目安/成立条件 |
| --- | --- | --- |
| 導入 | 許可対象・APK hash/署名・端末/OSを照合し、awake/unlockedと温度/充電状態を確認 | 5〜10分。署名不一致や予期しない削除は止める |
| 合成 | B有なら§3を1走行。B無ならその正式①を行わない | 準備/回収を含め5〜10分、冷却別。勝手な再試行なし |
| DNG | 同じ入力でCPU/GPU操作と④。初回準備と通常調整を分離 | CPUは入力1種類5〜8分、GPU/切替は最終入口で見積もる |
| PSS | 速度とは別走行で§6の観測 | 最終の入力/経路/観測点数を固定して見積もる |
| 比較表示 | 道具が完成していれば§7の③ | 5〜10分の計画枠。ユーザー確認時間は別 |
| 保存 | 準備できた同等性試験、可能ならCPU書出しメモリ参考 | 独立範囲/試験名/回数/枠を固定して許可に含める |
| 終了 | owner/worker、台帳0、異常有無、成果物整合、試験APK削除を確認 | 3〜5分。アプリ本体/原本を無断削除しない |

- 各主要ブロックの開始/終了にOS thermal status、電池温度、充電status/plugged、画面状態を記録する。開始はNONE(0)/LIGHT(1)、比較組は同じstatus/充電条件とする。
- 項目間は最低2分冷却し、30秒間隔2sampleの安定を確認する。電池温度は当日その走行の開始基準±1℃を目安とし、基準を後付けで動かさず実測差を残す。追加5分で安定しなければ残りを延期する。過去の33.4℃等を新走行へ流用しない。
- **計測中はアプリを離れない。`onPause`で表示が閉じるため、離れたらその回を無効として記録し、取り直す。** 無効回と再取得を分離し、取り直しが許可枠を超える場合は相談する。
- Activityを用いないinstrumentationは余分な診断Activityを並行起動しない。実行中に別アプリ操作・別GPU負荷・画面ロック/消灯を加えず、画面条件の変化を記録して無条件に採用しない。新しいOS設定変更は行わない。
- thermal statusの開始時からの上昇、充電条件の変化、OS警告/異常発熱、OOM/ANR/crash、原本/品質不一致、owner/worker未回収、busy/HOLD、必須能力不足やcontext loss反復では停止する。部分結果を保全し、条件拡張/再試行を自動追加しない。
- 必須wall/台帳/環境記録が欠けたら当該項目は未判定。明示的なdisjoint等でGPU参考値がnullでも理由等の必要記録が揃う場合は、欠測として扱い、0埋めや速度合格への読み替えをしない。
- 端末/回収先の成果物SHAを照合し、試験APKを使った場合は終了時に削除・package/instrumentation不在を確認する。私有画像・比較ページ・生ログをGit/Issue/PRへ置かない。

## 6. PSS と CPU 書出しメモリ（参考）

PSSは速度と別走行にする。第2部にBを含める場合は、PSS参考走行もBを含む同じAPK/設定で測る。
Gate 2でプレビューと書き出しの同時動作を判断する材料とし、台帳とPSSを別列に記録して足し合わせない。
確認済みpackageのPIDへ `adb shell dumpsys meminfo -d <PID>` を各点1回取得し、
開始/終了時刻と採取費用、OS/API/variant、CPU見本・GPU readback保持の有無を残す。
複数プロセスは個別に採取し、近接時刻の合計と採取時間差を記録する。

- 詳細表のTOTAL/Pss Total、Dalvik Heap/Pss Total、Native Heap/Pss Totalを別列にし、managed/nativeのHeap Allocも別列にする。KiBを保存しMiB=KiB÷1024。App Summaryの別集計を代入せず欠測はnull。
- 観測点は同一プロセスのHubで入力前、最初の全工程完了後、各スライダー操作ブロック後、写真資源を閉じてHubへ戻った後。GPUはrenderer.close/台帳0、CPUも対象資源解放を確認する。
- 処理中/別PID/状態不明の値を正常系列へ混ぜない。到達・待機できない観測点は未対応。強制GC・heap dump・killで条件を揃えない。
- スナップショット/観測最大であり真の瞬間peakではない。GPU台帳peakと同時点とも限らない。PSSと台帳、managed/nativeの別時点最大を足さない。GPUメモリがPSSに完全に含まれる保証もない。
- CPU書出しも測るなら、同じ公開/合成DNGで既存の取得可能な開始/終了PSS・managed/native観測を別枠で固定する。未実装のsamplerや新予算を追加せず、観測間隔/欠測/診断負荷と、真peakを捉えない限界を明記する。
- Gate 2の判断材料であり、今回から12MP/24MPピークやGate 2合格を確定しない。

**PSS境界は未確定**。CPU/GPUそれぞれで観測対象PID、入力前・初回完了・操作後・解放後へ到達/待機する方法、回数と別走行の枠を固定するまで準備完了としない。instrumentation側の台帳0を別PIDのUI本体の解放証拠にせず、Hubへ戻ったことだけで台帳0やCPU資源解放を推定しない。

参照: [Android dumpsys meminfo](https://developer.android.com/tools/dumpsys#meminfo)、[Debug.MemoryInfo](https://developer.android.com/reference/android/os/Debug.MemoryInfo)。

## 7. ③と保存出力の同等性

③は実機のGPU表示をアプリ非公開領域へ保存し、許可されたadb回収で取り出す。同一入力/設定/解像度のCPU表示とタップ切替する私有ページを使う。
実Surface画像と、再render/試験用のoffscreen画像を混同しない。`gpuDiagnostic`はnon-debuggableなので、`run-as`可否を仮定せず、表示取得/搬出入口が検証されるまで未準備とする。
比較のためにdebuggableへ変更しない。ページと画像はGit・Issue・PRへ入れず、ユーザーの同じルックかの回答と匿名集計だけを記録する。
**現時点で③は未実施。** 表示画像の取得入口が今夜に間に合わなければ、第2部を待たせず③を第2部後へ回し、当日の事前登録にも「③は未実施」と記録する。比較ページだけの完成を取得入口の完成や目視合格にしない。後日の実施には対象APK・範囲を固定し、必要な配布/実機許可を改めて得る。

保存は #285 の「保存3工程の並列化前後で出力が同じことの確認」を維持する。
同source/入力/recipe/JPEG条件でGlow/Mist/R7 toneだけ1/1/1対2/2/2、Foundation2/Orton4/Character2・色融合OFFを固定し、
JPEG全byteのSHA/長さ/向き、保存・公開順序、journal遷移・確定記録・原本/recipe対応、再試行で重複公開や確定済み書換えがないことを確認する。
実行ごとに変わるID/URI/時刻を同値比較せず対応と順序を照合する。旧 #309 の部分試験をこの完了証拠にしない。
実行commit/test/条件/pass/fail/skip/未確認を残し、serial対照を改善前の速度値として使わない。

合成エミュレーターでの受入は [#325](https://github.com/reitojike/cinematic-camera/pull/325) で完了し、main `b3ac4e5beafe3085f23bee4213899ac39a819920` へマージ済み。最終head `50fe792be7c4a5bd1b03213a5e4ccde30256b633` の既存API37 Google APIsエミュレーターへの直接instrumentationで1 test・内部2条件AB/BAが成功し、全JPEG bytes、公開順序、journal全7状態、再要求2回、原本不変と資源解放を確認した。GMD/ATD実行や製品factory/FGS/UI配線・実機・実写真・速度の合格とは区別する。詳細な再実行手順は [RAW_DIAGNOSTIC_BUILD](RAW_DIAGNOSTIC_BUILD.md) とPRに集約し、同じ受入を未完として重複実施しない。追加の実機保存確認を行う場合だけ、別の範囲と許可を固定する。

## 8. 結果整理

採用ケース/実APK/CPU基準照合、正式①と参考列、②の範囲、③のユーザー判断、④peak/解放、PSS、保存、欠測/延期を分ける。
公開は匿名集計のみ。写真名・写真別数値・個人用パス・端末固有識別子・生の会話/ログ・画像を含めない。
第1部と第2部を合わせた未確認点を示し、全 Gate 1 の継続・中止をユーザーへ確認する。

## 9. 後継手順：GPU判定・CPU外部参考

本節は #326 マージ後のユーザー判断を反映する追補である。[既存の第2部登録](https://github.com/reitojike/cinematic-camera/issues/285#issuecomment-6772026000)と§1〜§8の記録を遡って変更しない。GPUの計測入口・試験APKは下記の準備証拠と対応付けるが、CPU参考試験APK、実端末の換算値、採取の総上限・所要、新しい配布/実機許可は未完。後継PRのマージだけで実機計測を始めない。

### 9.1 判定範囲

- 判定はGPU、CPUは参考。CPUに描画値取得hookを追加せず、CPU参考欠測をGPU第2部の開始延期理由にしない。
- GPUの既存描画経路へのプログラム値入力を主とし、タッチは補助。§4の「同じ物理操作」の旧準備条件をこの役割分担へ置き換える。プログラム入力とCPU相対dialを同じ物理操作・同じ描画値列と呼ばない。
- **正式①は§3と既存登録のまま**。MIXED 1024×768、seed=20261110、photo-lived FULL_RECOMPUTE、各軸60慣らし＋120本番（540/360）、目標開始周期16,666,667ns。写真p97/基準EVはopen時1回の初回準備として除く。CPU時計の描画開始→glEndQuery/glFinish完了wall中央値で、強さ16.7ms以下（最低33ms以下）、色温度/EV33ms以下。値列、p95等、計時内外も変更しない。
- GPU DNGプログラム入力・タッチと正式①を別走行・別列にする。CPU gfxinfoからGPU完了wallとの速度比を作らない。
- ②、④の台帳128MiB、書出し192MiBは不変。PSSは参考。③とCPU書出しメモリは第2部後。#325の合成保存同等性受入は完了済みで重複実行しない。

### 9.2 固定本体・GPU用試験APK・CPU用試験APK

| 対象 | 固定値・状態 |
| --- | --- |
| GPU本体 source / tree | `ee682646034f08f5739c6dc20cfff268514bc780` / `c168ffbad35d62e7d9fd4a886b02ab7fe6eb865a` |
| GPU本体 APK SHA-256 / bytes | `cf79d84ea2e225d390516ff8f7bcfe3c2d6e93c775f5118a0b97008cfd4285ac` / 36,577,393 |
| CPU本体 source / tree | `1ce63154bd886d50b7669ccfdcfd01a8e192f83e` / `1b9ca54e1309ae350cc6f2d10c220037abcc3ac0` |
| CPU本体 APK SHA-256 / bytes | `8cfc16cf5c60e29a3c67618402a9913be7a24a7f5367e5bd101c362e8b4bf745` / 36,462,513 |
| 両本体 variant / ABI | `gpuDiagnostic` / `arm64-v8a`, `x86_64` |
| 両本体 package / version | `dev.cinematic.p0.gpudiagnostic` / code 10、name `0.0.10-p05` |
| 両本体 manifest条件 | debuggable=false、profileable shell=true |
| 本体/GPU試験の公開署名証明書SHA-256 | `0e6fc2b5e12b4bcf5142592992f3b4879b44ca849c6c26c14464003ca0a35a8c` |
| GPU用試験APK source / tree | `28857ffb3c77bb57c354fff44da7394540afa68c` / `bffddbcbf5cf155360ed661171dbe5053d7f5957` |
| GPU用試験APK SHA-256 / bytes | `03204b4117421ebb2561aab94cb13f8cdaec5d292d52b8b818461ee8884e8846` / 2,467,001 |
| GPU用試験APK build base | ee68264からのtest-only 6ファイル追加。後継PRの統合headからbuildしたAPKではない |
| CPU参考用source | `6e6d4016eb2de2dc125be368d0d2b74c2ff37f78` のCPU guard-only変更を、28857ffを含む後継branchへ通常merge |
| CPU参考用試験APK | 後継branchの確定build head/tree・SHA-256・bytes・署名・metadataを別票へ記録する。現時点では未build・未固定。GPU用03204bをこの識別へ流用しない |

試験packageは `dev.cinematic.p0.gpudiagnostic.test`、targetは `dev.cinematic.p0.gpudiagnostic`、runnerは `androidx.test.runner.AndroidJUnitRunner`。GPU/CPU用試験APKは同じpackageの別artifactなので、どちらを導入したかを実bytesで照合する。CPU用の正確なmanifest/署名照合はbuild後に行う。

GPU本体は受入head `5b143d6414b1394c278e69a96a2c97e29d3f239a` と上表sourceのtree一致を根拠に、既存bytesを使う。CPU本体も固定bytesを維持する。標準checkが新たに本体出力を生成しても、配布/測定候補へ置き換えない。

統合base main `148a41220608f91b64f5008a8533d714267ccfb3` には、固定GPU source以後のMesa opt-in Gradle/LWJGL host-test設定、保存同等性AndroidTest、文書等がある。通常の製品source/依存は不変でも、test APKのbuild source全体は同一ではない。公開PR head、GPU APKをbuildした28857ff、CPU APKをbuildする後継headを別々に記録する。未公開時点のsourceへ公開commitリンクを付けない。最終headのCI/reviewと各APKの実行証拠を混同しない。

### 9.3 準備済みの入口と引数

次のclass名はすべて `dev.cinematic.p0.gpu.` を前置する。各runで `class=<完全修飾class#method>` を1件だけ指定し、`isolated=true` と対象APKのinstalled hashを確認する。GPUの物理入口には別途 `gate1PixelProbe=true` とMODEL `Pixel 9a` が必要。これらのflagは許可ではなくguardであり、実機配布/操作には新しい明示許可が必要。

| 項目 | class#method | gate1Part2 | 回数・用途 |
| --- | --- | --- | --- |
| 正式① | `GpuGate1Part2TimingAndroidTest#measureContinuousFullRecomputeWithResourceReuse` | `continuous-full-v2` | 1走行、540frame/採用360。28857ffで最終入口のAVD再確認済み |
| 共通入力準備 | `GpuGate1Part2CommonInputTest#prepareSyntheticCommonInputAndVerifyCpuGpuIdentity` | `common-input-v1` | emulator-onlyの生成/同一性確認。既存fixtureを再生成しない。最終sourceのblob不変とfixture hashを照合済み |
| DNG④ | `GpuGate1Part2DngLedgerTest#measureSelectedDngLedgerAndRelease` | `dng-ledger-v1` | 1走行、初期合成を閉じた空状態でpeakを1回reset後、DNGの5組離散paramsと解放を測る。速度/gestureではない |
| DNG主入力 | `GpuGate1GestureObserverProbeTest#observeSelectedDngProgrammaticValues` | `dng-programmatic-values-v1` | strength→warmth→EV、各軸慣らし1＋本番4、計15操作。正式①と別 |
| GPU PSS | `GpuGate1GestureObserverProbeTest#observeSelectedDngPssReference` | `dng-programmatic-pss-v1` | 同じ15操作を速度と別run。Hub1＋初回1＋各操作後15＋close1＝18予定点 |
| MIXED主入力の機能probe | `GpuGate1GestureObserverProbeTest#observeProgrammaticRenderValues` | `programmatic-observer-probe-v1` | emulator-only、各軸1操作の準備証拠。Pixelの追加走行にしない |
| 補助touch v4 | `GpuGate1GestureObserverProbeTest#observeRealEngineAndDeliveredTouch` | `gesture-observer-probe-v4` | emulator-only、各軸1操作の準備証拠。現sourceはPixel補助タッチ入口ではない |

DNG主入力/PSSにはさらに `gate1ProbeRun=<未使用ID>`（英数字/underscore/hyphen、1〜40文字）、`gate1AuthorizedTarget=Pixel 9a`、`gate1TargetApkSha256=cf79d84ea2e225d390516ff8f7bcfe3c2d6e93c775f5118a0b97008cfd4285ac`、`gate1DngDocumentId=primary:Download/gate1-common-synthetic-1024x768-v1.dng` を指定する。AVDではtargetをその実MODELへ合わせた保存argsで検証済みであり、AVD target/serialを実機へ流用しない。sourceDir実bytes、選択文書hash、run IDの非再使用をコードでも確認する。MIXED機能probeも未使用の `gate1ProbeRun` を要するが、実機では実行しない。

DNG④には `gate1DngDocumentId` に同じ文書ID、`gate1DngSha256=796555f835a38a94162383dc9f037023a1fd6400037d7b5f3f0e287ccd64bf3a`、`gate1DngBytes=1573250`、`gate1DngWidth=1024`、`gate1DngHeight=768` を指定する。固定出力名を持つ正式①/DNG④は、既存出力の退避・hash照合と実行回の対応を先に記録し、旧証拠を上書きしない。DNG④のsourceは既検証入口から不変であり、28857ffで再実行した正式①/DNG主入力/PSSとは実行履歴を分ける。

合成DNGは非写真、orientation1、1,573,250 bytes、上記SHA。実Files選択後native/AS_SHOTは1024×768、RGB Float raw bits little-endian row-majorのSHAは `db20dc7a341bd1c9c60e1087896380c20c5b130248ef1d6eb51cce1221882ae3`。原本hashを走行前後で照合する。共通入力の準備試験は候補側CPU入力とGPU入力の一致であり、固定CPU 1ce6315の描画ごとの値を証明しない。

### 9.4 主軌跡・端点・入力時間

主入力はtest-onlyで `GpuPreviewRenderer.submit(params, display)` へ直接与え、タッチと同じsubmit以降のmailbox→FULL_RECOMPUTE→swap/callbackを通る。上流Slider/Compose state更新は迂回し、**UI Slider表示値を提出値へ同期しない**。開始/到達/帰着は実render値で判定する。固定本体の [Slider→state](https://github.com/reitojike/cinematic-camera/blob/ee682646034f08f5739c6dc20cfff268514bc780/app/src/gpuDiagnostic/java/dev/cinematic/p0/gpu/GpuPreviewDiagnosticActivity.kt#L412-L414)、[LaunchedEffect→submit](https://github.com/reitojike/cinematic-camera/blob/ee682646034f08f5739c6dc20cfff268514bc780/app/src/gpuDiagnostic/java/dev/cinematic/p0/gpu/GpuPreviewDiagnosticActivity.kt#L378-L383)、[renderer共通経路](https://github.com/reitojike/cinematic-camera/blob/ee682646034f08f5739c6dc20cfff268514bc780/app/src/main/java/dev/cinematic/p0/gpu/preview/GpuPreviewRenderer.kt#L147-L152) を根拠とする。通常タッチ全体の再現や観測負荷ゼロとは主張しない。

| 軸 | 開始→到達→帰着 | 上昇/下降の値速度 |
| --- | --- | --- |
| strength | 0.50→0.56→0.50 | +0.30 /s、−0.30 /s |
| warmth | 0→6→0 | +30 /s、−30 /s |
| exposureEv | 0→0.6→0 | +3 EV/s、−3 EV/s |

200ms等速増＋400ms保持＋200ms等速減、予定800ms。他軸は0.50/0/0。t=0,20,...,800msの固定41点を各1回Mainからsubmitし、省略・実経過時間からの値列再計算をしない。latest-winsなので全点描画は要求せず、予定/実submit時刻・返却時刻・全params・実render時刻/値・callback・欠落を分ける。予定点数を描画数や表示FPSと呼ばない。

入力時間の規則は初回実submitをt0とし、全点のuptime遅れ0〜20ms、最終span800〜820ms、隣接submitの同一uptimeミリ秒への集中なし。20msは入力周期1点分の操作成立条件であり、正式①の描画速度閾値ではない。整数ms uptimeとnanoTimeを別記する。

開始は試行直前のdrain済み実状態、到達は当該試行の**全実render値の最大**、帰着は最終submit以後の完了render/callbackとmailbox状態の一致。都合のよい保持frameを選ばない。端点・他軸/表示固定、入力時間、observer対応、cleanupを別判定し、失敗は記録して自動調整/再試行しない。28857ffのAVDではDNG15操作と別PSS18点が成立し、正式①入口540frame・欠測0・close0を確認済みだが、Pixelの性能/合否ではない。

### 9.5 許容差と実端末の確認順序

CPU dial/touchとの比較に使う共通操作許容差を `epsilon_operation=max(CPU量子化半幅, CPUの1px相当, GPU touchの1px相当)` とする。GPU1pxはprogrammatic経路自体の最小刻みではない。別の数値丸め余裕 `epsilon_round=8 Float ULP(target peak)` は1px由来ではなく、最終許容差は両者のmax。各成分を別欄に残す。

CPU量子化半幅はstrength0.005、warmth/EV0。CPU1pxはtapStep/(24×density)、tapStepは順に0.01/1/0.1。GPU1pxはspan/track、spanは1/200/4。Material3実trackはaccessibility幅−左右各round(10dp×density)−active thumb round(2dp×density)。丸め方・単位・対象経路を記録する。

**式固定→新しい許可→準備UIで実端末density/bounds/trackを確認→具体値を算出して事前記録→性能計測**の順とする。Pixel geometryは未検証で、AVD991pxを流用しない。結果の成功率/実到達値から許容差を広げない。実機計測開始後の手順・目標値・許容差変更にはユーザー承認が必要。

旧GPU v3の幾何誤りとstrength有効/warmth・EV無効は保持する。補助touch v4のEV端点無効も新許容差で再判定しない。入力予測によるovershootとは整合するが原因確定とはしない。タッチ時間はnot_assessedで、主入力の成立判定とは別である。

### 9.6 CPU参考入口と未取得範囲

`dev.cinematic.p0.gpu.CpuGate1ReferenceUiTest#axisHoldReferenceOnly` はpublic UI-only、1run1軸42events＋入力窓外gfxinfo。製品型依存/reflection/描画hookはない。引数は `cpuReferenceUi=true`、`isolated=true`、`cpuAxis=strength|warmth|EV`、`verifiedCpuApkSha256=8cfc16cf5c60e29a3c67618402a9913be7a24a7f5367e5bd101c362e8b4bf745`、`cpuRunId=<未使用ID>`（先頭英数字、後続英数字/underscore/hyphen、計1〜80文字）。物理入口のみ追加で `gate1Part2=cpu-reference-ui-v1`、`gate1PixelProbe=true`、MODEL Pixel 9aを要求する。全runでpublic sourceDirの実bytes SHAを確認する。guard flagは実機許可を代替しない。

相対dialは左増、非zero進行率fで `x=x0−K(h+A×f)`、zeroでx0。K=24dpPx/tapStep、h=tapStep/3、Aは振幅。dead-zone、strength丸め、baseline snap、system gesture領域を含む実座標を記録する。800ms予定の42eventsはDOWN＋40MOVE＋UPであり、GPU41submitと区別する。

CPUのrangeInfo正規化Float逆換算はUI状態の復元値であり、内部Doubleそのもの/画像frame値ではない。stateDescription表示文字しか取れない場合の半幅はstrength0.005 / warmth0.5 / EV0.05で、その精度より細かな一致を主張しない。観測精度を許容差の後付け拡大に使わず、不足は未確認とする。

開始/帰着UI値・実操作時間・gfxinfo rawを記録する。**peak画面値はこのmethodでは取得しない**。描画ごとの値との対応もない。gfxinfoはWindow/UI frame情報で、現像engine時間やGPU完了wallではない。EOF/bytes保存は有効なframe採取の証明ではない。Flagsだけで採否を決めず、使うtimestampが正で、順序整合し、未完了sentinelでないこととheader/timeout/no-processを確認する。負のdurationや欠測を0補完しない。即時dumpは全release完了や写真frameとの対応を証明しない。

28857ffのstrength機能確認は1runのUI制御までで、guard追補後CPU APKのbuild・確認は別。各軸慣らし1＋本番4の15連続runとCPU PSS18点の自動runnerは未準備。CPU peak、他軸runtime、15連続完了、実機実施を証明済みにしない。旧CPU v3未build/未runの別案も実施済みにしない。

### 9.7 PSSの成立範囲と採取上限

PSSは速度と別run、原計画の**CPU18＋GPU18＝36予定枠**を保持する。各経路Hub入力前1、初回1、各15操作後、close/Hub復帰後1で、慣らし後も採る。各軸1回へ減らさない。GPU18点は28857ffのAVD機能試行・raw再集計まで準備済み。CPU18点は未測定・自動runner未準備であり、今回CPU18を未測定として進める案の最終確認が必要。36成功を保証せず、欠測をGPU値や別runで埋めない。実機PSSは両経路とも未測定である。

GPUは各点のscalar境界tokenを固定1s settle前、dump直前、直後に確認し、PID/package/UID/starttimeを照合する。`dumpsys -t 10 meminfo -d <verified PID>` を各点1回、8KiBずつ固有rawファイルへstreamして保存する。全文をheap保持せず、TOTAL/Dalvik/Native PSSとmanaged/native Heap Alloc、KiB、時刻/費用を別列へparseする。header/列幅/必要行/summary一致を検査し、未知layout・timeout・部分出力・状態変化は無効。自動再採取しない。

GPUのcloseは正常finish→DESTROYED→元Hub identity/resumed/focus復帰、terminated＋実GL thread終了、失敗flagなし、台帳bytes/textures/windows0を別証拠として扱う。native pending/ownerの解放は観測していない。close後PSSはtestのrenderer/callback参照を保持したobserver付き値であり、Activity GCや非観測アプリの完全解放を証明しない。DNG主/PSSのlifetime台帳peakは初期合成/setupも含み得るため、peakを空状態でresetする専用DNG④とは分ける。追加release/GC/killやclose後GL snapshotで条件を揃えない。

待機はsource上、通常GPU状態確認30秒、Files探索10秒、native DNG load60秒、closeのGL終了確認5秒等。CPUにはFiles90秒、編集準備/帰着120秒等の別待機がある。これらを足さずに1runの総上限と呼ばない。`dumpsys -t 10` はservice出力待機の指定であり、shell/Binder/file IO全体のhard deadlineではない。旧13分/経路案は実装された総watchdogではなく、採取総上限・失敗時の切り上げ・全第2部の所要は未確定。標準I/Oを強制中断する保証を捏造しない。

### 9.8 最終登録・新しい許可までの残件

- [ ] CPU用試験APKのbuild head/tree、SHA-256/bytes、manifest/署名、固定CPU本体との互換性を別票へ固定する。
- [ ] 後継最終headの標準check、診断test compile/assemble、必須CI、canonical review、独立OK、未解決0、main同期を確認する。
- [ ] 実端末geometry算出と数値記録の準備手順を、性能計測前の新しい許可範囲へ明示する。
- [ ] CPU peak未取得、15連続/PSS18 runner未準備を残し、CPU18未測定案と今回のCPU参考実施回数を最終確認する。
- [ ] 各runの採取総上限、導入/切替/入力配備/回収/冷却込みの総所要と許可枠を決める。未実装の総watchdogを完成扱いにしない。
- [ ] 最終条件を #285 へ新規追記し、APK・Pixel 9a・範囲・所要・同package切替/試験APK削除を示して新しい配布/実機許可を得る。

§5の最低2分冷却、30秒間隔2sampleの安定、開始NONE/LIGHT、追加5分で延期、異常時停止は維持する。旧第1部・AVDの許可をPixelへ流用せず、許可前に実機へ配布/導入/操作しない。生ログ・画像・端末固有識別子・個人用絶対パスは公開しない。

## 10. 任意補助試験の準備

本節は任意の補助試験であり、§1〜§9と既存登録の履歴を変更しない。**本番のGPU/CPU本体と主試験APKの4本は固定したまま**、補足試験APKを5本目の別artifactとして扱う。GPU主試験 `03204b4117421ebb2561aab94cb13f8cdaec5d292d52b8b818461ee8884e8846` とCPU参考試験 `a5ab18c18b38700ee92757cc72be6ca0c53de5539ed050503adacd1eb5f3bfe1` を補足版で置き換えない。補助試験の欠測・未採用はGPU第2部の開始条件に加えない。

### 10.1 補助入口と範囲

完全修飾classは下表の名前に `dev.cinematic.p0.gpu.` を前置し、`class=<完全修飾class#method>` を1件だけ指定する。両入口とも `isolated=true`、実APK bytesと共通DNGの照合、未使用run IDを要する。物理端末には追加で `gate1PixelProbe=true` とMODEL `Pixel 9a` を要求するが、flagは実施許可ではない。

| 任意項目 | class#method / gate1Part2 | 操作・境界 |
| --- | --- | --- |
| GPU補助タッチ | `GpuGate1GestureObserverProbeTest#observeSelectedDngAuxiliaryTouch` / `dng-auxiliary-touch-v1` | 共通DNGでstrength→warmth→EVを各1回、計3操作。各42events（DOWN＋40MOVE＋UP）。PSS・主入力・正式①と別run |
| CPU PSS参考 | `CpuGate1ReferenceUiTest#selectedDngPssReferenceOnly` / `cpu-reference-pss-v1` | 同じHubから1回だけFiles選択し、同じ写真で各軸慣らし1＋本番4、計15操作/630events。Hub1＋初回UI1＋各操作後15＋close後1＝18予定点 |

- GPUの追加引数は§9.3のDNG主入力と同じ `gate1ProbeRun`、`gate1AuthorizedTarget`、`gate1TargetApkSha256`、`gate1DngDocumentId`。固定GPU本体/共通DNGだけを対象とする。
- CPUは `cpuReferencePss=true`、`cpuRunId=<未使用ID>`（§9.6の形式）、`gate1AuthorizedTarget=<実MODEL>`、`verifiedCpuApkSha256=8cfc16cf5c60e29a3c67618402a9913be7a24a7f5367e5bd101c362e8b4bf745`、`gate1DngDocumentId=primary:Download/gate1-common-synthetic-1024x768-v1.dng` を指定する。既存 `axisHoldReferenceOnly` の1軸参考入口とは別で、`cpuAxis` を使って15操作を分割しない。
- GPU/CPUの目標値と200ms増＋400ms保持＋200ms減（予定800ms）は§9.4と同じ。GPUは実タッチ経路を通すが、実到達値・帰着・時刻・受付/不明を分ける。補正入力・許容差緩和・自動再試行を足さない。§9.5の式とPixel geometryの事前確認を維持し、AVD座標をPixelへ流用しない。
- GPUの `gate1AuxTouchFailAfterDown=true` はemulator-onlyの意図的失敗で、途中入力のCANCELとcleanupを確認するための別run。正常完走/性能合格には数えず、通常runには指定しない。

### 10.2 CPU PSSの意味と打切り

CPUはpublic UI、標準 `ActivityLifecycleMonitor`、`dumpsys meminfo` に限定した外部参考であり、製品型・reflection・描画hookを使わない。最初の編集UI、各操作後のUI復帰、通常Back後の同じHub/resumed/focusと対象ActivityのDESTROYEDを観測する。**native完了、全worker idle、Activity GC、完全解放の証明ではない。** 強制GC/release/killや新しいHubへの置換でclose条件を作らない。 CPUのDNG確認は期待URI/read-grant/hashとFiles選択位置の証拠であり、選択result URIやdecode後の画像一致は直接観測しない。 CPU PSSもtarget process内のtest instrumentation・収集中metadata等を含む値であり、無観測のCPU本体だけのPSSやGPUと同じ観測負荷を証明しない。

各点のPID/package/UID/starttimeとUI/lifecycle境界をsettle前・dump前後で照合し、`dumpsys -t 10 meminfo -d <verified PID>` を各点1回だけstream保存する。未知layout・欠落・timeout・状態変化は無効とし、再採取や0補完をしない。CPU peak描画値やCPU/GPU速度比は得られない。予定枠はCPU18＋GPU18＝36のままで、失敗/未着手点を別runの値で埋めない。

CPUの13分は経過時間によるsoft budget候補で、45秒をcleanup用に残して新しい観測を切り上げる。標準shell/Binder/file IOを強制中断するend-to-end hard deadlineではなく、全18点の成功や13分以内の終了を保証しない。旧60分の全体枠に収まるとも約束せず、実行結果を見て本番採否・追加所要を別途判断する。

### 10.3 補足artifactと採用判断

補足APKには独自のsource commit/tree、build baseと差分、実SHA-256/bytes、package/target/runner/version、公開署名fingerprint、variant/ABI、JDK/SDK/Gradle/nativeのbuild条件を私有の固定票へ記録する。既存4本との署名・互換性を実物で照合し、同じtest packageでも主GPU/CPU試験とは別識別にする。標準checkで生成した新しい本体APKは測定候補に選ばない。未公開sourceへ公開commitリンクを付けず、私有path・生ログ・写真を公開しない。

標準lint/unit/assembleと診断lint/unit/test compile・補足APK build、独立レビュー、許可されたAVD機能確認はそれぞれ別証拠とする。成功しても自動採用せず、失敗時は既存4本と本番登録を維持する。任意補助の採否、実施回数・総所要、同package切替/回収/試験APK削除を改めて固定し、新しいPixel配布・操作許可を得るまで実機へ導入しない。

### 10.4 GPU補助Files setupと失敗時の終了

GPU補助touchだけは、内部ストレージの `roots_list` 内のラベルを選び、drawer終了とtoolbarを確認してから `dir_list` 内の `Download` を選択する。Download toolbar到着後に同じfile listを先頭へ戻し、登録済みDNGの完全一致だけを選ぶ。探索・scrollはfile list内に限定し、別provider探索や内部ストレージ表示設定の変更へfallbackしない。各待機は10秒のpolling上限であり、UI automation全体のhard deadlineではない。旧programmatic/PSS経路とCPU既存1軸経路は変更しない。

`auxiliaryPicker` は到達phase、位置確認、cancel結果だけを残し、全filename一覧や画面画像を採取しない。setup失敗時は、このtestが開いて未完了のpickerと元Activityを確認し、標準Backを1回だけ試みる。元Activityの同一object・resumed・focus復帰を検証し、その後は既存のfinish/GL終了/同一Hub復帰検証を使う。drawerだけが閉じた場合を含め、復帰未確認はcleanup失敗として残し、繰り返しBack・kill・再起動で成功扱いにしない。元のsetup例外は保持する。observer attach前の失敗でも初期synthetic renderが0だったとは扱わない。

`gate1AuxTouchFailAfterDownload=true` は正確な補助touch class/modeとemulatorを入力前に検査し、Download到着後・DNG選択前に意図的に失敗させる別run専用hook。既存の `gate1AuxTouchFailAfterDown=true` とは併用不可。通常runでは省略し、意図的失敗は正常完走・本番計測に数えない。修正やhookの存在だけではFiles成功・cancel成功・実機検証済みを意味しない。

内部root選択だけは、既存CPU setupと同じ公開 `AccessibilityNodeInfo` APIでfreshなラベルとクリック可能ancestor、roots_list内・同active window・visible/enabled・ACTION_CLICK対応を確認し、`ACTION_CLICK` を1回だけ発行する。準備のread-only pollingとaction再試行は区別し、false・例外・結果不明時の再clickや座標fallbackは行わない。UiAutomator 2.3.0のcached bytecodeと失敗stackの照合では、旧root clickはgesture注入前のnode refresh失敗だったが、node置換の具体原因は未確定。先行drawer clickやcleanup Backの未実行を意味しない。取消はBack最大1回を維持し、呼出phase・返値・例外と元Activity復帰未確認を記録する。この限定補修後の `failAfterDownload` negativeは別runで1回だけ検証し、別失敗なら保存して停止する。後続のDNG row選択やtouch成功はこのnegativeだけでは証明しない。

### 10.5 2026-11-11: 補助sourceの限定改修と未実行範囲

以下は§10.4後の時系列追記であり、以前の失敗記録・手順・artifactを改変しない。新しい依頼による旧HOLDの解除は、#332の補助source修正と非公開診断の準備に限る。主4 APK、#285の登録、固定CPU `1ce63154bd886d50b7669ccfdcfd01a8e192f83e` と固定GPU `ee68264`、製品コード・依存・本番境界は変更しない。Pixelへの配布・導入・操作は不可のままである。

#### source統合の記録

共通baseは `f2a8ace6df578613e6764cc229f6e7852e89f37c`。次のローカルsource commitをCPU、GPUの順にcherry-pickした。未公開sourceを公開済みcommitリンクとして扱わず、build artifactの同一性とも区別する。

| 対象 | 元source commit | 元tree | 統合後commit |
| --- | --- | --- | --- |
| CPU診断 | `0c9283645aa140aa8daae3a8aaea1618f2d1c729` | `4eded4322207f7f01d291c626fbe6446aaf1578b` | `afde36be0de9d7ea9c266b89b63f347f663d16d2` |
| GPU補助picker | `2ccef577c27fca8f03f44f802ca0e7fe7c3b17d3` | `5593640c74a174eeb7cdcbd079c4d41e602c8e06` | `96a9e78675387b1d5ebf8777d77babab1e28f7a0` |

統合source headは `96a9e78675387b1d5ebf8777d77babab1e28f7a0`、treeは `6c4973faf4c94f47ca1a2005955f76364b06e863`。source変更は `app/src/gpuDiagnosticTest/java/dev/cinematic/p0/gpu/` 内の `CpuGate1PssSupport.kt`、`CpuGate1ReferenceUiTest.kt`、`Gate1DngObservationSupport.kt`、`GpuGate1GestureObserverProbeTest.kt` の4ファイルだけで、本追記は別の文書変更である。

#### CPU: 原Hubの事実確認を追加し、継続条件は変更しない

旧f2a8ace6 runはPSS18予定点のうち14点、13操作まで進み、15点目で原Hubのweak referenceがnullとなって停止した。旧raw・失敗・未着手点を保持し、再runの値で補完しない。この事実だけではHubの破棄、GC、ヒープ逼迫の有無・原因を確定できず、原因は `UNKNOWN` のままである。

原Hubはweak referenceで追跡し、標準lifecycle callbackによる遷移を最大64件、identity/task/stageと破棄を観測したelapsed時刻を記録する。破棄・同じtask内の別Hub・参照消失は区別して失敗理由に残す。strong reference保持、強制GC、`ActivityScenario`、Hubの再bindは追加しない。原Hubの同一instanceという条件は維持し、再生成したHubで続行する境界変更は未実施である。ヒープ逼迫により破棄されたことが証拠で確認できた場合も、境界を変更する前に利用者へ別途確認する。

各PSS試行の結果を確定した後に、Java heapのused/max/allocated/freeとlifecycle snapshotを記録する。heap値は非atomicなprocess推定であり、破棄原因の証明にはしない。ActivityManager / ActivityTaskManagerとkill/destroy eventのlogcatは、対象packageまたはHub名で限定し、system/events buffer、最大120秒・80件・64KiB、shell `timeout 2s` とする。欠落・切詰め・stderr混在・終了コード未確認を成功の根拠にせず、非公開rawのhost確認を要する。shell/Binder/file IO全体のhard deadlineを保証しない。

UI待ちtimeout時だけXMLとPNGを非公開run記録へ保存し、CPUは本失敗とcleanupを合わせて最大2組とする。採取失敗・部分保存を記録して元のPSS/UI失敗を上書きしない。lifecycle metadataの保持と各点後のheap/logcat診断は割当・IO・経過時間を増やし、後続PSSにも影響する。無観測CPUや旧runと同じ観測負荷とは扱わない。18予定点・15操作、通常Back、1秒settle、各点1回のdump、13分soft budget/cleanup45秒、既存CPU1軸入口は変更しない。

#### GPU: 登録DNGの可視性でDownload到着を判定する

§10.4の旧Download toolbar判定を、既知のDocumentsUI（`com.android.documentsui` / `com.google.android.documentsui`）の `dir_list` 内で登録DNGの完全一致名が一意・visible・enabledであることへ変更する。内部rootの確認からその `Download` 行を1回だけclickする経路は維持する。Download toolbar文字は独立した診断として残し、到着判定に用いない。未知directoryのscroll、provider fallback、再clickは追加しない。内部rootの既知一覧内での準備探索と、Download到着後のDNG選択を区別する。

補助経路のUI待機中に未成立となる条件は `check()` で即時終了せず `false` として扱う。UI待ちtimeoutごとにXML/PNGを非公開run記録へ採取し、採取失敗で元の例外を置き換えない。画面階層・画像には他のfilename等が含まれ得るため、§10.4の従来の通常記録とは別の非公開失敗証拠とする。Git・PR・公開artifactへ生XML/PNG/logcatを含めず、公開報告は必要な状態と件数だけに限定する。

登録DNGが見えたことはUI経路の証拠であり、実際に選択されたresult URIの直接観測とは別である。既存の期待URI・read grant・hash検証は維持する。主programmatic/PSS経路、入力値・gesture、Back最大1回と既存finish/GL終了/元Hub復帰のcleanup条件は変更しない。

#### 実行順・残るHOLD

本追記時点はsource prepareのみである。両ローカルsource commitと統合headのcompile、標準lint/unit/assemble、診断compile/build、AVD、最終headの必須CI/canonical review/独立レビューは未検証であり、合格・採用・配布可能を意味しない。この作業ではGradle・build・ADB・AVDを一切実行していない。source範囲・公開用identity・privacy差分・`git diff --check` の静的確認と、実行証拠を区別する。

③の準備を優先し、#332の実行枠を先取りしない。枠と対象sourceが確認されてから、通常GPU touchを先、意図的失敗のnegativeを後にする。承認範囲内の各再runは1回までとし、別の失敗が出たら証拠を保持して繰り返さず報告する。CPUの原因確認前の継続条件緩和、補助試験の本番採用、主4 APK/#285の置換、Pixel配布・操作のHOLDは解除しない。
### 10.6 2026-11-11: 入力到達と実描画の証拠を分ける

利用者の追加指示により、補助GPUタッチだけで終点sampleのWindow到達と、実際に描画された値を別々に判定する。旧313c7b8のcurrent MOVE完全一致条件での失敗を保持し、新しい走行で遡って合格へ変えない。既存記録はresamplingと整合するが、具体原因を確定したとは扱わない。主4 APK、主programmatic/PSS、既存emulator-only touch、#285の登録、製品・依存・Pixel操作のHOLDは変更しない。

- Android標準の[MotionEvent](https://developer.android.com/reference/android/view/MotionEvent)はMOVEをまとめ、途中sampleをhistoryとして配送できる。終点到達は、同じdownTimeのMOVEにあるcurrentとhistoryを調べ、sample時刻がdown+800、Xが注入終点から1px以内という従来条件を使う。一致sampleがちょうど1件でなければboundary_unprovenとする。current/historyの区別、含んでいたpacketの時刻・位置・receivedNs・dispatch返却、最新座標とhistory座標を記録する。Yは記録するが新しい一致条件にはしない。
- historyはWindow座標、注入は画面座標である。両者を直接比較しない。currentは記録済みrawXを直接使い、historyはそのsampleを含むpacketのrawX−windowXを加えたXを使う。これは固定された通常Windowの平行移動という前提であり、一般の拡大・回転変換への対応を主張しない。raw/local両方と変換量を記録する。Yやstream全体の新しい条件を終点一致へ加えない。1pxは入力sampleの識別用であり、値の許容差や①の速度閾値ではない。
- historyに終点があることはWindowへ届いた証拠であり、Sliderがそのsampleをcurrent値として採用した証拠ではない。[Compose PointerInputChange](https://developer.android.com/reference/kotlin/androidx/compose/ui/input/pointer/PointerInputChange)もcurrentとhistoryを分ける。受付・入力到達・描画完了を同義にしない。
- 描画側は既存のUP後Compose/Main barrier、semantics値、実renderの全params・時刻・完了・callback、FIFOとmailboxのdrain、実状態との一致を別に確認する。実render開始時刻の下限は一致sampleを含んだpacketのreceivedNsとし、historyの時刻や注入時刻へ置き換えない。入力到達が成立しても、実描画値の帰着許容差が外れれば補助成功にしない。入力の不明・失敗時も取得済みの実描画値を残す。
- 42入力、20ms刻み、200ms上昇＋400ms保持＋200ms復帰の800ms予定軌跡、端点・値許容差を維持する。終点直前の待機、入力点の追加/省略、非batch配送要求、強制submit/renderは追加しない。
- 新sourceのcompile・標準/診断check・独立レビュー・合成正常/negative試験は新しい証拠として扱い、旧headの成功を流用しない。試験結果、追加補助APKの識別、残件はPRへ記録する。本節の追加だけでは完成・採用・実機許可にならない。
- 実機検証の開始前に本補助作業を区切り、使用中の共有AVDを通常終了し、adbの対象がPixelだけであることを確認する。Pixelが未接続なら「対象なし」と記録し、Pixelだけであると推定しない。Pixelの配布・操作は別の許可を得るまで開始しない。
