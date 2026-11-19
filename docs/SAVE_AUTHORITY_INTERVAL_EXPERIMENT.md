# 保存 authority の永続照合間隔実験（#475、未採用）

追補完了: UNKNOWN再照合のtimeoutは旧UIセレクターの不一致でした。製品APK不変のまま試験1行を修正し、0/50/100msのprocess-death・復旧24経路が成功しました。第一候補は50msですが、既定・releaseは0msのまま、未採用のDraft候補です。公開・CI・レビューの現状はPR記録を参照してください。

この文書は既定オフの Draft 候補の設計です。製品採用、v0.1.0 算入、実機速度の合格を意味しません。元の診断 worktree と証跡は保全し、製品コードが同一のリリース候補から独立した branch で検証します。メモリ世代による失効管理は実装しません。

## 有効範囲

通常の debug と release は 0ms（従来経路）です。debug の `saveAuthorityIntervalMs=50` または `100` だけが成功照合の短時間共有を有効にします。Android 試験は明示した合成 AVD 内でのみ同じ設定を切り替えます。試験 0ms のカウンターは照合結果を保持せず、I/O を直列化しません。release は常に 0ms です。

共有は単一 owner の単一 `advance` 呼出しの間だけです。prepare、restore、次の advance、他の要求へ成功結果を持ち越しません。外側 owner.releaseResources、実行枠解放、CLEAN の確定は共有窓の外です。Original24Attempt 内部の DECODE_CLOSE / RESOURCE_CLOSE / SCRATCH_CLEANUP は窓内で、本体の後に強制照合します。request / intent / attempt / consent の durable 判定内容と順序は従来どおりです。原本の公開経路 5 回照合、全バイトの hash、JPEG の描画・エンコード設定は変えません。

## 境界

| 箇所 | 方針 |
|---|---|
| 行処理、待機ポーリング、JPEG 書込の前後 | 既存のメモリ取消を毎回呼び、成功した永続照合だけを最大 50/100ms 共有 |
| DngContentVerifier の各 checkContinue（64 KiB read 前後・EOF・open 前／close 後を含む） | 同じ試行の owner / publisher の重複照合を同じ窓で共有。読み取り・コピー・hash の順序は維持 |
| 帯の受渡し・GL 処理前後、GPU stage 境界 | 強制照合。窓内の成功でも永続読み取りを実施 |
| OriginalStageTrace の各工程前後 | IO owner に結び付いた強制照合。trace が null でも有効 |
| RESOURCE_CLOSE / DECODE_CLOSE / SCRATCH_CLEANUP | cleanup 本体を先に実行。検証失敗で解放を妨げない |
| publisher の検証・コピー工程前後、公開直前 | 強制照合。既存の公開順序を維持 |
| AndroidAtomicJournalStorage.replace の直前 | IO owner に結び付いた強制照合。journal 書込を成功キャッシュで許可しない |

ThreadLocal は現在の checkpoint の強制／共有区分だけに使い、工程全体を共有可能にしません。GL／reader へ IO queue を同期 dispatch しません。追加の GPU readback、fence、待機用 worker はありません。

## 競合と失敗

実照合は window の monitor で single-flight とし、待機後に単調時計で期限を判定します。有効期限の起点は照合完了時ではなく開始時です。遅い照合をその後さらに 50/100ms 有効にしません。強制 check は monitor を待つ前に pending を宣言し、待機中は他の通常 check も古い成功を共有できません。強制 check 同士はまとめません。

cache hit 前にも現在要求と preflight resource のメモリ条件を確認します。既存 stop の signal／メモリ取消も毎回実行します。false は同じ窓で失効を保持します。例外は最初の呼出しへ送出し、その後は false を返して owner の HOLD 確定を可能にします。失敗を成功として再利用せず、次の advance は新しい窓から開始します。

これは公開と取消を新たに原子的にする設計ではありません。従来の公開直前検証・journal／MediaStore 手順と競合の意味は維持します。プロセス外 writer をメモリ通知だけで扱うこともありません。

## 遅延の解釈

