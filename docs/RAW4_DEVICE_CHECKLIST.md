# 統合診断の Pixel 9a 当日手順

関連: [#208](https://github.com/reitojike/cinematic-camera/issues/208)、[#237](https://github.com/reitojike/cinematic-camera/issues/237)、保存比較 [#78](https://github.com/reitojike/cinematic-camera/issues/78)。
旧ファイル名は参照互換のため維持する。本書を実行順・反復・ログ回収の入口とし、実施版と結果はIssueに記録する。
手順の準備は実機操作・写真利用・インストールの許可ではない。実行する回に対象と範囲を確認する。

## 今回の限定メニュー（#270）

今回の準備対象は、保存3候補のメモリ・取消、GPUの内訳、原本照合軽量化後のプレビュー時間だけである。以下の一般手順にある保存20回、CPU1/2並列AB、GPUのメモリ/PSS/背景試験を自動追加しない。GPU採否は内訳を読むまで保留する。APK配布と実機確認は、準備完了後に本人からその回の許可を得てから行う。

### 取り込みと固定成果物

#262、保存profile #263、#268とR7内訳 #271がmainへ取り込まれたcleanなsourceを使う。#269も取り込み・検証済みなら含め、対象sourceの記録に有無を明示する。未mergeの寄せ集めAPKを当日版にしない。
`gpuDiagnostic` 1 APK、package `dev.cinematic.p0.gpudiagnostic` に統一し、別variantのflagsを併用しない。source/tree・APK hash・署名証明書hash・manifest・最適化条件を固定し、許可前に配布/アップロード/インストールしない。既存署名不一致はデータ消去で回避しない。

### 1. GPU内訳を別modeで採取する

メモリ観測OFF、録画なし。比較順・熱・充電条件を記録し、各ブロックで回収と冷却を確認する。

- 1024px全Look: 「内訳を分離」OFFのbaselineとONのsplitを各1回。各回はHDR/暗部それぞれ初回1＋再利用12、合計26対を自動実行する。各強度の通常/逆順は既存のABBAのまま。ボタンを押し直した初回をwarmへ数えない。
- 12MPぼかし/Orton: 各入口でbaseline通常/逆、split通常/逆を各1回。8回のボタン実行、各回初回1＋再利用3の計32対。1024pxとの単位を揃えるため実calls/passes/bytesを参照する。
- baseline/splitと初回/反復、入力、強度、比較順を混ぜず、stageごとの各行と条件別中央値を出す。各行のtotalと非重複内訳＋otherの和を照合する。baselineは従来3内訳、splitだけが詳細内訳を持つ。
- CPU確保・pack/unpack・入力準備/検査、GPU割当・upload・draw・readback、解放・otherと実calls/passes/論理bytesを残す。Orton CPU前処理、EGL準備、対象2工程、全Lookは別区間である。
- splitはtexture確保方法と同期条件が異なるため、旧upload値との差をそのままCPU変換時間としない。splitをbaselineの30ms判定へ入れず、約150ms固定費や純粋なGPU限界を2サイズだけで決めない。
- 1024pxのCPU差と12MP Ortonの部分品質条件、完了/解放を照合する。12MPぼかしは画素品質未測定であり、finiteを合格の代用にしない。

### 2. workers OFFでプレビューを測る

GPUの熱が残らない状態で、本人が選ぶ同じ12MP DNGの同一sessionを使う。診断2並列スイッチはOFFのままにする。初回OPENをwarm調整と分け、強さ・色温度・明るさをそれぞれ慣らし1回＋測定4回、計15調整とする。値戻し・切替・採取開始以前のbufferは測定へ数えない。前回に対応する開始値/終了値・方向・800ms操作は私有の対応表で照合する。

各操作のDRAFT256は正常renderの工程中央値を操作1件とし、EXACT1024は対応する最新確定renderを1件とする。render数が多い操作へ重みを増やさず、操作・kind・成功/取消を分ける。工程時間、処理total、要求→UI状態採用を別に示し、物理入力→OS提示と呼ばない。強さ/色温度/明るさでR7/Glow再利用条件が異なるため混ぜない。

#268後のVERIFY_BEFORE/AFTERはmetadata・読取権限の軽量確認で、未知mtimeはSHAへfallbackする。以前の全文SHA区間と同じ内容の速度比較にしない。全体値を比べる際もsource、provider、cold/warm、操作、充電/温度を合わせ、変更された安全性の境界を併記する。metadataだけから内容一致を主張しない。#269を含む場合でも初回decodeと保存の768MiB条件は維持され、warm条件変更を初回必要RAMの実測と扱わない。

同じ操作で `R7StageTiming:I` も採取する（#271、追加操作なし）。同sessionのkind/sequence/workersでRawPreviewTimingと対応付け、r7Visitedとoutcomeを確認する。保持済みbaseではSETUPのみ/r7Visited=false、DRAFTではEXACTの自動EVを共有するためMETER区間がない場合がある。未訪問stageを0ms実行へ補完せず、R7内側totalを外側FOUNDATIONへ足さない。R7が成功しても後続Lookが失敗したrenderはRaw側outcomeで除外する。詳しいstage定義は [プレビュー診断](PREVIEW_DIAGNOSTICS.md) を参照する。 #271の追加計時・ログの負担も今回の条件に含むため、旧版との差を#268単独の改善量と断定しない。

### 3. 保存3候補のメモリ・取消

詳細は本書の保存安全確認節と [取消fieldの定義](RAW_DIAGNOSTIC_BUILD.md#手動cancel受付から停止解放までの観測) を正とする。Memory A/B各1回、自動取消BBのdecode後/Foundation後各1回に加え、候補Bの手動CancelをGlow/Mist中に各1session観測する。計6試行、開始ボタンは4回で、速度20回は再実行しない。候補BはGlow2/Mist2/tone2・色融合OFF、Foundation2/Orton4/Character2を維持する。旧RAW4の色融合ON profileで代用しない。

手動CancelはUIハンドラー受付を起点に、render退出・use/close試行退出・owner解放確認・harness静止確認を分ける。物理タッチ時刻や個別worker内部の停止時刻ではない。USER_BUTTON、実際のstageAtRequest、renderOutcome、対象取消観測statusとresourcesQuiescentを確認する。STOPPED/complete=falseを速度成功へ補完せず、未到達/未解放はnullのまま残す。中断後の原本最終照合未実施を原本不変確認済みにしない。取り逃し・離脱先行・未押下・正常完走・HOLDを区別し、際限なく再試行しない。

### 全ブロック共通の記録と停止

各ブロックの開始/終了、温度/充電状態、実行数・成功・取消・欠測、owner/worker/FGS終了を記録する。最初の走行で必要タグ/fieldsが存在することを確かめ、空のまま反復しない。原本/品質不一致、MODERATE以上、低メモリ、未回収、busy/HOLDでは次へ進まない。GPUは失敗を含め最大2試行で止め、熱が残ればCPUへ負荷を移さない。予定枠を超えた分は未測定とし、回数を削って合格扱いしない。

私有の原本対応表・生ログ・写真別情報は外部へ出さない。今回の結果を共有する範囲は別途の許可に従う。準備のCI/合成成功を実機の時間・画質・メモリ安全性の成功へ読み替えない。

## 0. 版と条件を固定する

- 対象は同じPixel 9a。CPU編集/保存だけは本人が診断用に選ぶ12MP DNG 1枚を使い、原本を変更しない。GPU/CPU3工程比較は合成入力だけ。24MPは追加しない。
- 単一の `gpuDiagnostic` APKを使う。packageは `dev.cinematic.p0.gpudiagnostic`。通常製品版、旧 `.rawdiagnostic` / `.previewdiagnostic` と取り違えない。入力URI・設定の自動移行を前提にしない。
- source commit/tree、APK SHA-256、署名証明書SHA-256、ABI、min/target SDK、debuggable/profileable、R8/native最適化条件を控える。版名だけで同一buildと判断しない。
- 当日版には実編集1/2並列切替と編集Activityだけの再開修正を含める。APKにsource SHAが埋め込まれていない場合、cleanな固定commitからのbuild記録とhashを一緒に保持する。固定成果物や生ログはGitへ入れない。
- 対象sourceの必須CI、診断lint/build、manifest隔離・署名を確認する。エミュレーター成功は実機の導入/Files互換・GPU性能・画質・再開の合格ではない。
- GPUは #257 / #258 のsession再利用・初回/反復分離を含む版を使う。旧固定source `0b2a5eba` のAPKは未対応なので使わない。取り込み後のcleanなmainから統合診断APKを再buildし、source/tree/hash/署名を固定してから開始する。最終sourceの合成GPU試験成功と手順整合まで確認し、APKがあるだけで開始条件成立とはしない。
- 導入時の署名不一致をアンインストール/データ消去で回避しない。通常アプリを上書きしない。CPU編集の保存ボタンは共有JPEG保存なので、性能確認では押さない。
- 端末の明るさ、給電、充電状態、設定を固定する。各走行の前後に加え、各試験項目を始める前にthermal status・電池温度・充電状態を記録する。開始状態が比較可能になるまで冷却し、欠測を安全確認済みとみなさない。
- 通常アプリの現像/保存や他の重い処理と重ねない。別packageの負荷はHubの排他だけでは防げない。OS警告、MODERATE以上の発熱、低メモリ、異常発熱、品質不一致では止める。上限拡大・強制冷却設定・性能設定の変更で続けない。

### ローカル記録とログ

実施ごとに `run-id` を決め、版/入力の対応表と温度・回数表をローカルに置く。写真名・URI・原本hash・EXIF・写真固有値・端末識別子・個人パス・録画・生ログは公開しない。
Issueには匿名の工程/操作、回数、build条件、温度区分、数値集計と採否理由だけを残す。

対象端末は必ず `adb -s <対象デバイス>` で明示し、対象packageのPIDを確認する。再起動後はPIDを取り直し、ログを別セッションにする。
ログはブロックごとに新しいローカルファイルへ採取し、途中の設定切替やwarmupを時刻/回数表へ記録する。logcatは開始時に同じPIDの過去bufferも出すため、採取開始境界の端末時刻を控え、当該runの操作開始前の記録を必ず集計対象から除外する。新しいファイル名だけで新規runの証拠にしない。全logcatを公開せず、計測前の端末ログ消去も前提にしない。

```text
adb -s <対象デバイス> logcat --pid=<対象PID> -v threadtime -s GpuDiagnostic:I
adb -s <対象デバイス> logcat --pid=<対象PID> -v threadtime -s PreviewParallelDiagnostic:I
adb -s <対象デバイス> logcat --pid=<対象PID> -v threadtime -s PreviewTiming:I RawPreviewTiming:I
```

各ブロックの最初の走行で対象タグが非空、条件・成功/失敗・終了が読めることを確認する。空のまま反復を重ねない。
最後は操作回数と記録数、取消/失敗数、owner/FGS/worker終了を照合する。停止要求を送っただけで終了としない。
Hubがbusy/取消待ち/HOLDなら別入口へ進まない。RAW診断HOLDは既存契約に従い明示再起動し、その回を成功へ補完しない。

## 途中で止める条件と次の進み方

- **熱が続く場合は全検証をその日で止める。** OSの温度警告、MODERATE以上のthermal status、触って熱い状態、温度上昇が続く場合は取消し、owner/FGS終了を確認して自然に冷ます。一度休憩しても開始前と比較可能な状態へ戻らない、または再開するとすぐ熱くなる場合は別の日に回す。GPUからCPUプレビューへ負荷を移して続けない。強制冷却・充電/性能設定変更・際限ない待機と再開は行わない。
- **GPU項目の失敗は初回を含め最大2回。** crash、例外、完走しない、品質不一致は失敗として記録する。1回目で止まったら未完データを採否から除き、FGS/owner/メモリ採取の終了とHubの非busy、熱・メモリの正常を確認できる場合だけ同じ項目をあと1回試してよい。原因が能力非対応など明確な場合は1回で止めてよい。部分反復から続きを走らせず、新sessionの初回から開始する。
- **2回目も失敗したら、その日の残りのGPU試験を止める。** 入口、合成条件、失敗段階、試行回数、build/source、温度区分、回収できたかを記録する。未測定/失敗を30msや画質の合格へ変えず、3回目・別GPU入口による埋め合わせはしない。
- GPU失敗だけで、資源回収と端末の正常・冷却を確認できた場合は「2. CPUプレビュー」へ進んでよい。熱が残る、低メモリ、owner/FGS未終了、Hubがbusy/HOLD、原因不明の不安定が続く場合はCPUにも進まず、その日は終了する。データ消去/再インストール・上限拡大で回避しない。
- 意図した取消確認は速度試験の失敗回数に混ぜないが、取消が完了しない場合は回収失敗として全検証を止める。端末警告、原本不一致、保存品質不一致は保存側の既存停止条件を優先する。予定枠が尽きたら未完項目を明記し、回数を減らして合格にせず別日に回す。

これは手動の当日判断であり、アプリが自動で温度判定・再試行・CPUへの移行をする機能ではない。公開するのは匿名の要約だけで、生ログ/写真/端末識別子を載せない。

## 1. GPU合成診断を冷間で測る

詳細は [GPU診断](GPU_DIAGNOSTIC.md)。Hubを開くだけでは処理しない。速度ではメモリ観測OFF、録画なし。
先に小型試験や別診断を走らせた場合、最初の1024pxを未負荷の冷間値とは呼ばない。端末の冷間/温間と、GPU初期化済み/未初期化は別の条件として記録する。

### 初回・再利用ログと開始前の確認

最終sourceのGPU合成試験で、毎回EGL/context/shaderを作り直す版と準備を1回にする版のfloat出力一致、CPU許容差、取消・解放・次sessionを確認する。実機の速度/画質合格とは区別する。旧 `0b2a5eba` は呼出ごとに初期化を含むので、初回run除外や既存内訳の減算では新条件を測れない。

1. 冷えた状態で「1024px 全Look比較」を1回押す。HDR/暗部それぞれで1 sessionを作り、`GPU準備` の `preparationNs` と `GPU初回` の `firstTwoStagesIncludingPreparationNs` / `firstLookIncludingPreparationNs` を別記する。初回は強度0.25、`iteration=0 phase=first` で30msの反復判定から外す。準備込み値は実測区間の和であり、間に実行するCPU基準や画面表示、最終session解放を含まない。
2. 同じボタン実行内で、各強度0.25/0.5/1.0を同一sessionの `phase=reused` として4回ずつ自動比較する。通常→逆→逆→通常（ABBA）、Reverse選択時はBAAB。1 sessionは初回1＋反復12、HDR/暗部で計26対のCPU/GPU比較である。`対象GPU2工程` の session/iteration/width/dark/strength/reverseを対応づけ、対象2工程の `sumNs`、uploadNs/drawNs/readbackNs、Orton CPU準備、`全Look` のCPU/GPU時間を別記する。入力/強度/順序を混ぜて1つの中央値にしない。必要なら冷却後にReverseで1回（新sessionとしてさらに26対）を取るが、初回をwarmへ付け替えない。context喪失/失敗/取消は未完として止め、自動再生成や欠測補完をしない。
3. 冷却後、「12MP ぼかし単体」「12MP Orton単体」を各通常/逆1回、計4回のボタン実行。各実行で初回1＋再利用3の4対（計16対）を自動処理する。`GPU準備` / `GPU初回` とphase/iteration別のCPU/GPU、転送・同期draw・readbackを分ける。Ortonは全帯を同じ文脈で処理し、入力/soft field生成とCPU基準をGPU時間から除く。部分工程の少数screeningであり、p95や全保存の確定的改善率を主張しない。
4. メモリは速度と別走行。1024px・12MPぼかし・12MP Ortonを観測ONで各1回。100ms周期のmanaged/nativeは観測最大で、瞬間peakではない。最大値を足して同時peakとしない。
5. PSSが必要な場合は上の速度/周期samplerと別の走行に分け、対象入口を各1回、前後だけ記録する。取得時点を残し、速度の採否に使わない。取れなければ未測定とする。
6. 背景は長く動作する12MP入口を選び、前景でFGS開始→Home移行の1回と、前景でFGS開始→画面消灯の1回を分ける。実際に背景/非interactiveで動いたこと、完了ログとFGS終了を確認する。移行前に完了した回は背景成功に数えない。
7. 同じ入口で取消1回→終了回収→再実行1回を確認する。取消中に次を開始しない。背景/取消/再実行の時間は速度反復へ混ぜない。

### GPUの採否境界

- 1024pxのGlow bloom 0.006ぼかし＋Orton GPU合成の合計30ms以内を目安とする。EGL文脈・shader初期化を除いた、同じ準備済み状態での2回目以降を判定対象にし、操作ごとの転送・同期・readbackを含める。初回の準備込み時間、準備時間、Orton用fieldのCPU準備、全Look時間は別記する。`phase=first` と旧版の初期化込み `sumNs` は判定対象外。upload/draw/readbackは同期で区切ったCPU wall時間で、純粋な転送/演算時間ではない。入力をGPUに常駐させた場合の性能も未測定。
- CPUとの全Look最大差2/255以内、差画素数/割合、暗部の新しい平坦隣接点を記録する。数値だけでは実機GPU/実写の目視合格にしない。平坦化カウントはバンディングの目視判定の代用ではない。
- 12MPぼかしの画素差は未測定。Ortonの品質は一定色の部分工程だけ。finite/checksumや完走を画質一致としない。
- 12MP部分工程をCPUのDNG→JPEG全保存時間と直接比較しない。「全保存で明らかに速い」「保存全経路で最大1/255以内」は今回の入口では未判定。製品採用は別判断とする。
- API別FGSは製品と同じ種別だが、GPU背景診断の完走は製品保存の復旧・撮影競合の合格ではない。
- 初回の完了時間と温度で残り枠を見直す。品質不一致・異常・資源不足・想定枠超過は中断し、際限なく再試行しない。

## 2. CPUプレビューの工程と1/2並列を比較する

GPU試験後に熱が残っていれば冷却してから始める。合成比較、強さ、色温度、明るさの各項目前に温度と充電状態を記録し、比較中に条件が変われば同じ集合へ混ぜない。PreviewSerialの受付直列化（#217）は不採用で再試験しない。
以前の「ドラッグ更新のない基準版で#217 OFF/ONを32操作」は今回へ持ち込まない。

### 合成3工程の比較

Hubの「CPU候補3工程（合成1/2比較）」を使う。Foundation・Orton・Characterの各工程は同一合成入力で比較する。
1→2と2→1をそれぞれ1回慣らした後、1→2、2→1、2→1、1→2の4回を記録する。全3工程のraw-bit完全一致を必須にし、工程別時間・温度を記録する。
入力生成/ハッシュは計測外、worker開始/終了は計測内。実編集全体や入力→表示の速さと混同しない。

### 実編集画面の比較

1. Hubの「CPUプレビューの確認（Filesから選択）」で本人が入力を選び、1024pxの正確表示を待つ。初回OPEN/decodeはwarm調整と別記する。
2. 診断スイッチはOFF=1、ON=2 worker。調整を閉じた確定表示で切り替える。同じ値で起きる切替再計算は測定調整に数えない。
3. 強さ・色温度・明るさの各項目でOFF/ONを1回ずつ慣らす。測定は正順ブロックOFF→ON→ON→OFF、逆順ブロックON→OFF→OFF→ONとし、各設定4回、3項目で計30調整（慣らし6＋測定24）。同じ開始/終了値・方向・動かす速さを使い、値戻しや切替再計算は別記して除外する。
4. 各設定/kindで `RawPreviewTiming` の `workers=1/2 maxEdge=256/1024 kind=DRAFT/EXACT` を確認する。実際のDRAFTは1ドラッグで複数出るため、「3操作」と「3render」は同じ数ではない。
5. 強さ・色温度・明るさを別ファイル、再生成/入力切替を別セッションにする。集計器は操作名を判別しないため、3項目を同じmeasured.logへまとめない。各操作の慣らし・値戻し・切替再計算・初回OPEN・開始前bufferを時刻境界で除き、kindとworkers別に集計する。warmupを除外済みなら `--warmup 0` とし、二重除外しない。以下のmeasured.logは1項目・1セッションの測定ログを表す。採否の集計ではさらに正順/逆順と各ドラッグ操作を分ける。

```text
python -B tools/preview-timing/summarize_processing.py measured.log --kind DRAFT --workers 1 --warmup 0
python -B tools/preview-timing/summarize_processing.py measured.log --kind DRAFT --workers 2 --warmup 0
python -B tools/preview-timing/summarize_processing.py measured.log --kind EXACT --workers 1 --warmup 0
python -B tools/preview-timing/summarize_processing.py measured.log --kind EXACT --workers 2 --warmup 0
```

`per_render_ms`・中央値・p95・件数・取消/失敗・malformedを確認する。この補助集計はtotalNsだけを集計し、工程別中央値は出さない。工程別内訳は同じ操作/設定/kindと採用レコードの集合でRawPreviewTimingの工程ごとのnsをmsへ変換し、各回・中央値・件数を別表にする。未記録の工程を0msとせず、セッション外の処理も推測で配賦しない。p95はrender標本のnearest-rankであり、少数操作の統計的保証ではない。
workers混在をまとめず、記録欠落を成功で補完しない。失敗や不正行は性能見送りでなく未判定/要調査とする。

通常版と違い両タグはINFOで常時出るため、PreviewTimingをDEBUGへ変える必要はない。
表示Bitmapの即返却cacheだけを避け、縮小入力/R7/Glow/判定値は保持する。同値スイッチだけではFoundationを再計算しない場合がある。
Foundation・Orton・Character以外まで並列化した意味ではない。工程時間と全体を両方比較する。製品既定は変えない。

### プレビュー並列化の事前判定基準

- 実務上の性能採用候補の目安は **10%以上短縮**。既存RAW4の10%を参考に、測る前に固定する。統計的有意差の閾値や50ms提示の基準ではない。工程単体の短縮だけでは実編集の採用候補にしない。
- 強さ/色温度/明るさ、DRAFT/EXACT、正順/逆順を分けて判定する。DRAFTは1操作内の正常renderのtotalNs中央値を操作1件の値とし、EXACTはその指離しに対応する最新確定renderのtotalNsを1件の値とする。高速側でDRAFT件数が増えても操作の重みを増やさない。
- 各順序ブロックのOFF/ON各2操作の中央値から、短縮率 `100 × (1 - ON中央値 / OFF中央値)` を求める。同じ項目/kindで正逆両方10%以上ならその条件の性能採用候補、両方未満なら今回条件で見送り、正逆で判定が割れたら追加順序比較へ保留する。少数のscreeningであり確定的な改善率とは呼ばない。
- DRAFT正常記録なし、対応EXACT不明、不正/欠測、温度/充電条件の不一致、操作途中の失敗は採否へ変換しない。通常の指離しに伴う取消も件数を残し、成功中央値へ混ぜない。必要な記録が揃わなければ未判定で止める。
- 性能候補でも、合成raw-bit一致、本人の実機目視、値保持・最新結果だけの採用・取消回収を満たす必要がある。他の項目/kindの悪化や未判定も併記し、一部の短縮だけで全体ONを採用しない。製品への接続/既定変更は別判断とする。

## 3. ドラッグ表示と再開を確認する

前項の30調整で、ドラッグ中の更新、指離し後の正確像、最新値の採用を観察する。計算ログは同じ操作から回収し、追加の30調整を必須にしない。
録画を使う場合は比較するOFF/ONを同じ録画条件にそろえ、「録画あり」と明記する。本人がマイク音声OFF・個人情報や通知が映らない状態を確認し、録画をローカルだけで扱う。

- RawPreviewTimingはセッション内の処理時間。PreviewTimingは確定要求受付→UI状態採用。どちらも物理入力→OS提示完了ではない。
- 現在の統合APKには提示時刻相関の自動試験を接続していない。256px DRAFTの数値から50ms追従の合否を出さない。
- 録画は実timestampで更新/指離し/安定を読む。接触時刻や要求世代との対応が曖昧なら未判定。ログのUI採用やFrameCommitを実提示の代わりにしない。
- 最新要求だけが採用され、古い結果が戻らないことを確認する。計測後に写真切替、取消、編集終了/復帰を各1回の機能確認として分離する。
- 編集画面だけを閉じて再開し、撮影画面へ戻らず現像可能かを別枠で確認する。画面再生成では並列OFFに戻る。明示的なforce-stop/process killはこの手順へ自動追加しない。
- 初回decode、判定値未準備の初回fallback、再生成、通常のwarm調整を混ぜない。先行準備/埋込画像のUI採用はこの試験の対象外。
- 向き/レイアウト等の普段使い確認は通常版で別に行い、性能診断へ混ぜない。

実機の修復・表示品質・50ms/500msの提示目標は、合成試験や内部ログだけでは合格にしない。
表示/提示の未判定は未判定として残し、取得済みの計算時間とは分けて次の判断へ渡す。

## 4. 保存候補のメモリと取消だけを確認する

保存の速度判定はGlow / Mist / R7 toneの2並列を採用候補、色融合（#203）を不採用として確定済み。
充電状態の差を理由に速度20回をやり直さない。ここでは製品既定を変更せず、次の短い機会に残る安全確認だけを行う。
時間不足ならこのブロックを別日にする。録画・GPU・CPU診断を止め、冷却してから開始する。
詳細なbuild/所有権は [RAW診断](RAW_DIAGNOSTIC_BUILD.md#保存採用候補の安全確認profile261)。当日APKが新入口を含むsourceか照合する。

### 新しいSAVE_SAFETY: メモリ2回と取消2境界

1. Hubの「保存4候補の比較」からRAW診断画面を開く。上部の `Save safety` 3ボタンのうち、まずMemoryと自動取消を使う。
   旧RAW4のBは色融合ONのままなので、下のlegacy controlsで代用しない。
2. `Save safety: Memory A/B (2 attempts)` を1回押し、同じ許可済み12MP DNGを選ぶ。
   AはGlow1 / Mist1 / tone1、BはGlow2 / Mist2 / tone2。両方とも色融合OFF、Foundation2 / Orton4 / Character2固定。
   このボタンはlegacyのMemory / Reverse / 比較選択を無視し、AB各1回のみ。warmupや速度反復を追加しない。
3. reportで `comparison=SAVE_SAFETY`、`mode=MEMORY_PERIODIC_NOT_TIMING`、`order=AB`、A/B成功各1、
   `complete=true`、`status=SUCCESS`、全JPEG一致、選択原本不変、private入力削除、owner終了を確認する。
   各runの `fuseR7ColorRows=false` とworker数も照合する。100ms目標のmanaged/nativeは観測最大、PSSは前後だけ。
   sample数・実間隔・最大gap・観測費用・欠測・sampler終了を記録し、時間改善や瞬間peak保証に変換しない。
4. 冷却・資源回収の確認後、`Save safety: 2 cancellation checks` を1回押して同じDNGを選ぶ。
   同じB構成で2試行を自動実行し、native decode完了後とFoundation完了後に各1回取消する。
   reportの `comparison=SAVE_SAFETY`、`mode=CANCELLATION_BOUNDARIES_NOT_TIMING`、`order=BB`、
   `complete=true`、`status=SUCCESS`、`expectedCancellationChecks=2` と、両runの色融合OFF、
   完了結果なし・部分JPEGなし・owner終了・原本不変を確認する。ボタンを2回押す手順ではない。
5. `cancelBoundary` は `NATIVE_DECODE` / `R7_FOUNDATION`、`cancellationScope` は
   `AFTER_NATIVE_DECODE_AND_FOUNDATION_NOT_WORKER_IN_FLIGHT`。後者はtone完了後・Glow/Mist到達前であり、
   tone/Glow/Mistの並列worker実行中やnative内部の任意時点取消を証明しない。新しい境界を今回自動追加しない。

- thermal/電池温度/充電状態は安全確認時も記録する。速度の採否を再計算したり、充電差だけで20回の再測定を要求したりしない。
- 本人のCancel/離脱によるsession中断は期待する2回の成功に数えない。失敗scratchは既存契約で私有保持され得る。owner終了とscratch全消去を混同しない。
- 私有JPEGは各成功試行後削除されるため、目視画質・通常製品の共有保存/FGS復旧の代替にならない。
- このprofileは速度集計器 `compare_raw4.py` の対象外。旧RAW4の保存済みreportを新profileへ読み替えない。
  実機確認が済んだ後に製品既定へのPRを別途判断し、診断準備だけで製品採用済みとしない。

### Glow/Mist中の手動取消待ち時間（追加観測）

既存2境界では実行中の待ち時間を測れないので、同じ候補Bの単発を別sessionで使う。速度20回は再測定しない。

1. 冷却・owner解放後に `Save safety: Manual Cancel latency (B)` を押し、同じ許可済みDNGを選ぶ。
   画面下部の工程表示とCancelを見える位置にし、`Running GLOW` を見てCancelを押す。別sessionでMistも1回観測する。
2. reportのmodeが `MANUAL_CANCELLATION_LATENCY_NOT_TIMING`、comparisonが `SAVE_SAFETY`、orderが `B`、候補worker数/色融合OFFか照合する。
3. `cancellationObservation.requestOrigin=USER_BUTTON`、`stageAtRequest=GLOW` または `MIST` を確認する。
   UI受付時刻が起点であり、物理タッチ→OS配送の待ち時間は含まない。工程表示が遅れ、別工程で押した回は別結果として残す。
4. `renderOutcome` と、受付→render退出、use/close試行退出、owner解放確認、harness静止確認の4区間をそれぞれ記録する。
   render内部の回収時間も含む。close試行の退出だけでworker静止とせず、owner/harness解放の値がnullなら未確認。
5. `status=STOPPED`、`complete=false` はこの中断観測では想定どおり。対象工程での観測成立は
   `cancellationObservation.status=OBSERVED_TARGET_STAGE_CANCEL` と `resourcesQuiescent=true` で区別する。
   `ownerClosed` / `noCompletedResult` / `outputAbsentAtAttemptScopeExit` / 私有入力削除も確認する。
   `selectedOriginalUnchanged` は中断後の最終照合未実施のため真に補完しない。
6. 押さずに完走、取り逃し、開始前/完了後の押下、離脱による取消、失敗、HOLDを混ぜない。未到達はnullのまま。
   新しい取消位置や10秒以内などの成功閾値は設けず、実測待ち時間と制約を残す。

[各fieldの意味と限界](RAW_DIAGNOSTIC_BUILD.md#手動cancel受付から停止解放までの観測)を参照。
Glow/Mistの画素worker内部を直接観測した証拠ではない。回収不明・OS警告・低メモリ・異常発熱は停止し、無制限に再試行しない。

### 参考: 旧RAW4の速度判定手順（今回の再実行対象外）

1. Hubの「保存4候補の比較」でRAW4を選び、Memory/ReverseをOFF。同じ許可済みDNGをFilesで選択する。
2. warmup AB＋ABBA×2の計10回を待つ。complete=true、status=SUCCESS、全JPEG一致、選択原本不変、private入力削除、owner終了を確認し、ローカル `forward.json` へreportを保存する。
3. 冷却後、同じAPK/原本/設定でReverseだけON。warmup BA＋BAAB×2の計10回を行い、同じ条件を確認して `reverse.json` を保存する。
4. 入力対応表で同じ原本bytes/APK・端末条件を照合してから集計する。report自体には入力識別情報がない。

```text
python -B tools/raw-diagnostic/compare_raw4.py forward.json reverse.json --same-input-build-confirmed
```

Glow/Mistは工程中央値が両順10%以上短縮なら性能採用候補、両順未満なら今回条件で見送り。順序で割れた場合だけ追加順序比較。
tone/色が両順10%以上ならR7単独比較へ切り分ける。全体/未分解残差を併記し、安全・品質・製品採用と分ける。
各armの測定4回からp95・統計的有意差を主張しない。不足/失敗/品質不一致/owner未終了は未判定で止める。

旧速度計画の互換参照として残す。旧メモリはlegacy controlsのRAW4・Memory ONでA/B各1回、
旧 `Select DNG for 2 cancellation checks` はRAW4 B（色融合ON）の2境界取消を維持する。
これらは今回の採用候補の安全確認を代替しない。

## 終了・所要時間の扱い

- 各ブロック終了時に結果件数と回収を確認する。途中で止めた場合は取得数・条件・理由を残し、足りない回を推定しない。変更した診断タグは開始前の値へ戻す。
- GPUは1024pxの1回で26対、12MPの各1回で4対を処理する。旧版の所要枠を流用せず、新版の初回完了時間と温度から残り枠を見直す。メモリ/PSS/背景と冷却の所要は未実測であり、全項目を同日に終える条件にしない。
- CPU比較は合成慣らし2＋測定4と実編集30調整、初回/切替/値戻し/復帰確認が加わる。各項目前の温度/充電記録と必要な冷却も見込み、処理時間だけで操作時間を見積もらない。
- 今回の保存はメモリ2回分と取消2境界、手動取消のGlow/Mist各1回の到達/回収、選択/照合/冷却。旧速度計画の20回は所要枠へ加えない。過去の別buildの時間から完了時刻を約束しない。
- 実測なしの総所要時間を約束しない。優先順を守り、熱・時間不足なら保存を別日にする。
- 私有report/録画の保管先を本人が確認し、公開は匿名集計と採否理由だけにする。実機/写真未実施を準備完了へ付け替えない。
