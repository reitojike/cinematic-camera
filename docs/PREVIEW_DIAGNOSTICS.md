# プレビューの計測・直列受付・保持データの設計と確認

製品の契約は [編集とプレビュー](../specs/develop/spec.md)、診断の公開境界は [設定・診断](../specs/diagnostics/spec.md) を参照する。
本書は現行コードの技術設計と再実行手順をまとめる。過去の試験結果・採否・作業状態は関連 Issue / PR に残し、実機合格をここから推定しない。

## 2種類の時間を区別する

### 要求側：PreviewTiming

[PreviewTiming](../app/src/main/java/dev/cinematic/p0/photoroll/PreviewTiming.kt) は、Composeの要求受付からUI状態採用までを単調時計で測る。
物理的な指の操作時刻を開始点にしない。操作種別（OPEN / STRENGTH / TEMPERATURE / BRIGHTNESS / MULTIPLE）、revision、結果、区間と総時間を記録する。
設定復元による表示はOPENで、利用者がスライダーを操作した記録にしない。

- CHECK_ORIGINAL は原本照合と設定復元を含む。診断直列受付の待ちがある場合は ADMISSION_WAIT を分ける。
- MANUAL_TRIAL の TRIAL_RENDER は展開・土台・Look・変換を含む統合区間。内部の内訳は別の RawPreviewTiming で確認する。
- VERIFY_RESULT は結果照合とMainへの復帰待ちを含む。ADOPT_STATE はUI状態採用であり、ディスプレイのフレーム完了ではない。
- 訪問した区間は重複せず、同じ名前へ戻った場合は加算する。区間合計と総時間を照合し、取消・未採用を成功と混同しない。

### 処理側：RawPreviewTiming

[RawPreviewTiming](../app/src/main/java/dev/cinematic/p0/raw/RawPreviewTiming.kt) と
[Original24PreviewSession](../app/src/main/java/dev/cinematic/p0/jpegrender/original/Original24PreviewSession.kt) は、EXACT（最大1024px）とDRAFT（最大256px）を分けてセッション内の計算時間を記録する。
工程は ADMISSION / VERIFY_BEFORE / INPUT / FOUNDATION / GLOW_PREPARE / SKIN_EXPAND / GLOW / MIST_AUTO / MIST / ORTON / CHARACTER / PROTECTIONS / BITMAP / VERIFY_AFTER / CLEANUP。

- FOUNDATION は色温度・R7・露出を含む。Glow準備は別工程なので、強さだけの変更による再利用と、調整値変更による再構築を区別できる。
- 非重複区間を合計する。同じ工程の繰り返しは加算し、顔ガード等の再処理を別の成功や別要求にしない。
- セッション外の要求前処理・比較用Before Bitmap生成・描画フレーム提示は含まない。要求側のtotalと同じ値になることを期待しない。
- タイマーは工程境界だけにあり、画素ループ、取消チェックポイント、並列の既定値、保存経路を変えない。
- 計測器生成・時計・出力先の失敗は任意診断として隔離し、画素処理や後片付けの結果に置き換えない。処理/後片付けの失敗はSUCCESSにしない。

通常版ではどちらも PreviewTiming のDEBUG指定を作成条件とし、通常は計測を生成しない。統合診断版のCPU編集画面は両ログをINFOで常時取得できる。写真・URI・素材名・撮影情報・編集値・端末固有識別子をログに含めない。
既存CPU goldenは維持し、計測あり/なしの合成出力・判定値・取消挙動の同一性をテストする。

## 明示的な取得手順

対象デバイスと取得目的を確認したうえで、対象を `adb -s <対象デバイス>` で明示する。実機は承認された確認の機会だけ使用する。