無条件に「取消を 100ms 以内に検知／完了」とは保証しません。永続変更が durable read に見えることを前提として、適格 checkpoint の最大間隔 P、monitor 待ち W、実照合 I/O 時間 D が各々有界なら、おおむね T + P + W + D（T は設定間隔）が条件付き上限です。強制境界は T を待ちません。native 処理、スケジューリング、monitor の公平性、遅い I/O に無条件の上限はありません。取消の完了にはさらに cleanup が必要です。

計測では、永続変更後から次の適格 checkpoint の入口まで、そこから I/O 完了までを分離します。独立した実ファイルの mutation モデル（意図した 0/20ms I/O 遅延）と、実保存中の最大 monitor 待ち／照合時間を区別します。モデル値を製品保存中の実測取消遅延に読み替えません。

## 検証

JVM では 0ms の従来挙動、50/100ms 境界、遅い I/O、false／例外、単調時計、並行通常／強制照合、毎回のメモリ取消、cleanup、失敗後 HOLD を確認します。AVD は同一合成 4000×3000 DNG・同一設定で 0→50→100→100→50→0 の 6 保存を比較し、全 JPEG の byte hash と原本不変、PUBLISHED、resource 解放を確認します。

50/100ms の両方で、帯取消と同一世代 retry、JPEG 開始後取消、変更 recipe への明示的置換、旧 owner の失効、process death 後の取消／復旧を既存の実 factory 試験で確認します。確認済取消証明と明示 retry（#365）は帯取消・再試行・取消後の process death で確認します。UNKNOWN publication の回復承認（#377）は完成済み合成 JPEG の process-death fixture であり、GPU render 中断試験とは区別します。

## Pixel B 回の付帯計測案（未実行）

候補が B 回までに整い、本人が付帯計測を選んだ場合だけ実施します。同一 build の 0ms と選択候補を同一入力・recipe・表示条件で ABBA の 4 保存に揃え、保存操作・待機・冷却を分けます。見積りは操作 3～5 分、保存と冷却を含む予約枠 20～30 分です。これは実機の速度予測ではありません。

既存 dev.9 の工程別記録は、入力、recipe、build 種別、表示・温度条件が一致すると確認できない限り定性的比較だけです。AVD の相対短縮を Pixel に外挿しません。この作業では実機接続、インストール、配布、署名 release を行いません。
## AVD の比較結果（2026-11-19）

AOSP API 37、host GPU、2 vCPU / 4 GiB の専用 AVD 1 台。通常の GPU 保存 backend / authority / journal / MediaStore publisher を使用しました。合成 DNG は 12,000,000 pixel、強度 0.45、同一 bytes です。試験中に他の AVD／重い build を重複させていません。少数回の対応比較であり、有意差や実機速度を保証しません。

| 順序 | 間隔 ms | advance 秒 | authority 呼出 | 実照合 | 成功共有 | 強制実照合 | 最大 monitor 待ち ms | 最大実照合 ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 0 | 116.571 | 66091 | 66091 | 0 | — | — | 112.141 |
| 2 | 50 | 17.180 | 65496 | 810 | 64686 | 493 | 76.216 | 6.688 |
| 3 | 100 | 16.700 | 65556 | 599 | 64957 | 493 | 32.279 | 6.831 |
| 4 | 100 | 16.877 | 65524 | 598 | 64926 | 493 | 13.146 | 82.983 |
| 5 | 50 | 16.875 | 65540 | 692 | 64848 | 493 | 15.029 | 15.888 |
| 6 | 0 | 110.819 | 66028 | 66028 | 0 | — | — | 90.084 |

2 回の平均は 0ms 113.695 秒、50ms 17.028 秒（約85.0%短縮）、100ms 16.789 秒（約85.2%短縮）。50→100ms の差は約0.239秒に留まり、この試験だけで100msを優先しません。共有時の最大成功 age は50ms候補49.9999ms、100ms候補99.9809msでした。monitor／I/O の最大値は別の呼出しから得た値で、同一イベントの総和ではありません。0ms は I/O を直列化しないため monitor 待ちは比較対象外です。

