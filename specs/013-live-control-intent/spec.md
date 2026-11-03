# Feature Specification: ライブ操作の最新意図と主値の可読性

**Feature Branch**: `fix/018-auto-display-input`
**Created**: 2026-11-03
**Status**: Draft
**Input**: [Issue #47追加スコープ](https://github.com/reitojike/cinematic-camera/issues/47#issuecomment-6593446735)、関連[Issue #37](https://github.com/reitojike/cinematic-camera/issues/37)。本書は確定した振る舞いと受入条件を扱い、実機の原因確定・解消済み宣言は行わない。

現行の追加ACは[Issue #47 AUTO表示・入力・6キー](https://github.com/reitojike/cinematic-camera/issues/47#issuecomment-6594720497)。横スクロールによりキーを隠す旧可読性方針を、6キー同時表示へ置換する。EV/WB/MFの連続入力・最新intent・即時preview契約は維持する。

## User Scenarios & Testing

### User Story 1 - AUTOの主数値を途切れず読む (Priority: P1)

撮影者はAUTOの正常なISO/SS実測を読み、近似説明に主数値を隠されず撮影する。
**Why this priority**: 現在の露出を読めることが撮影判断の前提となる。
**Independent Test**: 正常観測が継続するAUTO sessionで表示更新中にISO/SSをtap/dragし、欠落混在・期限・session変更と区別する。最長/最短数値とLOOKを含む6キーを縦横・拡大文字で同時に確認する。
**Acceptance Scenarios**:

1. **Given** 同じsessionで正常なプレビュー実測が更新中、**When** 欠落・不正・静止画観測や表示更新間隔を挟む、**Then** 軸別の正常な最新実測を保持し、不要な空白を出さない。元の取得時刻から2000ms以内だけ有効とする。
2. **Given** 実測が真に古い、sessionが変わる、切断または候補非対応、**When** 主値を見る、**Then** 旧値・架空値を表示せず「—」。欠落による時刻延長や旧session値の復活を許さない。2000msちょうどは有効、超過は無効とする。
3. **Given** 最長/最短ISO・SS主値と近似説明、**When** 縦/左右横持ちとfontScale 1.0/1.3/2.0で見る、**Then** 近似説明を主数値から分離し、主数値・分母・単位の欠落、ellipsis、重なりを0件にする。ISO/SS/EV/WB/FOCUS/LOOKの6キーは同時に表示され、LOOKを含めラベルや操作領域を切らず、横スクロールなしで到達できる。プレビュー寸法を変更しない。
4. **Given** AUTOで正常実測が連続更新しカメラの制御受付が可能、**When** ISO/SSをtapして目盛りを開き、実測更新をまたいでdrag/tap/矢印/読み上げで選択する、**Then** 表示更新で押下・gesture・選択状態を破棄せず、目盛りの開閉や段変更を成立させる。正常な実測更新だけを理由にキーを無効化しない。選択後は最新MANUAL targetを表示する。
5. **Given** AUTO実測が真に古い/不正/非対応、またはsession/能力が変わる、**When** ISO/SSを操作する、**Then** 値の鮮度不足とカメラの操作不能を区別して理由を示す。旧値や丸めた表示から設定を捏造しない。session/能力変更時のgesture無効化は維持する。

### User Story 2 - EV/WBを待たずに連続選択する (Priority: P1)

撮影者はEV/WBを連続操作し、送信待ちでも次の段を選び、最新の選択を即座に確認する。
**Why this priority**: 1段ごとの応答待ちは撮影中の調整を妨げる。
**Independent Test**: EV 0→+1の連続段選択、WB往復、応答遅延とISO/SS同時操作を行い、選択と要求・受付・実測を別に照合する。
**Acceptance Scenarios**:

1. **Given** EVまたはWBがpending、**When** 次の段をdrag/tap/矢印/読み上げで選ぶ、**Then** 操作を受け付け最新intentを即表示する。pendingのみを理由にUIを無効化しない。EVは対応範囲の絶対選択とし、MANUALへ未対応EVを追加しない。
2. **Given** 複数の新しい選択、**When** 遅い中間ACKが届く、**Then** 最新intentを巻き戻さず、最新値への要求を継続する。古い中間値を無制限に蓄積しない。指を離すまで送信を保留しない。
3. **Given** EV/WBとISO/SSを同時に変更、**When** プレビュー補償・要求・受付が更新される、**Then** 最新の各項目を相互に上書きせず、撮影targetと補償値を区別する。失敗/timeoutは最新intentを保持して不明/失敗を示し、成功表示や無限再試行を行わない。明示reset/rebindで復旧する。

### User Story 3 - MFを動かしながら合焦を探す (Priority: P1)

撮影者はMFを往復操作してプレビューの変化から合焦位置を探す。
**Why this priority**: 毎回の適用操作を要求すると合焦探索が成立しない。
**Independent Test**: MFを近→遠→近へdragし、途中要求、最後の正確な選択、AF/MF/∞、失敗と復帰を確認する。
**Acceptance Scenarios**:

1. **Given** MF対応端末、**When** MFをdragし続ける、**Then** drag中も最新値への要求を継続しプレビューへ反映する。適用/取消UIを置かず、指離しだけをcommit条件にしない。pending中も次段を選択できる。
2. **Given** 正確な既存MF値、**When** 目盛りを開閉・往復・AF/MF/∞切替、**Then** 表示丸めを送信値に使わず、報告最大値の100等分をUI候補として扱う。開いた時のexact anchorは同じsession/能力範囲内で安定させる。非対応/不正値は送信しない。
3. **Given** 送信中の背景化・session変更・能力変更・失敗、**When** 復帰し古いACKが到着、**Then** 旧要求が現在値を変更せず、最新の論理設定を対応能力の範囲で復旧する。ISO/SS/EV/WBの新しい意図と矛盾せず、操作不能なlockを残さない。

### Edge Cases

ISO/SS片軸欠落、観測時刻の逆転/未来、不正値、PREVIEW/VIDEO_RECORDとSTILL_CAPTURE/UNKNOWN、2000ms境界、表示clockとsample更新の境界、AUTO高頻度更新中の押下/drag継続、旧epoch、background/resume、連続同値・端点・方向反転、EV非対応mode、WB非対応preset、MF最大値変更・exact anchor・∞・AF切替、遅延/失敗/timeoutと旧ACK、同時ISO/SS補償、拡大文字・狭幅・LOOK端部の欠落を扱う。

## Requirements

### Functional Requirements

- **FR-001**: AUTOのISO/SSは現sessionの軸別正常プレビュー実測を元取得時刻から2000ms以内だけ表示し、正常更新が続く間に不要な空白を出さない。欠落で延命せず、旧session/期限超過/非対応は偽表示しない。
- **FR-002**: 近似説明を主数値から分離し、縦横・fontScale 1.0/1.3/2.0・候補の最長/最短主値で数値/単位の欠落をなくす。ISO/SS/EV/WB/FOCUS/LOOKの6キーを同時表示し、キーのラベルと操作領域を切らず、横スクロールを必要としない。固定プレビュー寸法と既存操作入口を維持する。
- **FR-003**: EV/WB/MFはpending中も有効な連続入力を受け付け、その最新intentを即表示する。共通detentと全入力経路を維持し、実段変更時だけ標準hapticを要求する。端点/同値/非対応では要求しない。
- **FR-004**: 操作中も最新intentを反映し、指離しcommitだけにしない。送信待ちは有限個の最新候補へ集約し、遅い中間ACKや旧session応答で新intentを巻き戻さない。
- **FR-005**: MFを即時プレビュー操作とし適用/取消UIを廃止する。AF/MF/∞、正確な選択値、報告能力の検査とsession/能力ごとの安定anchorを維持する。
- **FR-006**: ISO/SS/EV/WB/MFの最新論理設定を相互に保持し、撮影target/プレビュー補償/要求受付/実測を混同しない。失敗/timeoutは最新intentを保持してUNKNOWN等で明示し、無限再試行しない。reset/rebindと背景復帰で能力・世代を再検査し、旧応答と残留lockを無効化する。
- **FR-007**: RAW比較、他repo、鍵/Secrets、依存、回転実装を変更しない。原画像・個人パス・会話転載を含めない。Draft/review/CIまでとしmerge/配布は別承認とする。機械確認と実機ACを分ける。
- **FR-008**: ISO/SSの操作readinessと実測の鮮度を分離する。正常実測の表示更新でキー/目盛り/gestureの同一性を変えず、更新中の入力を受け付ける。鮮度不足やsession/能力変更は既存の安全境界で拒否し、正常更新そのものを操作不能の理由にしない。表示丸めを送信せず、有効な元実測からMANUAL targetを選ぶ。

### Key Entities

最新の論理設定intent、送信中の要求と世代token、有限の最新待機intent、API受付履歴、軸別正常実測slot、独立した入力readiness/鮮度、安定したgesture/目盛りidentity、能力/epochとMF exact anchor、主数値と独立した近似説明、6キーの共通配置。

## Success Criteria

### Measurable Outcomes

- **SC-001**: 正常更新中の不要空白0件、期限超過/旧session/非対応の偽主値0件。指定した縦横・文字倍率・値端点で主数値/単位の欠落0件。
- **SC-002**: EV 0→+1、WB連続/往復、MF往復の各操作でpending中の次段拒否0件、中間ACKによる最新intent巻戻り0件。MF drag中に指離し前の要求を確認し、適用/取消操作0回で探索できる。
- **SC-003**: 遅延・失敗・timeout・背景復帰・epoch/能力変更・同時ISO/SS変更で、無限queue/無限retry、旧token採用、残留lock、他項目intent消失を0件にする。要求受付を実測や合焦の証明に数えない。
- **SC-004**: 最新headのunit/lint/build・公開情報検査・必須CIと独立/canonical review・未解決会話0件を確認する。実描画の可読性と実機AUTO/連続操作/合焦探索は別ACとし未実施を合格にしない。
- **SC-005**: AUTO正常更新中にISO/SSの押下・目盛り表示・連続段変更が成立し、更新だけに起因するgesture破棄/入力不能0件。縦/左右横持ちと指定文字倍率で6キーの欠落/スクロールを要する隠れ0件。検証したviewport/文字倍率と実描画結果を明示し、未検証の画面幅や倍率へ保証を広げない。

## Assumptions

本Draft採用時は[012](../012-v15-capture-feedback/spec.md) US3/FR-005/SC-003と[009](../009-capture-ui-states/spec.md) US2-5/FR-003/SC-003の旧MF draft適用/取消、EV/WB pending入力禁止を本書へ置換する。[004 FR-007](../004-live-exposure/spec.md)のUNKNOWN後の同値選択再送もFR-006の最新intent保持・明示reset/rebind復旧へ置換し、UNKNOWN中の入力を自動retryの起点にしない。同時1 API要求というtransport制約はUI入力禁止を意味しない。AUTOの期限・表示専用・MANUAL最新target・RAW固定targetは維持し、正常観測の集約と可読性だけを補正する。その他の既存契約は維持し、merge前にmainの契約が変更済みとは扱わない。

文字倍率1.0/1.3/2.0は受入検証の対象であり、機械的な幅計算の合格だけで実描画合格とはしない。数値を縮小する設計では固定最小文字サイズの有無と検証viewportの実績/未確認をplanへ明示し、成立を確認できない条件は未確認として残す。旧横スクロール方針の実装完了はFR-002/008とSC-005の達成を意味しない。