1. PreviewTiming と RawPreviewTiming のログタグの現在値を控える。
2. `adb -s <対象デバイス> shell setprop log.tag.PreviewTiming DEBUG` で計測を有効にする。必要に応じて RawPreviewTiming のタグもDEBUGにして出力を取得する。
3. `adb -s <対象デバイス> logcat -s PreviewTiming:D RawPreviewTiming:D` で対象タグだけを取得する。既存の個人情報を含み得る全logcatを公開しない。
4. 初回表示・強さ・色温度・明るさを分け、慣らしと採用回数を記録する。統合診断の順序・回数・冷却・録画条件は [当日手順](RAW4_DEVICE_CHECKLIST.md) に従い、実施版と結果を確認用Issueに記録する。初回OPENや切替再計算をwarm調整へ混ぜない。
5. 終了後は変更したタグを開始前の値に戻す。未設定だった場合は空値へ戻し、明示的に無効化する場合はINFOとする。

画面録画は別の観測であり計算時間から操作→表示時間を推測しない。保存の速度試験とは条件を分けて冷却し、録画の負荷も記録する。
提示時刻との相関が必要な場合は [提示相関の診断](../tools/preview-presentation/README.md) の境界と未検証範囲に従う。
計測の採否基準や当日の所要時間はIssueの記録を使い、この手順を追加の実機操作の許可にしない。

## 診断専用のプロセス共通直列受付

[PreviewRequestGate](../app/src/main/java/dev/cinematic/p0/photoroll/PreviewRequestGate.kt) は標準coroutines Mutexを使い、
MANUAL_TRIALかつ `log.tag.PreviewSerial=DEBUG` のときだけ、呼出側の原本確認からrender/再照合の受付を直列化する。製品既定はオフで、製品採用はしていない。

- Composeの旧LaunchedEffect取消は維持する。取消済みのMutex待機は計算に入れず、実行中blockが戻るまではpermitを保持する。
- Mutexはnative・queue・Bitmap所有の代用ではない。旧結果の失効、未採用Bitmapの回収、既存資源受付を省かない。
- `awaitPreviewOwnerCompletion` はproducer futureの終了を待つ。future.cancelだけを解放証拠にしない。非協調的な処理が残る間の最新要求の待ち時間の上限は保証しない。
- 現在のMANUAL_TRIALは、通常有効な同一画面draft/exact排他にもowner終了待ちを使う。これは #217 の診断タグと独立であり、旧設計の「通常は早期復帰」という記述を現在へ転記しない。
- 比較時だけ PreviewSerial をDEBUG/INFOで切り替え、PreviewTimingのserialフラグで条件を分ける。終わったら元のタグ値へ戻す。画素・golden・保存経路を変えない。