全6件で PUBLISHED、原本不変、resource 解放、JPEG byte 一致を確認しました。合成原本は24,000,386 bytes（SHA-256 `526e90b5c77dd73e8a92a722054da971339a3bc0eeed74cbaf3eabf6f6243df7`）、JPEGは3,224,355 bytes（SHA-256 `56e08a0f59c365ce4b8382e76571567eedd16120752e469a7837def5ed66592f`）。JPEG hash は #475 の先行未計測対照とも同一です。今回0msには数値カウンターがあるため完全な無計測対照とは区別します。

カウンターの時計読み取り、短い同期、既存工程記録には負荷があります。0msでは元の並行 I/O を保ち、50/100msでは意図した single-flight の待ちを含みます。呼出回数にはポーリング頻度差も含むため、差分すべてを省略チェック数としません。既存工程 trace は継続していますが、今回新規カウンターは試行全体の集計であり、先行診断の工程別 stop / authoritative カウンターとは別です。

既存工程 trace の2回平均（秒）も確認しました。GPU_RENDER は 64.04→7.96→7.73、JPEG_ENCODE は 9.35→0.43→0.48、PUBLICATION は 16.04→0.83→0.81（順に0/50/100ms）。GPU の純粋な shader 実行時間ではなく、各工程の I/O・checkpoint を含む wall 時間です。内包・並行する細工程を全体時間へ単純加算しません。
### 永続 mutation の遅延モデル

実ファイル変更と fd.sync 完了後、1ms の試験 cadence でチェックしました。各組5回。下表はそれぞれの最大観測値です。0/20ms の意図した I/O 遅延は試験コードのみで、製品保存に sleep を追加していません。

| 間隔 ms | 注入 I/O ms | mutation→適格 checkpoint 最大 ms | checkpoint→照合完了 最大 ms |
|---|---:|---:|---:|
| 0 | 0 | 0.03 | 0.22 |
| 0 | 20 | 0.03 | 22.18 |
| 50 | 0 | 49.49 | 0.53 |
| 50 | 20 | 50.43 | 21.66 |
| 100 | 0 | 98.43 | 0.47 |
| 100 | 20 | 99.34 | 22.03 |

これは単一 file の失効モデルです。実保存に対して外部プロセスが request / consent を変更してから検知するまでの実測ではなく、競合 writer の全 interleaving の証明でもありません。元の照合 predicate を読む強制境界と、取消／置換／復旧の別試験を併せて評価します。
### 前回の停止状態（下記追補前）

lint / unit / debug app・test APK は成功（JVM1450件、failure/error0、既存skip12）。0/50/100ms各8件、計24件の帯取消・JPEG取消・明示retry・世代置換・factory失効試験は成功。ホスト起動引数不足による4件のfixture失敗と、合成UI用閲覧権限不足による1件の失敗は保全し、条件修正後の成功と区別しています。

50msのprocess-deathでは、確認済取消からの明示retry、JPEG開始後取消のHOLD復元が成功。一方、UNKNOWN-completeを既存UIから再照合する試験が「recheck result through product owner」待機で失敗しました。原因は未確定です。残りのUNKNOWN-partial、100ms process-deathは未実行。通常保存の高速化・JPEG一致を回復契約全体の合格とは扱いません。

この失敗の調査途中で停止。未検証の候補はpush／Draft PR化していません。実装・試験・私有ログは専用worktreeに保全しています。最終head CI／canonical reviewは未実施です。独立したコード・文書レビューのOKは、未解決の実走失敗を免除しません。
## UNKNOWN 再照合タイムアウトの切り分け追補

同じ製品APK・test APK・合成入力の作成条件・専用AVDで、間隔共有を無効にした0msと50msを各々新しいケースとして seed → host force-stop → PID不在確認 → cold UI resume しました。両方で旧試験の同じ待機タイムアウトを再現しました。失敗を削除／成功扱いせず保持しています。

| 旧試験の設定 | UI待機 | 元のpublication journal | 回復journal |
|---|---|---|---|
| 0ms | 同じ箇所でtimeout | UNKNOWN | PUBLISHED |
| 50ms | 同じ箇所でtimeout | UNKNOWN | PUBLISHED |

journalは読取りのみでchecksumを確認してphaseを抽出しました。旧試験の `PUBLISHED && action(...)` が待つ説明文「保存したJPEGを照合して開く」は、GPU製品UIの現行 `DevelopMessages` が渡す contentDescription「保存したJPEGを開く（開く前に照合します）」と不一致でした。旧説明文はlegacy側のfallbackとして残っています。通常製品の成功判定を変えたのではなく、試験が旧説明文を待っていたものです。

