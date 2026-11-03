# Feature Specification: ライブ操作の最新意図と主値の可読性

**Feature Branch**: `fix/023-landscape-control-width`
**Created**: 2026-11-03
**Status**: Draft
**Input**: [Issue #47追加スコープ](https://github.com/reitojike/cinematic-camera/issues/47#issuecomment-6593446735)、関連[Issue #37](https://github.com/reitojike/cinematic-camera/issues/37)。本書は確定した振る舞いと受入条件を扱い、実機の原因確定・解消済み宣言は行わない。

現行の追加ACは[Issue #47 横一列・ショートカット・歯車](https://github.com/reitojike/cinematic-camera/issues/47#issuecomment-6595243467)と[Issue #37 安全な手動再接続](https://github.com/reitojike/cinematic-camera/issues/37#issuecomment-6595251915)。6キーは等幅の横一列とし、旧横scroll/狭幅2段方針を置換する。AUTO観測の鮮度、EV/WB/MFの連続入力・最新intent・即時preview契約は維持する。

表示と触覚の追加ACは[Issue #47 v19 UI](https://github.com/reitojike/cinematic-camera/issues/47#issuecomment-6595777813)。SSの可視値は後続の明示指示を優先し、短秒/長秒とも単位なし、読み上げには秒を保持する。既存の一般的なユーザー報告をv19個別項目の実機合格に読み替えない。

現行の追加は[Issue #47 v20 共通見出し・触覚](https://github.com/reitojike/cinematic-camera/issues/47#issuecomment-6596333540)。5目盛りの見出し配置と受付触覚の種類だけを変更し、追加PO指示なしに無関係な表示/動作を変えない。

[Issue #61](https://github.com/reitojike/cinematic-camera/issues/61)は目盛り外のプレビューtapを閉じる操作へ統一する。設定/診断パネル自体は横持ちでも回転しない現行動作を明示的に維持する。

[Issue #47 v22 横持ち項目幅](https://github.com/reitojike/cinematic-camera/issues/47#issuecomment-6599683392)は横持ち6キーの見かけ幅を広い項目側へ揃える限定補正とする。縦持ちのユーザー確認は報告範囲だけの証拠であり、横持ちや他の実機ACへ包括的に広げない。

## User Scenarios & Testing

### User Story 1 - AUTOの主数値を途切れず読む (Priority: P1)

撮影者はAUTOの正常なISO/SS実測を読み、近似説明に主数値を隠されず撮影する。
**Why this priority**: 現在の露出を読めることが撮影判断の前提となる。
**Independent Test**: 正常観測が継続するAUTO sessionで表示更新中にISO/SSをtap/dragし、欠落混在・期限・session変更と区別する。最長/最短数値とLOOKを含む6キーを縦横・拡大文字で同時に確認する。
**Acceptance Scenarios**:

1. **Given** 同じsessionで正常なプレビュー実測が更新中、**When** 欠落・不正・静止画観測や表示更新間隔を挟む、**Then** 軸別の正常な最新実測を保持し、不要な空白を出さない。元の取得時刻から2000ms以内だけ有効とする。
2. **Given** 実測が真に古い、sessionが変わる、切断または候補非対応、**When** 主値を見る、**Then** 旧値・架空値を表示せず「—」。欠落による時刻延長や旧session値の復活を許さない。2000msちょうどは有効、超過は無効とする。
3. **Given** 最長/最短ISO・SS主値と近似説明、**When** 縦/左右横持ちとfontScale 1.0/1.3/2.0で見る、**Then** 近似説明を主数値から分離し、主数値・分母・単位の欠落、ellipsis、重なりを0件にする。ISO/SS/EV/WB/FOCUS/LOOKの6キーを等幅で横一列に同時表示し、SHUTTERの視覚ラベルはSS、読み上げは元の意味を保持する。横スクロールや2段化を使わず、LOOKと操作領域を切らない。プレビュー寸法を変更しない。設定入口は歯車アイコンで示す。
4. **Given** AUTOで正常実測が連続更新しカメラの制御受付が可能、**When** ISO/SSをtapして目盛りを開き、実測更新をまたいでdrag/tap/矢印/読み上げで選択する、**Then** 表示更新で押下・gesture・選択状態を破棄せず、目盛りの開閉や段変更を成立させる。正常な実測更新だけを理由にキーを無効化しない。選択後は最新MANUAL targetを表示する。
5. **Given** AUTO実測が真に古い/不正/非対応、またはsession/能力が変わる、**When** ISO/SSを操作する、**Then** 値の鮮度不足とカメラの操作不能を区別して理由を示す。旧値や丸めた表示から設定を捏造しない。session/能力変更時のgesture無効化は維持する。
6. **Given** 撮影画面、**When** SS主値とSTILL/CINE切替を確認する、**Then** 見出しはSS、短秒/長秒とも可視値に単位を付けず（例1/100、1、2）、読み上げでは秒の意味を保持する。スイッチから現在の撮影modeを視覚と読み上げで区別できる。busy/録画/撮影専有中の既存切替制限を維持する。
7. **Given** シャッターを押す、**When** アプリが静止画の新規撮影、CINE開始または停止を同期的に受け付けた、**Then** 標準の軽い触覚応答を1回だけ要求する。拒否・受付遷移のない連打・保存中・mode切替では要求しない。触覚はAPI受付/保存成功/実録画の証明ではなく、端末/ユーザーの触覚設定を尊重する。
8. **Given** ISO/SS/EV/WB/FOCUSの目盛り、**When** 縦/左右横持ちで開く、**Then** 見出しは中央title・右close・左actionで共通化し、WBの左actionが空でもtitleを中央に保つ。ISO/SSには通常の補足説明を重ねず、実測待ちの場合はtitleに待機を示し読み上げで理由を伝える。AUTO/RESET/FOCUS AUTOと閉じるの既存guardを維持する。
9. **Given** 設定/診断を表示せず5項目のいずれかの目盛りを開いている、**When** 目盛り外のプレビュー領域を単一tapする、**Then** tapを消費して目盛りを閉じ、同じtapで背後のAE/AF測光や別操作を起動しない。送信済みintent・選択値・camera受付を取り消さない。目盛り内drag/tap/close、Back、設定/診断、録画停止、既存の撮影専有guardと読み上げを維持する。目盛りを閉じた状態の既存測光は保つ。
10. **Given** 左右横持ちの6キー、**When** 数値/候補/タグを含む幅を確認する、**Then** 広い項目側を基準に同じ見かけ幅へ揃え、領域に収まる条件では広い寸法を使う。短い領域では共通上限を適用し、可読性/収まりの限界を明示する。縦持ち・tap領域/読み上げ・固定配置/preview寸法・回転と撮影/設定/測光guardを維持する。

### User Story 2 - EV/WBを待たずに連続選択する (Priority: P1)

撮影者はEV/WBを連続操作し、送信待ちでも次の段を選び、最新の選択を即座に確認する。
**Why this priority**: 1段ごとの応答待ちは撮影中の調整を妨げる。
**Independent Test**: EV 0→+1の連続段選択、WB往復、応答遅延とISO/SS同時操作を行い、選択と要求・受付・実測を別に照合する。
**Acceptance Scenarios**:

1. **Given** EVまたはWBがpending、**When** 次の段をdrag/tap/矢印/読み上げで選ぶ、**Then** 操作を受け付け最新intentを即表示する。pendingのみを理由にUIを無効化しない。EVは対応範囲の絶対選択とし、MANUALへ未対応EVを追加しない。
2. **Given** 複数の新しい選択、**When** 遅い中間ACKが届く、**Then** 最新intentを巻き戻さず、最新値への要求を継続する。古い中間値を無制限に蓄積しない。指を離すまで送信を保留しない。
3. **Given** EV/WBとISO/SSを同時に変更、**When** プレビュー補償・要求・受付が更新される、**Then** 最新の各項目を相互に上書きせず、撮影targetと補償値を区別する。失敗/timeoutは最新intentを保持して不明/失敗を示し、成功表示や無限再試行を行わない。明示reset/rebindで復旧する。
4. **Given** EV対応のAUTOで0を含む合法範囲、**When** EV目盛りのRESETショートカットを1回押す、**Then** EVだけを絶対index 0へ要求し、他の最新intentを保つ。全制御resetとは区別する。pending中も使え、MANUAL/非対応/古いsession等では送信しない。
5. **Given** 設定API応答またはpreview確認がUNKNOWNで、前面・接続中・待機かつ撮影専有なし、**When** 表示された手動再接続を押す、**Then** 現在のcamera/modeで安全に再bindする。押下時もepochと安全条件を再検査し、古い応答を新sessionの成功にしない。撮影中/撮影専有/録画中/送信中にはこの操作を許さず、撮影中の既存停止・復旧を維持する。自動retryやモード切替を要求せず、再接続をセンサー停止原因の解消証明にしない。

### User Story 3 - MFを動かしながら合焦を探す (Priority: P1)

撮影者はMFを往復操作してプレビューの変化から合焦位置を探す。
**Why this priority**: 毎回の適用操作を要求すると合焦探索が成立しない。
**Independent Test**: MFを近→遠→近へdragし、途中要求、最後の正確な選択、AF/MF/∞、失敗と復帰を確認する。
**Acceptance Scenarios**:

1. **Given** MF対応端末、**When** MFをdragし続ける、**Then** drag中も最新値への要求を継続しプレビューへ反映する。適用/取消UIを置かず、指離しだけをcommit条件にしない。pending中も次段を選択できる。
2. **Given** 正確な既存MF値、**When** 目盛りを開閉・往復・AF/MF/∞切替、**Then** 表示丸めを送信値に使わず、報告最大値の100等分をUI候補として扱う。開いた時のexact anchorは同じsession/能力範囲内で安定させる。非対応/不正値は送信しない。
3. **Given** 送信中の背景化・session変更・能力変更・失敗、**When** 復帰し古いACKが到着、**Then** 旧要求が現在値を変更せず、最新の論理設定を対応能力の範囲で復旧する。ISO/SS/EV/WBの新しい意図と矛盾せず、操作不能なlockを残さない。
4. **Given** MF選択中（近距離/∞を含む）、**When** FOCUS目盛りのAUTOショートカットを1回押す、**Then** MF overrideだけを解除してAFを要求する。露出AUTOとは区別し、目盛りを端まで移動する必要はなく、他の最新intentを保つ。押下時の能力/epoch/readinessを再検査し、API受付を合焦確認にしない。

### Edge Cases

ISO/SS片軸欠落、観測時刻の逆転/未来、不正値、PREVIEW/VIDEO_RECORDとSTILL_CAPTURE/UNKNOWN、2000ms境界、表示clockとsample更新の境界、AUTO高頻度更新中の押下/drag継続、旧epoch、background/resume、連続同値・端点・方向反転、EV非対応mode、WB非対応preset、MF最大値変更・exact anchor・∞・AF切替、遅延/失敗/timeoutと旧ACK、同時ISO/SS補償、拡大文字・狭幅・LOOK端部の欠落を扱う。

## Requirements

### Functional Requirements

- **FR-001**: AUTOのISO/SSは現sessionの軸別正常プレビュー実測を元取得時刻から2000ms以内だけ表示し、正常更新が続く間に不要な空白を出さない。欠落で延命せず、旧session/期限超過/非対応は偽表示しない。
- **FR-002**: 近似説明を主数値から分離し、縦横・fontScale 1.0/1.3/2.0・候補の最長/最短主値で数値/単位の欠落をなくす。ISO/SS/EV/WB/FOCUS/LOOKの6キーを等幅の横一列に同時表示し、横scroll/2段化を使わない。SHUTTERは視覚上SSと略記し、設定入口は歯車とする。操作領域/読み上げと固定プレビュー寸法を維持する。
- **FR-003**: EV/WB/MFはpending中も有効な連続入力を受け付け、その最新intentを即表示する。共通detentと全入力経路を維持し、実段変更時だけ標準hapticを要求する。端点/同値/非対応では要求しない。
- **FR-004**: 操作中も最新intentを反映し、指離しcommitだけにしない。送信待ちは有限個の最新候補へ集約し、遅い中間ACKや旧session応答で新intentを巻き戻さない。
- **FR-005**: MFを即時プレビュー操作とし適用/取消UIを廃止する。AF/MF/∞、正確な選択値、報告能力の検査とsession/能力ごとの安定anchorを維持する。
- **FR-006**: ISO/SS/EV/WB/MFの最新論理設定を相互に保持し、撮影target/プレビュー補償/要求受付/実測を混同しない。失敗/timeoutは最新intentを保持してUNKNOWN等で明示し、無限再試行しない。reset/rebindと背景復帰で能力・世代を再検査し、旧応答と残留lockを無効化する。
- **FR-007**: RAW比較、他repo、鍵/Secrets、依存、回転実装を変更しない。原画像・個人パス・会話転載を含めない。Draft/review/CIまでとしmerge/配布は別承認とする。機械確認と実機ACを分ける。
- **FR-008**: ISO/SSの操作readinessと実測の鮮度を分離する。正常実測の表示更新でキー/目盛り/gestureの同一性を変えず、更新中の入力を受け付ける。鮮度不足やsession/能力変更は既存の安全境界で拒否し、正常更新そのものを操作不能の理由にしない。表示丸めを送信せず、有効な元実測からMANUAL targetを選ぶ。
- **FR-009**: EVはRESETで0、FOCUSはAUTOでMF解除/AFへ各1回で要求できる独立ショートカットを設ける。全制御reset/露出AUTOと区別し、最新全体intentへ変更項目だけを反映する。pending連続入力・能力/epoch/readiness検査を共用し、EVの0は対応範囲/正のstepでのみ有効とする。MANUALへEVを追加しない。
- **FR-010**: 設定応答/preview確認UNKNOWNで、現epoch・前面・接続中・待機・撮影専有なしの場合に限り同camera/modeの手動再接続を可能にする。表示時と押下時の両方で検査し、背景化/epoch変更/送信中/録画中/撮影中を拒否する。最新intentの既存復旧scopeと旧token無効化を維持する。撮影中の停止/復旧と、API受付/preview実測/保存結果の分離を変更しない。
- **FR-011**: SS見出しと短秒/長秒の可視値には単位を付けず、読み上げには秒を保持する。STILL/CINEは現在値を示すスイッチとし既存の切替制限を維持する。シャッターの新規撮影/録画開始/停止をアプリが同期受付した時だけ画面key用途の標準hapticを1回要求し、拒否/受付遷移のない連打/保存/mode切替では要求しない。目盛りの段変更用触覚と区別し、端末/ユーザー設定を尊重する。実際の振動強度を保証せず、API受付や保存/録画成功と混同しない。
- **FR-012**: ISO/SS/EV/WB/FOCUS目盛りの見出しを中央title・右close・左actionで共用し、actionの有無でtitleをずらさない。ISO/SSの通常補足説明を外し、待機titleと読み上げ理由を保つ。既存action/close・epoch/能力/鮮度/pendingのguard、操作領域、読み上げを維持する。追加PO指示のない差分を作らない。
- **FR-013**: 5目盛り外のプレビュー単一tapは閉じる専用として消費し、同時にAE/AF測光・別操作を発火しない。UIを閉じても送信済みintent/選択値/camera受付を保持する。対象はプレビュー領域に限定し、上位の状態バー/別キー/シャッター/設定入口は既存経路を優先する。目盛り内操作・close/Back・設定/診断・録画停止と既存の状態保護を維持し、撮影中のtap機能を拡張しない。回転設定を変更せず、設定/診断パネル自体は各向きで無回転を維持する。
- **FR-014**: 横持ち6キーは回転後の占有領域を区別して測定し、広い項目側を基準に見かけ幅を共通化する。領域に収まらない場合は共通上限と既存の文字fitを使い、狭小領域/高文字倍率の限界を明示する。縦持ちの寸法方針、等weightの物理tap領域、値/ラベルの意味、読み上げ、固定配置/preview寸法と既存guardは変更しない。

### Key Entities

最新の論理設定intent、送信中の要求と世代token、有限の最新待機intent、API受付履歴、軸別正常実測slot、独立した入力readiness/鮮度、安定したgesture/目盛りidentity、能力/epochとMF exact anchor、主数値と独立した近似説明、6キーの共通配置。

## Success Criteria

### Measurable Outcomes

- **SC-001**: 正常更新中の不要空白0件、期限超過/旧session/非対応の偽主値0件。指定した縦横・文字倍率・値端点で主数値/単位の欠落0件。
- **SC-002**: EV 0→+1、WB連続/往復、MF往復の各操作でpending中の次段拒否0件、中間ACKによる最新intent巻戻り0件。MF drag中に指離し前の要求を確認し、適用/取消操作0回で探索できる。
- **SC-003**: 遅延・失敗・timeout・背景復帰・epoch/能力変更・同時ISO/SS変更で、無限queue/無限retry、旧token採用、残留lock、他項目intent消失を0件にする。要求受付を実測や合焦の証明に数えない。
- **SC-004**: 最新headのunit/lint/build・公開情報検査・必須CIと独立/canonical review・未解決会話0件を確認する。実描画の可読性と実機AUTO/連続操作/合焦探索は別ACとし未実施を合格にしない。
- **SC-005**: AUTO正常更新中にISO/SSの押下・目盛り表示・連続段変更が成立し、更新だけに起因するgesture破棄/入力不能0件。縦/左右横持ちと指定文字倍率で6キーの欠落/スクロールを要する隠れ0件。検証したviewport/文字倍率と実描画結果を明示し、未検証の画面幅や倍率へ保証を広げない。
- **SC-006**: EV遠端→0、近距離MF/∞→AFが各1回の操作で要求され、古いACKでの巻戻り・他項目intent消失・無効時送信0件。6キーは常に等幅横一列、SS略記/歯車/読み上げを維持する。手動再接続の許可/拒否行列と旧epoch拒否が一致し、撮影専有中の再bind0件とする。純粋テスト・callsite確認・独立レビューを実機原因確定や復旧成功の代用にしない。
- **SC-007**: SSの短秒/長秒で可視単位なし・読み上げ秒あり、STILL/CINEの現在値、RESET=EV0/AUTO=AFの表示と操作が一致する。静止画新規受付・CINE開始/停止だけで触覚要求1回、それ以外0回とする。純粋状態テスト/接続確認と、v19の実描画・触覚体感・実機受入を分ける。
- **SC-008**: 5目盛り×縦/左右横持ちでtitle中央・close右・action左が一致し、ISO/SSの通常補足説明0件、待機理由/既存guard/読み上げの欠落0件とする。受付時と目盛り段変更の標準haptic種別を接続で確認し、端末の強度・体感とv20実描画は実機確認まで未確認とする。
- **SC-009**: 5項目×縦/左右横、pending・録画/保存・設定/診断との重なりで、外側tapの測光/操作貫通・送信済みintent取消0件。目盛り内操作/停止/Back/読み上げの後退と設定/診断パネルの新たな回転0件とする。純粋routing/接続確認と実描画・実機受入を分離する。
- **SC-010**: 縦/左右横・6キー・値/候補/タグ・文字倍率・短い表示領域で、横持ちの共通寸法と上限/fitが同じ方針を使い、縦持ちとtouch/a11y/guardの後退0件。純粋な模擬測定値のテストを実寸描画の証明にせず、初回構成/回転途中も含む実描画と横持ち実機結果は確認まで未確認とする。

## Assumptions

本Draft採用時は[012](../012-v15-capture-feedback/spec.md) US3/FR-005/SC-003と[009](../009-capture-ui-states/spec.md) US2-5/FR-003/SC-003の旧MF draft適用/取消、EV/WB pending入力禁止を本書へ置換する。[004 FR-007](../004-live-exposure/spec.md)のUNKNOWN後の同値選択再送もFR-006の最新intent保持・明示reset/rebind復旧へ置換し、UNKNOWN中の入力を自動retryの起点にしない。同時1 API要求というtransport制約はUI入力禁止を意味しない。AUTOの期限・表示専用・MANUAL最新target・RAW固定targetは維持し、正常観測の集約と可読性だけを補正する。その他の既存契約は維持し、merge前にmainの契約が変更済みとは扱わない。

文字倍率1.0/1.3/2.0は受入検証の対象であり、機械的な幅計算の合格だけで実描画合格とはしない。数値を縮小する設計では固定最小文字サイズの有無と検証viewportの実績/未確認をplanへ明示し、成立を確認できない条件は未確認として残す。旧横スクロール方針の実装完了はFR-002/008とSC-005の達成を意味しない。
