# 統合診断の Pixel 9a 当日手順

関連: [#208](https://github.com/reitojike/cinematic-camera/issues/208)、[#237](https://github.com/reitojike/cinematic-camera/issues/237)、保存比較 [#78](https://github.com/reitojike/cinematic-camera/issues/78)。
旧ファイル名は参照互換のため維持する。本書を実行順・反復・ログ回収の入口とし、実施版と結果はIssueに記録する。
手順の準備は実機操作・写真利用・インストールの許可ではない。実行する回に対象と範囲を確認する。

Gate 1 第2部の実施版選択（エンジン改善B有/無）・事前登録・当日手順は
[GPU_GATE1_PART2](GPU_GATE1_PART2.md)を参照する。最終APK固定後に配布と実機の許可を改めて求める。

## Gate 1 の現行CPU比較基準（#285、準備中・未実施）

[Gate 1 #285](https://github.com/reitojike/cinematic-camera/issues/285) では、GPUと同じ機会に、
**#299・#300・#302・#303を含む現行CPU**の強さ・色温度・明るさ操作を測る。
この節はGate 1手順へのCPU比較の追加分であり、後続の#270限定メニューを再実行する指示ではない。
#270当時の配布・実機許可を流用しない。#284の統合、手順・計測入口・最終APKの検証が揃った後に、
対象端末・素材・範囲・所要枠を提示し、**診断APKの配布と実機確認の許可を改めて求める**。
現時点でCPU改善の実機短縮量やGate 1の合否は未確認である。

### 比較版と固定条件

- 計測するsource commit/treeが [#299](https://github.com/reitojike/cinematic-camera/pull/299)（分位点selection）、
  [#300](https://github.com/reitojike/cinematic-camera/pull/300)（不変値の重複検査削減）、
  [#302](https://github.com/reitojike/cinematic-camera/pull/302)（DRAFT原本照合省略）、
  [#303](https://github.com/reitojike/cinematic-camera/pull/303)（待ちの分解・Main往復削減）をすべて含むことを確認する。
  「現行」の呼称だけで旧APKを基準にせず、source/tree・APK SHA-256・版・ABI・最適化条件を実施記録へ残す。
- 原則として同じ固定source・同じ統合診断APKのCPU/GPU入口を使う。CPUは通常製品と同じ
  候補3工程の2並列OFF（`workers=1`）、`PreviewSerial`の追加直列受付OFFで固定する。
  CPUの入力・R7・Glow等の通常の保持/再利用を維持し、診断入口で無効になる表示Bitmapの即返却cacheは条件に明記する。
  製品版そのものの全応答時間や、4件個別の効果を分離した試験とは呼ばない。
- 同じPixel 9a・入力bytes・向き・provider・開始/終了値・操作方向・操作時間・回数・録画条件を
  私有対応表で照合する。長辺1024px以下の同じAS_SHOT入力を基準にし、実際の処理寸法も記録する。
  CPUのDRAFT256とEXACT1024を分け、GPU1024とDRAFT256の時間比を同解像度の高速化率にしない。
- 合成画像と本人が選んだDNGを分ける。合成入力を両入口へ同条件で渡せない場合は未実装として準備を止め、
  別画像の時間で埋めない。DNG原本・写真名・URI・写真ごとの数値はGit/Issue/PRへ出さない。

### 同じスライダー操作と当日の組み込み

導入 → 合成画像 → DNG → 比較表示 → 終了の各段へCPU比較を組み込み、
GPUだけを一巡した後に別日のCPU値を当てはめない。各入力のCPU/GPUについて、強さ → 色温度 → 明るさの順にそろえる。
既存の800ms操作、項目ごとの慣らし1回＋測定4回（各経路・各入力15調整）を初期案とし、
GPU側の必要な連続計測時間と合わせて**操作時間・回数・採用/除外範囲を計測前に#285へ固定する**。
時間/回数を変える場合はCPU/GPUを同時に変更し、結果を見てから延長しない。
初回OPEN・慣らし・値戻し・写真切替・cold/warmは通常調整と分離する。

- 経路の実行順も事前登録する。例として強さはCPU→GPU、色温度はGPU→CPU、明るさはCPU→GPUとし、
  各経路の前に開始条件へ戻す。順序差は残るため、少数の操作だけで統計的な優位を断定しない。
- CPU分の追加枠は入力1種類あたり5〜8分、冷却は別枠の計画値とする。合成/DNGの2種類なら10〜16分を
  Gate 1全体の予定へ加え、GPU側の所要を含む総枠は準備後に提示する。未実測の所要を保証しない。
- 前後にOS thermal status・電池温度・充電/給電状態を記録する。開始はNONE(0)/LIGHT(1)で比較組のstatusをそろえ、
  主要ブロック間は最低2分冷却する。電池温度は当日基準±1℃・30秒間隔2回の安定を目安にし、
  条件差は記録する。追加5分でも戻らなければ残りを延期する。充電状態の変化を同じ比較へ混ぜない。
- OS thermal statusの上昇、OS警告/異常発熱、OOM/ANR/クラッシュ、原本/品質不一致、
  owner/worker未回収、busy/HOLD、GPU能力不足やcontext lossの反復があれば停止する。
  熱が残ったままCPU/GPUを切り替えて続けない。枠超過・欠測を埋める追加測定は本人へ相談する。

### 記録する時間と解釈

ログの取得と区間定義は [プレビュー診断](PREVIEW_DIAGNOSTICS.md)、
処理側の集計は [既存の補助集計](../tools/preview-timing/README.md) を使い、計測器を新設しない。
対象PIDと採取開始境界を固定し、同じ操作から `PreviewTiming` / `RawPreviewTiming` /
`R7StageTiming` / `PreviewOriginalCheck` の取得可能な記録を回収する。

| 区分 | 同じ操作について残すもの | 混同しない境界 |
| --- | --- | --- |
| CPU DRAFT / EXACT | 操作別・kind別の成功標本数、中央値・p95・最大、成功/取消/失敗/欠測、実際の寸法・workers・保持状態 | DRAFTは最大256px、EXACTは最大1024px。初回と保持済みを分ける |
| CPU要求側 | `PreviewTiming`総時間と待ち/描画/UI採用の区間 | effect開始/actor受信以降であり、物理入力・channel全待ち・OS提示は含まない |
| CPU処理側 | `RawPreviewTiming`総時間・工程、必要なR7内訳と原本照合の種別 | 要求側に内包されるので足さない。未訪問/未計装は0msへ補完しない |
| GPU | #285のフレーム時間中央値・p95・最大、取りこぼし、初回`openPhoto`、GPUメモリ、熱・充電状態 | CPUログと計測境界が揃う項目だけ並べる。GPU frameとCPU要求/処理時間を同じ列で割らない |
| 操作/表示の観察 | ドラッグ中の更新、指離し後の確定、最新値の採用、欠測/対応不明 | UI採用・FrameCommitはOS提示ではない。ログ欠落から取りこぼし0を推定しない |

DRAFTは操作ごとの正常render数と分布も残し、renderの多い操作だけが重くならないよう
操作ごとの中央値を併記する。EXACTは各操作に対応する最新確定renderを使う。
p95はnearest-rankと標本数を明記する。4操作等の少数標本では最大値になり得る参考統計で、
母集団のp95保証とは呼ばない。失敗・取消を成功の分布へ入れず、欠測や対応不明は未判定とする。

#302前後のVERIFY区間と#303前後の要求側区間は同じ仕事/境界とは限らない。
旧版の結果や削減した走査数を、この4件を含む現行版の実機短縮量へ読み替えない。
比較可能な旧記録がある場合だけ条件と境界の差を併記し、旧版の再導入は自動追加しない。
**CPUの今回値を新しい比較基準として残すこと**と、旧版からの因果的な短縮率の証明を分ける。

CPU比較は判断材料を追加するもので、#285のGPU速度・画素差・目視・96MiBのGate 1基準を変更しない。
今回のCPU操作速度と、CPU見本に対するGPU画素差の検査も別に記録する。
終了時は両経路の資源解放・記録数を確認し、変更したタグを戻す。私有ログと写真別の対応表は非公開で保管し、
Issueには匿名集計・条件・未確認点のみを残す。手順の追加やCI成功を実機確認済みとは扱わない。

## 今回の限定メニュー（#270）

今回の準備対象は、保存3候補のメモリ・取消、GPUの内訳、原本照合軽量化後のプレビュー時間だけである。以下の一般手順にある保存20回、CPU1/2並列AB、GPUのメモリ/PSS/背景試験を自動追加しない。GPU採否は内訳を読むまで保留する。本人は今回の診断APK配布と限定実機確認を許可済み。追加のA/B取消診断（#273）が最終検証・main取り込み済みになり、新しい固定APKを照合してから始める。許可のない撮影/通常版削除/端末設定変更/写真や私有ログの公開は追加しない。

本書の保存 `SAVE_SAFETY` profileでは、**A＝候補なし（Glow1 / Mist1 / tone1）、B＝候補あり（Glow2 / Mist2 / tone2）**。両方とも色融合OFF、Foundation2 / Orton4 / Character2固定とする。ここでのA/Bは保存profileの構成を表し、GPUの実行順やプレビューの並列切替とは区別する。私有記録にもA/Bと候補なし/ありを併記する。

比べた差が微妙、または条件が不揃いな項目は未判定とし、**その項目だけ追加測定するかを本人へ相談してから決める**。後付けの許容値で採否を決めず、無断の再測定を追加しない。

### 取り込み・通常版への影響・終了後

当日版は#273のA/B取消診断が通常mergeされたclean mainから固定する。**#268と#269は両方含む**。#262 GPU内訳、#263保存安全/取消、#271 R7内訳も含む。以前のsource33aabf43はBのみの手動取消なので今回のA/B判定には使わない。最終source/tree・APK hashは配布記録で確定し、文書commitをAPK sourceとは呼ばない。

`gpuDiagnostic` 1 APK、package `dev.cinematic.p0.gpudiagnostic`。通常版 `dev.cinematic.p0` とは別packageで、通常版のインストール/私有データ/設定を上書きしない。診断2並列/GPUを通常版の設定へ反映しない。ただしCPU/RAM/発熱は端末全体で共有するため「端末へ一切影響しない」とはしない。両アプリを同時に処理させない。撮影/音声権限はなく、CPU編集の共有保存ボタンは押さない。

source/tree・APK hash・既存署名証明書hash・manifest・最適化条件を固定し、許可前に配布/アップロード/インストールしない。既存診断版との署名/版の不一致はアンインストール・データ消去・強制downgradeで回避せず止める。

終了・回収後、必要な私有report/logを本人の許可先へ退避して照合できれば、**この診断packageはアンインストールしてよい**。診断アプリ内のreport、cache、設定、未削除の私有入力/失敗scratchも消える。通常版の私有データとFilesで選んだ外部原本は別領域であり、今回の手順は原本を変更しない。必要な診断データを失う前に保管を確認する。これは削除の実施許可ではなく、こちらでの削除は本人の別許可後に対象packageを再確認して行う。

### 順序・予定枠・条件をそろえる

今回の実行順は一般手順の章番号ではなく下表を使う。本人の優先順位どおり**GPU→プレビュー/R7→保存**の順にする。ブロック終了ごとに私有ログ/reportと件数・回収を確定し、後続を止めても完了ブロックを利用できるようにする。全体合格へ不足分を補完しない。

| 順序 | 範囲 | 操作を含む予定枠（未実測の計画値） |
| --- | --- | --- |
| 0 | 許可・APK/写真/充電/温度の照合 | 3〜5分 |
| 1 | GPU1024px baseline→split | 3〜5分 |
| 2 | GPU12MP blurの両mode/正逆→Ortonの両mode/正逆 | 5〜8分 |
| 3 | Preview/R7: 強さ→色温度→明るさ、各5調整 | 5〜8分 |
| 4 | 既存Memory ABでJPEG一致確認→単発Memory A/BをABBA | 8〜12分 |
| 5 | 保存自動取消2境界→手動GLOW/MISTの各ABBA | 8〜12分 |
| 各間 | 資源解放確認・記録・冷却 | 合計20〜30分を別枠 |

取消とメモリのA/B条件合わせを追加し、全体は**約50〜80分の計画値**へ見直す。既に確認した40〜65分の枠や本人の終了時刻を無断で延長せず、超える分は未測定として残す。新しいsplit同期・工程到達待ち・操作・冷却は未実測なので所要保証ではない。最初の実行時間から残り枠を見直し、本人が30分だけ希望する場合は同じ順で完了ブロックまでに止める。枠を超えたため回数/冷却を削って合格にしない。許可時に今回の終了時刻を決める。

- 同じPixel 9a・同じ12MP原本bytes・Files provider・向き・開始値/終了値・方向・800ms操作を私有対応表で照合する。前回そのものの充電条件が混在していた場合、再現できない過去値は参考値とし、今回は1状態を固定する。通常版/他の負荷を並走させない。
- 充電/給電状態、battery status、電池温度、thermal statusを各項目前後に記録する。途中の充電切替は同一比較組に混ぜない。配布許可はADB方式変更・無線debug設定・給電設定変更の許可ではない。
- 提案する冷却規則は、表の主要ブロック間に最低2分休み、開始時のOS thermal statusがNONE(0)またはLIGHT(1)で同じ比較組は同じstatus、電池温度が当日最初の基準±1℃内かつ30秒間隔2回で安定すること。2分で戻らなければ待つ。5分追加しても戻らない場合は当日残りを延期する。この幅は再現条件の提案であり、安全温度の保証や安全上の自動停止閾値ではない。冷却により基準の下限をわずかに下回る場合は、冷えすぎだけを理由に待機せず、温度と条件差を記録して続行し、比較の解釈へ反映する。条件差で判定が曖昧になれば未判定とし、その項目の再測定は本人へ相談する。OS thermal statusがMODERATE(2)以上、異常発熱、回収不明などの停止条件は緩和しない。
- GPUのmode/正逆を変える前も同じ開始状態へ戻す。メモリの相対比較は#273の単発A/B入口を別開始し、各runの前に冷却/開始条件をそろえる。既存Memory ABは連続2試行で中間冷却できないため、同一sessionのJPEG一致/原本確認用に分け、冷却済み相対比較へ読み替えない。自動取消2境界も内部の中間冷却はできない。
- MODERATEはAndroid OSのthermal status実値2であり、電池温度34℃などの一律温度閾値ではない。thermal statusと電池温度を別々に記録する。OS statusがMODERATE(2)以上、OS発熱警告/異常発熱、lowMemory/資源gate拒否、OOM、ANR/クラッシュ/予期しないprocess終了、原本/品質不一致、owner/worker/sampler/FGS未回収、busy/HOLDでその回を停止する。CPU/GPUを切り替えて継続せず、保存済みの完了結果だけ保持する。
- 取消は1回受理させて解放を待つ。設定緩和・強制GC・強制冷却・強制終了で回収成功を作らない。手動force-stop/再起動/削除はこの許可へ自動追加しない。OSが終了させた場合は回収未確認の失敗として残す。
- GPUはエラー時のみ、回収確認・冷却後に1回再試行できる（同じ入口の初回を含め最大2回）。2回失敗または回収不明なら残りGPUを停止する。初回をwarmへ付け替えない。時間不足/取り逃し/欠測を埋める無制限反復はしない。

### 計測前に固定する判断表

この表は本人が確認した事前目安と追加指示を区別する。#237の30ms/画質は既存基準。previewの40%/10%/5%は見込みの仮説、取消2秒/5秒は参考値で、取消採否はA/B非悪化と回収で判定する。いずれも実機の安全保証ではない。測定を見てから都合よく基準を変えない。欠測・条件不一致・取消/失敗は「未判定」、正常な全記録があって閾値外の場合だけ「今回条件では見送り」とする。

**GPU**
- #237の更新後基準どおり、baseline・phase=reusedの1024px対象2工程sumを入力/強度/順序別に30msと比べる。転送・同期・readback・呼出内のCPU変換/割当を含み、EGL/shader準備を除く。各条件の中央値と全個別値を残す。全条件の中央値が30ms以内でも、全Look最大2/255以内・本人目視・回収を満たすまで製品採用しない。初回・Orton CPU前処理・全Lookは別記する。
- 30ms超で、splitのCPU pack/unpack・明示確保が最大の負担なら、buffer保持/RGB変換・コピー回数削減を次の限定Issue候補にする。GPU割当・転送/readback・passesの負担が最大なら、texture再利用/往復削減・GPUに保持する構成の検証候補とする。比率は各raw行で求め、中央値の差から復元しない。
- 30ms超でdraw/driver待ちが主要であり、変換/確保を減らすだけでは解消しない場合は、**現行の同期・帯処理方式を見送る**。splitは同期を増やすので、その内訳をbaselineから引いた仮想30ms達成は主張しない。候補変更は別Issueでbaseline相当のend-to-endを再検証する。
- baselineとsplitの条件差、otherの大きさ、反復ばらつきで主因を切れなければ保留。2サイズやcall平均だけで固定150ms・GPUハードウェアの限界としない。画質超過・不一致・未回収は性能評価より先に停止する。
- 12MPは部分工程のCPU比較と件数/bytesの説明に使う。保存全経路の1/255以内・明らかな高速化は今回未判定であり、部分工程だけで保存GPUへ進まない。

**プレビュー**
- 前回の同じ写真/操作/worker OFF/計測境界の値を本人用の対応表へ記載し、強さ・色温度・明るさとDRAFT/EXACTを分ける。旧新VERIFY_BEFORE/AFTERは**別表**にし、同名工程の速度比を作らない。
- 見込みはDRAFT（ドラッグ中）約40%、EXACTの強さ約10%、色温度/明るさ約5%の短縮という本人の仮説。項目/kindごとの前回対応値をTとし、目安はそれぞれ0.60T/0.90T/0.95T。実測短縮率とこの見込みとの差を示す。全項目へ一律10%を適用せず、見込みを達成保証や採用の自動合格基準にしない。
- metadataが既知なら毎回の全文SHA読取を省くが、未知mtimeはSHA fallback。R7/Look自体は高速化しておらず、#271の追加計時負担も含む。#269はwarmの資源判定変更であって演算高速化ではない。
- DRAFTの短縮が見込みを下回った場合は、差が大きいケースを見逃さないため少なくとも一度#268の適用を確認する。配布/導入したAPK hashとsource、診断のManualTrial経路・callback配線、metadataのmtime既知/不明とSHA fallbackの可能性、新版内のVERIFY時間と他工程残時間、操作/入力/充電/熱/保持条件を順に点検する。timingだけから軽量経路かfallbackかを断定しない。観測できない分岐は未確認とし、必要なら別の限定診断へ分ける。
- 4操作の個別値・範囲・操作均等中央値を示し、ばらつき/旧条件不一致なら参考比較に止める。前回充電混在で同条件標本を確保できなければ因果的な改善率/合格を出さない。統計的有意差/p95保証や50ms OS提示を主張せず、旧版再インストールABを自動追加しない。

**R7: 次の検証先**
- 同一の成功renderで、S= METER_LUMA + METER_QUANTILE + LOCAL_QUANTILES、E= WARMTH + PROFILE + LOCAL_LOG_LUMA + LOCAL_BOX + LOCAL_BICUBIC + LOCAL_QUANTILES + LOCAL_APPLYを求める。Sは統計値の作成に必要な輝度生成も含む。SとEは重複するため足さない。未訪問は欠測、保持済みbaseを再計算0msとしない。外側RawPreviewTiming totalを分母にし、各renderの比率から操作単位で比較する。
- Eが明るさ変更totalの10%以上なら、同じ入力/解像度/色温度での**明るさだけ前半の正確な再利用**を第一候補にする。総EVはfoundation以降へ作用し、色温度変更にこの再利用を広げない。保持メモリ/無効化/出力一致の検証を別Issueで行う。
- Eを保持する案と重複しない条件でSがtotalの10%以上なら、**統計を正確に固定する案**を候補にする。自動EVは同じAS_SHOT/寸法/crop/測光式、local統計は同じprofile後画像/色温度/field版が必要。局所fieldは残り、低解像度から統計を代用するのは近似なので別承認・誤差検証が必要。
- 正確な再利用で除けない画素計算が主要に残り、GPU内訳から転送/回収を含む利益の見込みが立つ場合だけ、**R7のGPU検証**を候補にする。現GPUはR7を測っていないので、今回のGPU msをR7へ比例配分しない。CPU再利用を先に検討し、GPU方式が見送り/未判定ならGPUへ自動移行しない。
- 10%は次に調べる優先度の提案であり、全該当工程をゼロ時間にできる保証ではない。差が小さい/複数候補が拮抗/記録不完全なら決めず、統計固定やLUTをこの診断中に実装しない。

**保存: 観測範囲と事前budget**
- **真のプロセスpeakの数値上限は、現APKでは合否判定できない**。managed/nativeは100ms目標の個別観測最大、PSSは前後だけである。2つの個別最大を同時peakとして足さず、PSS開始/終了をPSS peakにしない。上限未満という安全合格にはしない。768MiBはdecode/save時のsystem空きメモリadmissionで、アプリpeak上限・確保予約・必要量の実測ではない。
- 今回の観測増加budgetはmanaged/nativeを別々に、観測最大B ≤ A + max(Aの10%, 32MiB)とする。行並列化で大きな増加がないかを検出する比較用の設計値で、32MiBは理論上限ではない。超過は採用保留、範囲内でも真peakの合格にしない。各arm2回の観測最大のうち大きい値を比較値A/Bとし、単発ABBA（各arm2）の各run値を残して中央値と範囲を比べ、順序/GC/開始メモリが不揃いなら未判定にする。少数反復でもallocator/GC状態の完全同一は保証しない。sampleCount>0、quiescent=true、failed=false、failedSamples=0、両max非nullを測定成立条件とし、maxGap>300ms（100ms目標の3倍）ならこの比較も未判定とする。300msも短いpeak捕捉の保証ではない。
- この回で出せるのは、A/Bの観測最大・PSS前後・sample数/実間隔/maxGap/収集費用・欠測と、OOM/lowMemory/異常終了の有無。本人用報告に数値上限を決める根拠がまだないことを明記し、今回は許可された観測値の収集に限定し、**真peak安全性の合格や製品採用を確定しない**。絶対上限の厳密判定が必要になれば、追加の計測設計へ分ける。測定後に都合のよい上限を後付けしない。
- **取消採否はAに比べてBの取消→停止時間と資源解放が悪くならないこと**。参考2秒（render退出）/5秒（owner/harness静止）の超過だけで3候補を落とさない。A自体も目安を超える場合、取消checkpointを増やす改善は別Issueとし、この診断中に位置を変えない。
- #273の手動A/B入口でGLOW/MISTを分け、各工程A→B→B→Aの4単発（各arm2）、合計8観測を上限とする。A/Bとも入力・設定・狙う工程・押下手順・冷却/充電をそろえ、intendedCancellationStage、実際のstageAtRequest、stageElapsedAtRequestNanosを残す。MISSED_INTENDED_STAGEは取り逃しであり有効対へ入れない。同じ経過nsでも同じ内部worker/行位置の証明ではない。工程表示が初めて対象になったら速やかに1回押し、報告の実stageを正とする。
- 比較可能な各工程/armで4つの受付起点区間を各runから比較し、個別値・中央値・範囲を示す。比較可能な同工程でBの中央値または観測最大がAを超える、あるいはBだけ回収条件を満たさない場合は採用保留。両方がA以下でも『今回の有効観測では悪化なし』に限る。押下経過/環境が不揃い、結果が揺れる、どちらか欠測/失敗なら非悪化を確定せず未判定。単発対や8回のscreeningで統計的非劣性を主張しない。後付けの許容幅や選別でBがよく見える対だけ残さない。
- 両構成・両工程の有効観測で、USER_BUTTON・狙った実stage・renderOutcome=CANCELLED、4区間非nullかつrender≤scope=owners≤harness、resourcesQuiescent=true、ownerClosed、noCompletedResult、outputAbsentAtAttemptScopeExit、private入力削除、harnessCloseFailed=falseをすべて確認する。null/HOLD/クラッシュ/取り逃し/離脱先行/未押下完走は合格に補完しない。
- 参考秒数を超えても強制終了せず、協調取消と回収を確認するまで次を始めない。sampler/IO owner/並列owner静止、別のGPU/保存FGSなしも確認する。harness時刻に最終report書込/UI表示は含まれないため、report確定/Hub idleまで別に待つ。メモリの即時baseline復帰やプロセス生存は解放の代用にしない。自動2境界は別の機能確認である。

### GPU内訳の採取内容（実行順は上表）

メモリ観測OFF、録画なし。比較順・熱・充電条件を記録し、各ブロックで回収と冷却を確認する。

- 1024px全Look: 「内訳を分離」OFFのbaselineとONのsplitを各1回。各回はHDR/暗部それぞれ初回1＋再利用12、合計26対を自動実行する。各強度の通常/逆順は既存のABBAのまま。ボタンを押し直した初回をwarmへ数えない。
- 12MPぼかし/Orton: 各入口でbaseline通常/逆、split通常/逆を各1回。8回のボタン実行、各回初回1＋再利用3の計32対。1024pxとの単位を揃えるため実calls/passes/bytesを参照する。
- baseline/splitと初回/反復、入力、強度、比較順を混ぜず、stageごとの各行と条件別中央値を出す。各行のtotalと非重複内訳＋otherの和を照合する。baselineは従来3内訳、splitだけが詳細内訳を持つ。
- CPU確保・pack/unpack・入力準備/検査、GPU割当・upload・draw・readback、解放・otherと実calls/passes/論理bytesを残す。Orton CPU前処理、EGL準備、対象2工程、全Lookは別区間である。
- splitはtexture確保方法と同期条件が異なるため、旧upload値との差をそのままCPU変換時間としない。splitをbaselineの30ms判定へ入れず、約150ms固定費や純粋なGPU限界を2サイズだけで決めない。
- 1024pxのCPU差と12MP Ortonの部分品質条件、完了/解放を照合する。12MPぼかしは画素品質未測定であり、finiteを合格の代用にしない。

### workers OFFでのプレビュー採取内容

他の負荷や熱が残らない状態で、本人が選ぶ同じ12MP DNGの同一sessionを使う。診断2並列スイッチはOFFのままにする。初回OPENをwarm調整と分け、強さ・色温度・明るさをそれぞれ慣らし1回＋測定4回、計15調整とする。値戻し・切替・採取開始以前のbufferは測定へ数えない。前回に対応する開始値/終了値・方向・800ms操作は私有の対応表で照合する。

各操作のDRAFT256は正常renderの工程中央値を操作1件とし、EXACT1024は対応する最新確定renderを1件とする。render数が多い操作へ重みを増やさず、操作・kind・成功/取消を分ける。工程時間、処理total、要求→UI状態採用を別に示し、物理入力→OS提示と呼ばない。強さ/色温度/明るさでR7/Glow再利用条件が異なるため混ぜない。

#268後のVERIFY_BEFORE/AFTERはmetadata・読取権限の軽量確認で、未知mtimeはSHAへfallbackする。以前の全文SHA区間と同じ内容の速度比較にしない。全体値を比べる際もsource、provider、cold/warm、操作、充電/温度を合わせ、変更された安全性の境界を併記する。metadataだけから内容一致を主張しない。#269を含む場合でも初回decodeと保存の768MiB条件は維持され、warm条件変更を初回必要RAMの実測と扱わない。

同じ操作で `R7StageTiming:I` も採取する（#271、追加操作なし）。同sessionのkind/sequence/workersでRawPreviewTimingと対応付け、r7Visitedとoutcomeを確認する。保持済みbaseではSETUPのみ/r7Visited=false、DRAFTではEXACTの自動EVを共有するためMETER区間がない場合がある。未訪問stageを0ms実行へ補完せず、R7内側totalを外側FOUNDATIONへ足さない。R7が成功しても後続Lookが失敗したrenderはRaw側outcomeで除外する。詳しいstage定義は [プレビュー診断](PREVIEW_DIAGNOSTICS.md) を参照する。 #271の追加計時・ログの負担も今回の条件に含むため、旧版との差を#268単独の改善量と断定しない。

### 保存3候補の採取内容

詳細は本書の保存安全確認節と [取消fieldの定義](RAW_DIAGNOSTIC_BUILD.md#手動cancel受付から停止解放までの観測) を正とする。既存Memory AB2試行はJPEG一致/原本確認に残す。メモリ相対観測は単発A/BをABBAで4試行、自動取消BBのdecode後/Foundation後を2試行、手動CancelはGLOW/MIST各ABBAで8試行とする。保存計16試行（Memory6＋自動取消2＋手動8）、開始は14回で、速度20回は再実行しない。候補BはGlow2/Mist2/tone2・色融合OFF、Foundation2/Orton4/Character2を維持する。旧RAW4の色融合ON profileで代用しない。

手動CancelはUIハンドラー受付を起点に、render退出・use/close試行退出・owner解放確認・harness静止確認を分ける。物理タッチ時刻や個別worker内部の停止時刻ではない。USER_BUTTON、実際のstageAtRequest、renderOutcome、対象取消観測statusとresourcesQuiescentを確認する。STOPPED/complete=falseを速度成功へ補完せず、未到達/未解放はnullのまま残す。中断後の原本最終照合未実施を原本不変確認済みにしない。取り逃し・離脱先行・未押下・正常完走・HOLDを区別し、際限なく再試行しない。

### 全ブロック共通の記録と停止

各ブロックの開始/終了、温度/充電状態、実行数・成功・取消・欠測、owner/worker/FGS終了を記録する。最初の走行で必要タグ/fieldsが存在することを確かめ、空のまま反復しない。原本/品質不一致、MODERATE以上、低メモリ、未回収、busy/HOLDでは次へ進まない。GPUの失敗時は同じ入口の初回を含め最大2試行で止め、熱が残ればCPUへ負荷を移さない。予定枠を超えた分は未測定とし、回数を削って合格扱いしない。

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

### 既存SAVE_SAFETY: JPEG一致用Memory ABと自動取消2境界

1. Hubの「保存4候補の比較」からRAW診断画面を開く。上部の `Save safety` 専用入口を使い、既存Memory AB、冷却後の単発Memory ABBA、自動取消、手動取消の順で確認する。
   旧RAW4のBは色融合ONのままなので、下のlegacy controlsで代用しない。
2. `Save safety: Memory A/B (2 attempts)` を1回押し、同じ許可済み12MP DNGを選ぶ。
   AはGlow1 / Mist1 / tone1、BはGlow2 / Mist2 / tone2。両方とも色融合OFF、Foundation2 / Orton4 / Character2固定。
   このボタンはlegacyのMemory / Reverse / 比較選択を無視し、AB各1回のみ。warmupや速度反復を追加しない。
3. reportで `comparison=SAVE_SAFETY`、`mode=MEMORY_PERIODIC_NOT_TIMING`、`order=AB`、A/B成功各1、
   `complete=true`、`status=SUCCESS`、全JPEG一致、選択原本不変、private入力削除、owner終了を確認する。
   各runの `fuseR7ColorRows=false` とworker数も照合する。この連続ABはJPEG一致/原本の証拠とし、冷却した単発A/Bの代用にしない。100ms目標のmanaged/nativeは観測最大、PSSは前後だけ。
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
- 本人のCancel/離脱によるsession中断は期待する2回の成功に数えない。失敗scratchは全owner解放後に回収する。解放または削除未確認はHOLDとし、原本や公開の記録は保全する。
- 私有JPEGは各成功試行後削除されるため、目視画質・通常製品の共有保存/FGS復旧の代替にならない。
- このprofileは速度集計器 `compare_raw4.py` の対象外。旧RAW4の保存済みreportを新profileへ読み替えない。
  実機確認が済んだ後に製品既定へのPRを別途判断し、診断準備だけで製品採用済みとしない。

### 保存メモリの単発A/B比較（#273）

既存Memory ABの同一JPEG一致/原本不変を確認した後、#273の `Save safety: Memory A (1 attempt)` / `Save safety: Memory B (1 attempt)` を使う。A→B→B→Aの4単発を上限にし、同じDNGとAPKを私有対応表で照合する。各runの前にowner解放・冷却・同じ充電/OS thermal・開始メモリを確認する。原本importと採取境界、100ms目標sampler、前後PSSの方法は両armで同じにし、Memory単発と手動取消を同時に行わない。

- 同じ候補構成差だけであること、`mode=MEMORY_SINGLE_ARM_NOT_TIMING`、`order=A` / `B`、選択arm成功1/他0、`complete=true`、最終原本不変・private入力削除・owner/sampler静止を確認する。単発には構成間JPEG一致の証拠がなく、`crossArmJpegComparison=NOT_AVAILABLE_SINGLE_ARM`、`memoryComparisonDecision=REQUIRES_MATCHED_CONDITIONS_NOT_AUTOMATIC_PASS` を確認する。既存ABの一致を、単発で直接比較した証拠へ読み替えない。
- managed/nativeごとに、各arm2回の観測最大のうち大きい値をA/Bの比較値とする。全個別値、中央値、範囲、memoryBefore/After・sample数/間隔/maxGap/失敗も残す。前の判定表の増加budgetを適用する。
- 候補なし/ありで開始メモリや充電/熱条件がずれる、順序で傾向が変わる、採取欠測がある場合は未判定。強制GC・アプリkill・電源/性能設定変更で揃えない。ABI/プロセス/冷間と温間を混ぜず、瞬間peak・PSS peakとしない。
- 計測器を新設せず、既存の観測最大の限界を維持する。時間の採否や旧速度20回を再開しない。

### Glow/Mist中の手動取消A/B比較（#273）

既存自動2境界では実行中の待ち時間を比較できないので、次の4つの明示入口を使う。各ボタンは1session/1候補、入力選択前に候補と狙う工程を固定する。

- `Save safety: Manual Cancel A / GLOW`
- `Save safety: Manual Cancel B / GLOW`
- `Save safety: Manual Cancel A / MIST`
- `Save safety: Manual Cancel B / MIST`

1. GLOWでA→B→B→A、次にMISTでA→B→B→A。各run前に回収・冷却・条件を確認する。毎回同じDNGを選び、戻ったら画面末尾のCancelと現在工程表示が同時に見える位置へ移動する。初めて狙った工程表示が出たら速やかにCancelを1回押す。表示の準備前に狙った工程を過ぎた回は取り逃しとして残す。内部workerの行位置を固定した試験ではない。
2. modeが手動取消観測、comparison=SAVE_SAFETY、arm/orderとworker数・色融合OFF・intendedCancellationStageを照合する。USER_BUTTON、実際のstageAtRequest、stageElapsedAtRequestNanosを正とし、狙いと異なるMISSED_INTENDED_STAGE、遅れ/取り逃しを有効対へ数えない。
3. UI受付→render退出・use/close試行退出・owner解放・harness静止の4区間を候補A/B別に記録する。物理タッチ時刻や純粋な最終画素演算停止ではない。条件差を後付けの許容nsで正当化しない。
4. 判定は冒頭のA/B非悪化と全解放条件を用いる。2秒/5秒は参考として併記し、超過だけでB不採用としない。Aにも同様の待ちがあれば取消位置追加は別課題にする。
5. STOPPED/complete=falseは想定どおり。対象工程の観測成立とresourcesQuiescent、ownerClosed/noCompletedResult/outputAbsentAtAttemptScopeExit/private入力削除を確認する。harness null/HOLDを0秒/合格にせず、最終report/UI確定まで待つ。中断後の原本最終照合未実施を原本不変確認済みに補完しない。
6. 押さず完走、取り逃し、離脱先行、例外/回収不明は別結果。8回の上限を超える自動再試行や取消checkpoint変更はしない。不足は未判定で報告する。

[各fieldの意味と限界](RAW_DIAGNOSTIC_BUILD.md#手動cancel受付から停止解放までの観測)を参照。観測成功やreportの出力はA/B採否の自動合格ではない。B-onlyの旧結果をA/B非悪化の証拠へ付け替えない。

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
- 一般手順のCPU比較は合成慣らし2＋測定4と実編集30調整だが、今回の統合確認は並列OFFのみ15調整であり、一般のAB試験を追加しない。初回/切替/値戻し/復帰確認、各項目前の温度/充電記録と必要な冷却も見込み、処理時間だけで操作時間を見積もらない。
- 今回の保存はJPEG一致用Memory AB2回、単発Memory ABBA4回、取消2境界、手動取消のGLOW/MIST各ABBA計8回の到達/回収、選択/照合/冷却。旧速度計画の20回は所要枠へ加えない。過去の別buildの時間から完了時刻を約束しない。
- 実測なしの総所要時間を約束しない。優先順を守り、熱・時間不足なら保存を別日にする。
- 私有report/録画の保管先を本人が確認し、公開は匿名集計と採否理由だけにする。実機/写真未実施を準備完了へ付け替えない。