変更は `SaveRecoveryColdUiProbe` の期待contentDescriptionの1行だけです。PUBLISHED条件、intent不変、元のUNKNOWN履歴・既存行の保持、回復journalのphase／URI／target／fingerprint一致、資源解放、consent不存在、非自動FGSの検査は維持しています。独立した読取りレビューでも安全性判定の緩和ではないと確認しました。修正前後で製品APKのSHA-256は同一です。

追補の回帰matrixは0/50/100msそれぞれで、UNKNOWN complete/public/partial、承認済み次世代cursor、確認済取消から明示retry、JPEG開始後HOLD、最初のSTARTEDでprocess death、retryのSTARTEDでprocess deathを対象とします。意図したhost killで終わるseedと、通常のJUnit成功を分けて記録します。
### 追補の最終結果

| process-death → 復元の経路 | 0ms | 50ms | 100ms |
|---|---|---|---|
| UNKNOWN complete → UI再照合、既存pending行を公開 | 成功 | 成功 | 成功 |
| UNKNOWN public → UI再照合、既存公開行を確認 | 成功 | 成功 | 成功 |
| UNKNOWN partial → 不一致確認、明示承認で別世代のFGS保存 | 成功 | 成功 | 成功 |
| 承認済み次世代cursor → 非自動復元、明示FGS保存 | 成功 | 成功 | 成功 |
| 確認済取消 → 非自動復元、同一世代の明示retry | 成功 | 成功 | 成功 |
| JPEG開始後取消 → HOLD維持、retry／recipe置換拒否 | 成功 | 成功 | 成功 |
| 最初のSTARTED直後に終了 → HOLD維持 | 成功 | 成功 | 成功 |
| 明示retryのSTARTED直後に終了 → 古い取消証明を再利用せずHOLD | 成功 | 成功 | 成功 |

計24 protocol。hostが各caseのprocess不在を確認し、resume側でも別PIDをassert。通常JUnit完了はseed6件＋resume24件の計30成功で、armed状態で意図して終了したseed18件をJUnit成功には数えません。前回の0/50/100ms各8件の帯取消・JPEG取消・recipe置換・factory失効回帰24件とは別の今回実走です。製品APKは前回の6保存/JPEG一致・安全回帰に使用したものと同じhashです。

更新後の lint / unit / debug app・test APK task 成功（JVM1450件、failure/error0、既存skip12。変更のないtaskはUP-TO-DATE）。`git diff --check` 成功。独立レビューはセレクター修正を現行UI契約への追従と確認し、保存判定・承認条件の緩和はないとしています。AVDのboot/system_serverは前後同一、収集した終了時logcatでFATAL/ANR検出0。専用AVDは終了し、枠を留保しません。

先行の同一合成12MP比較で50ms平均17.028秒、100ms平均16.789秒（差0.239秒）。今回の予定回帰が通ったため、成功共有の期間が短い50msを第一候補とします。設定を採用したという意味ではなく、既定debug／releaseの0msは変更していません。メモリ世代案も実装していません。

実機の速度・温度・電力、24MPや全DNG形式、外部writerと任意I/O遅延を含む全interleavingは未実証です。50ms以内の無条件検知／取消完了保証はありません。今回の追補はhostの読み取りprobe、試験セレクター1行、文書だけで、候補の製品ソースは不変です。診断worktreeと旧失敗／対照／最終試験の私有証跡を保全し、push・Draft PR・公開・main・他レーン・Pixelは操作していません。
## Draft候補とPixel準備の境界

製品側差分は authority 成功照合の共有窓と強制境界への接続です。通常debug／releaseの既定は0ms。debug専用Gradle property `saveAuthorityIntervalMs=50/100` はbuild時定数で、通常UI・外部Intent・providerで変更できる設定ではありません。`configureForTest` はdebug内の一時overrideで、今回の呼出し元はemulator限定instrumentationです。物理端末へそのfixtureを流用したり、hardwareチェックを解除したりしません。試験セレクター修正は別の1行であり、製品の成功判定を変更しません。