標準根拠: [Kotlin Mutex](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.sync/-mutex/)、
[Compose side effects](https://developer.android.com/develop/ui/compose/side-effects)、
[CompletableFuture](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/CompletableFuture.html)。

## 保持済みセッションの検証

Original24PreviewSessionは縮小AS_SHOT入力、調整値に対応するR7 baseと準備済みGlow fieldを保持する。強さ変更だけで新しい同等cacheを重ねて作らない。
原本の差し替え、破棄・メモリ条件等の無効化は既存factory/所有者の契約に従い、停止・失敗したセッションが暗黙に再decodeを始めない。
256pxの入力/base/Glowと正確処理の判定値も保持し得るため、1024pxのpayloadだけでプロセスの最大メモリを見積もらない。

[PreviewCacheEquivalenceAndroidTest](../app/src/androidTest/java/dev/cinematic/p0/photoroll/PreviewCacheEquivalenceAndroidTest.kt) は、
合成DNGの保持済みセッションを別に生成したcoldセッションと比較する回帰テスト。新しい製品cacheを実装するものではない。

- 強さ・色温度・明るさの変更ごとに全ARGB画素を完全一致させ、原本fingerprint不変を確認する。Bitmapは呼出側が解放し、両セッションを閉じて未解放資源とfield残量を確認する。
- source-read / field-buildの既存assertionは再利用の証拠であり、decode/R7の実測時間の代用ではない。
- 1024正方形のAS_SHOT+baseのFloat payloadは24MiB、Glow field上限は435,692bytes（約24.416MiBの合計）。これは1024側の限定的なpayloadだけで、256側、Bitmap cache、表示コピー、native、作業buffer、PSSや瞬間ピークを含まない。
- 実行は `cinematicOriginal24Probe=true` の専用variantを使い、test classを明示選択する。テスト自身が `isolated=true` とpackage `dev.cinematic.p0.original24probe` を要求する。他の診断variantと同時指定しない。
- GMDの選択・占有と起動は [MANAGED_DEVICES](MANAGED_DEVICES.md)、既存の署名・build制約に従う。通常の製品アプリや個人写真に対してこの合成テストを実行しない。
- このセッション試験だけでPhotoRollScreen全体のlifecycle、動的な将来ルック版の変更、実機速度/メモリ/画質、OS提示時刻を検証済みとしない。セッションへの後片付け障害注入も別の確認範囲。

関連する変更・結果は [#205](https://github.com/reitojike/cinematic-camera/issues/205)、[#206](https://github.com/reitojike/cinematic-camera/issues/206)、
[#207](https://github.com/reitojike/cinematic-camera/issues/207)、[#208](https://github.com/reitojike/cinematic-camera/issues/208)、[#223](https://github.com/reitojike/cinematic-camera/issues/223) を参照する。

## 統合診断版の実編集で1/2並列を比較する

`cinematicGpuDiagnostic=true` の統合診断だけで `BuildConfig.EDITOR_PREVIEW_DIAGNOSTIC=true` とする。通常debug/release、rawDiagnostic、previewDiagnosticはfalseで、編集UI・sessionの両入口で境界を確認する。
統合診断は撮影Activity/権限を持たないが、診断用の仮の撮影所有者は登録しない。通常版と同じく、main threadで正常に観測できた空registryをアプリ内撮影処理なしと扱う。未確認owner・読取失敗/timeout、未終了callback/writer/finalize、撮影優先予約と端末カメラ利用可否・温度・メモリ・ストレージ・foregroundの判定は維持する。詳細は [編集再開の契約](../specs/develop/spec.md#再開時の撮影資源状態) を参照する。

1. Hubの「CPUプレビューの確認（Filesから選択）」から入力を明示選択し、初回1024px表示を待つ。
2. 画像上の「診断: 候補3工程の2並列」を確認する。初期値OFFは既存製品と同じ直列処理。ONは既存Foundation・Orton・Characterの画素演算だけを2 workerに渡す。残りの工程まで並列化した意味ではない。
3. 調整を閉じた確定表示中に切り替える。切替自体で同じ値を再計算する。計算中、調整中、保存中/保留/未確認中は切り替えない。保存ボタンは既存の共有JPEG保存なので、速度比較だけなら押さない。
4. OFF/ONそれぞれで強さ・色温度・明るさを同じ開始値と操作にそろえ、順を交互にして慣らし後に比較する。写真切替やアプリ再生成と通常の調整を混ぜない。画面再生成ではOFFに戻る。
5. 診断版ではタグ設定変更なしで `PreviewTiming:I RawPreviewTiming:I` を対象PID限定で取得する。RawPreviewTimingの`workers=1/2 maxEdge=256/1024 kind=DRAFT/EXACT`を確認する。各回の計算は[補助集計](../tools/preview-timing/README.md)の`per_render_ms`へ出せる。

この診断でも初回decodeと慣れた操作は区別する。表示Bitmapの保持cacheからの即返却だけを無効にして、毎回計算を通す。縮小入力、調整値依存のR7、Glow、DRAFT判断共有は通常と同じ寿命で保持するので、同じ値の切替だけではFOUNDATIONが再計算されないことがある。並列workerは各renderで作成し、終了待ちまでRawPreviewTimingに含める。

PreviewTimingは確定要求受付からUI状態採用までの比較に使えるが、指の離れた物理時刻やOSの提示完了の測定ではない。DRAFTログだけでドラッグの50ms達成を宣言しない。実機操作・個人写真の使用はその確認の許可がある回に限定する。

## R7工程を分ける診断計測（Issue #267、Draft提案）

R7全体は単一RGB→RGB表では表現できない。全体の統計を固定しても、localToneの位置別fieldが残る。
この節と`R7StageTiming`は工程の分類と測定境界だけを追加する提案であり、LUT・近似・統計cacheを実装しない。

### 取得範囲と集計

統合診断の`renderDiagnostic`だけが`R7StageTiming:I`を出す。通常previewと保存はtimerを作らない。
取得時は既存の`PreviewTiming:I RawPreviewTiming:I`にこのタグを加える。対象の実機・素材を使う許可は別途必要。
新APKの配布や実機測定が済んだことを、この実装の存在から推定しない。

- 同一sessionの`kind=EXACT/DRAFT sequence workers`で`RawPreviewTiming`と対応する。sequenceはsession内だけの番号。
  EXACTとDRAFT、worker数、coldと再計算と保持済み結果を混ぜない。写真・入力URI・調整値・統計値は記録しない。
- `totalNs`はFOUNDATIONに入った後の最初の時計取得からR7 baseを取り出すまで。RawPreviewTimingのFOUNDATIONより狭く、
  最初の時計取得前・最終ログ出力、後続の保護mask生成などは外側に残る。計測器内の配列確保はSETUPに含む。両者を足してはいけない。
- 表の区間は単調時計`System.nanoTime`で非重複に測り、再訪した同名区間は加算する。区間合計が`totalNs`。
  `Ns / 1_000_000.0`でmsへ変換する。画素ループ内に時計やログを置かず、境界・既存checkpoint・配列割当/検査の費用を含む。
  単一kernelのCPU時間ではなく経過時間であり、GC・OS scheduling・2 workerの終了待ちも含み得る。
- `r7Visited=false outcome=SUCCESS`でSETUPだけなら保持済みbaseを取り出した回。未訪問工程を0msの測定結果にしない。
  DRAFTのMETER_*欠落はEXACTで得た自動EVを再利用したため。R7の失敗/取消はFAILEDで、成功回の集計へ入れない。
  R7がSUCCESSでも後続のLookや保存/表示が成功したことは意味しない。RawPreviewTimingの結果も別に確認する。
- 時計・計測器・出力先の障害は任意診断として隔離する。その回のログ欠落を0msや成功の証拠としない。
  固定時計を使うunit試験は境界と加算の検証であり、性能測定ではない。既存のFOUNDATION合計だけのログから内部時間を復元しない。

### 非重複の分類

「画素ごと」は**この区間で行う演算**の分類であり、外部から受け取るfield/統計までRGB独立という意味ではない。
局所処理と統計を含むlocalTone全体をどれか1つに押し込めず、次の境界で分ける。3分類へ入らない準備/検査も別枠で残す。

| 計測区間 | 分類 | 内容・入力依存 |
| --- | --- | --- |
| SETUP | 準備・保持確認 | 入力/設定に対応するbaseの保持確認、AsShotInput検査等。保持済みならここだけ |
| METER_LUMA | 画素ごと | 未変更AS_SHOT中央領域の各RGBから輝度配列を作る。中央領域を使うこと自体は統計集約ではない |
| METER_QUANTILE | 画像の統計 | 中央輝度配列のsort、97%点、そこから自動EVを求める。画像全画素ではなく中央領域の分布 |
| ROI | 画素ごと | 寸法と明示ellipseから位置別weightを作る。周囲RGBは見ない。現行sessionは空ellipse/0一定 |
| WARMTH | 画素ごと | 同じRGBと色温度設定から一定利得。中立設定は既存の早期return |
| VALIDATE | 検査 | R7のEV・寸法・ROIの検査。色変換ではない |
| PROFILE | 画素ごと | 同じRGBのOKLab・色相・彩度変換 |
| LOCAL_LOG_LUMA | 画素ごと | profile後RGBの輝度とlog2、局所field寸法の計算 |
| LOCAL_BOX | 周辺画素 | log輝度のBOX縮小。複数画素の重み付き和 |
| LOCAL_BICUBIC | 周辺画素 | 縮小fieldを入力寸法へ拡大。複数field sampleの重み付き和 |
| LOCAL_QUANTILES | 画像全体の統計 | **拡大後field全体**をcopy/sortし、50%/92%点を求める |
| LOCAL_APPLY | 画素ごと | 同位置のfield値と50%/92%点から利得を求めRGBへ適用。位置別fieldと統計が外部入力として必須 |
| FOUNDATION | 画素ごと | 総EV・同位置ROIを受けたtone/彩度。ROI=0一定の場合と可変ROIの場合を区別 |
| VIBRANCE | 画素ごと | OKLabによる彩度調整 |
| CRISP | 画素ごと | 輝度curveとOKLab彩度の2pass。名前に反して周辺画素を見ないので同じ分類でまとめる |
| SKIN | 画素ごと | 同画素の色による肌weightと色調整。顔検出/周辺mask作成ではない |

LOCAL_*の5区間がlocalToneに対応する。R7自体はVALIDATEからSKINまで、測光/ROI/WARMTHはその前準備。
Glow準備/Lookはこの表とR7StageTimingの外。各工程の実機msはこの境界を持つログの取得後にだけ記入する。

### 統計を固定できる条件と縮小画像の扱い

| 値・中間結果 | 正確に再利用できる条件 | 変わる/再確認が必要なもの |
| --- | --- | --- |
| 中央P97・自動EV | 同じ未変更AS_SHOTの画素・寸法・向き・crop・測光式の版 | 新しい入力/解像度/crop等。手動warmth/EV/Look強度は参照しない |
| profile後画像 | 同じAS_SHOT・寸法・warmth・profile/色変換の版 | warmth変更。総EV/ROI/Look強度は未適用 |
| localToneのfieldと50%/92% | 同じprofile後画像・寸法・BOX/BICUBIC/係数の版 | warmth・入力・寸法変更。総EV/ROI/Look強度は参照しない |
| localTone後画像 | 上記field/統計/入力とlocalTone係数が同じ | 同じwarmthなら手動EV変更からは独立 |
| foundation以降 | 同じlocalTone出力・総EV・ROI・R7/色変換の版 | 手動EVはここから作用。ROIが位置で変わればRGBだけの一表ではない |

これはcache可能性の条件であり、新cacheを採用したという意味ではない。現行EXACTはbaseを再構築する際に
`R7PreviewInputs.prepare`を呼び直し、DRAFTはEXACTの自動EVを共有して中央P97を再測定しない。

小さいAS_SHOT画像からP97を出す場合、BOX平均と中央領域の画素境界/分布が変わり、元解像度の分位と一般に一致しない。
localToneは既にlog輝度を小さいgridへ落としているが、そのgridの分位ではなく**拡大後の全fieldの分位**を使用する。
小さいgridだけの分位に置換する、または先にRGB画像を縮小してprofile→log→field→分位を作ると、非線形変換・
resize重み・丸め・sample分布が変わるため近似である。既存出力の正確な保持とは区別して別途誤差評価が必要。

統計2個だけを固定しても、LOCAL_APPLYには位置ごとのfieldが残る。提案を検討する順番は、実測で統計・空間処理・
画素計算の負担を分け、同条件での正確な再利用が効く範囲を確認し、その後に必要なら近似を別に評価すること。
本変更は統計の推定、LUT、演算順序、出力、既定値、保存経路を変更しない。