### 同一APKの切替可否

- AVD合成試験: 同じapp/test APKのまま、試行ごとに0/50/100msを設定して実証済み。
- Pixel本人操作: 同一APKで0/50msを選ぶ入口は**未実装**。同一APK比較を準備済みとは扱いません。追加するなら専用診断入口、保存中の切替拒否、試行ごとの固定、別IDと通常保存authorityの整合、個人データを出力しない記録方法を別途実装・合成検証する必要があります。
- 現在のbuild時定数だけを使う場合: 0ms版と50ms版は再build・再installが必要。現在検証したapp APKは0ms定数です。製品と同じapplicationIdで一時debug署名なので、端末上の固定署名版を更新できるとは限りません。アンインストールやデータ削除を比較手順に含めません。現物をそのままPixelへ導入する段取りにはしません。
- #464: `dev.cinematic.p0.halfcompare` のプレビュー品質／FULL-HALF ABBA専用。JPEG保存はありません。#475の通常IDとは別なので理論上は併存可能ですが、#475の安全なPixel用構成がまだ必要です。同じ回でも#464の再buildやアンインストールで切り替える計画にはせず、各診断アプリを順番に使う構成が前提です。現状の2 APKで同一アプリ内の切替はできません。

### ローカル検証済みAPKの識別（配布・導入承認ではない）

| 項目 | #475保存候補 | #464プレビュー比較（読取り確認） |
|---|---|---|
| applicationId | dev.cinematic.p0 | dev.cinematic.p0.halfcompare |
| versionCode / versionName | 10 / 0.0.10-p05 | 10 / 0.0.10-p05 |
| ABI | arm64-v8a, x86_64 | arm64-v8a, x86_64 |
| APK SHA-256 | 9af9bb6bcc3675d89b48cdfc51720333e4235928731db578444e3526d50c84a9 | 3aeb48bc62cb80f45e4cefc2490554bdcbb25af3add8aad08bcdf7695db81e31 |

#475 test APK SHA-256: `ff223f1727840522abb21268b16dbe0b055e4ba9d11fde9a6550751781f22467`。
#475公開証明書SHA-256: `0e6fc2b5e12b4bcf5142592992f3b4879b44ca849c6c26c14464003ca0a35a8c`（一時debug。署名秘密ではありません）。
#464の確認対象sourceは `bfe06bfc6b9268503d2e30c581d1db172331c56b`。いずれもAPK添付・配布は行いません。CI生成APKは版情報が異なるため、このhashをCI成果物へ読み替えません。

### 本人承認後に使う最小計測案（入口準備後・未実行）

前面／背面はアプリのforeground／background状態を指します。同じ既知の端末内DNG、解像度、強度・調整値、画面状態、電源・thermal条件を揃え、0ms前面・0ms背面・50ms前面・50ms背面の計4保存を各1回行います。原本を転送せず、各回は新しい明示保存要求とし、既存PUBLISHEDの再照合を新しい保存時間へ混ぜません。

1. #464の処理と一時データ解放を終え、同時実行せずに保存診断へ切り替える。発熱条件が揃わなければその場で延長せず別回にする。
2. 前面回は保存完了までアプリを表示。背面回は両設定で同じ開始工程を確認してHomeへ移り、既存FGSで継続する。移行時点・移行に要した時間を数値で控える。計時開始／終端は同じ保存イベントとし、手動ストップウォッチ時間と内部工程時間を混ぜない。
3. PUBLISHED・資源解放・元DNG不変を確認し、時間、工程、間隔設定、前面／背面、thermalだけを匿名で控える。写真、名前、URI、端末識別情報、生ログは公開しない。失敗・中断・熱条件の差を黙って成功回へ置き換えない。

これは各条件1回の探索的比較です。AVD秒数や今回の合成JPEG byte一致をPixelの速度・全入力の画質保証にしません。50msは成功した永続再照合の共有期間であり、I/O・checkpoint・schedulerを含む検出／取消完了の無条件50ms保証ではありません。操作目安は3～5分＋保存待ち・冷却・導入準備で、待ち時間は実機未測定です。実機操作・配布・署名release・mergeはこのDraftの対象外です。