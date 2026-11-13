# アプリ共通メッセージの仕様

本書を撮影・設定・診断・カメラロール・取り込み・現像の表示規則の正本とする。
根拠は [改修指示書](../../docs/DEVELOP_UI_REFACTOR_INSTRUCTIONS.md) 第1・2章と
[計画 v3](../../docs/cinematic-camera-roadmap-v3.md) レーン5。原文と後続の補足を混同しない。
共通部品を追加した段階では既存画面へ適用しない。画面ごとの切替は別PRで行う。

## 不変条件

撮影・録画・保存・取消・再試行・復旧・入力受付・プレビュー開閉・照合の挙動と状態遷移条件を変えない。
表示から canSave、GPU保存時のプレビュー停止、録画開始可否、ownerの寿命を決めない。
CPU経路・診断専用画面の整理や削除を同伴しない。FeedbackTone の廃止は撮影画面への適用時に行う。

## 枠と重複

| 枠 | 用途・長さ | 操作 |
| --- | --- | --- |
| CHIP | 状態の常時要約、8〜10字 | なし |
| CAPTION | 画像・プレビューに表示している内容、1行・省略可 | なし |
| CENTER | 画像がない理由・空状態、1〜2行 | 0〜2 |
| PROBLEM | 続行不能の失敗。CENTERを置換。見出し＋本文2文以内 | 主1＋副1まで |
| BANNER | 進行中・注意・続行可能な失敗。画面上部に1件、本文2文以内 | 主1＋副1まで |
| INLINE | シート・パネルのその項目への注意、1〜2文 | 0〜1 |
| TOAST | 完了・受付、1文。約4秒で消す | なし |
| DIALOG | 不可逆操作の確認・まとまった選択、見出し＋本文 | 確定＋閉じる |
| INFO | 利用者が開く静的説明。エラー・進行状態を入れない | 閉じる |

同じ事実を2枠に文として重ねない。例外は CHIP の短い要約と BANNER/PROBLEM の説明、および読み上げ用説明。
画像上へ重ねられるのは CHIP、CAPTION、CENTER/PROBLEM、BANNER のみ。操作パネルや単独の再試行ボタンは重ねない。
診断シートの数値・ログ、撮影画面のフォーカス点の要求/失敗はデータ・位置指標なので枠の対象外だが色の意味を守る。

## 優先順位と組み立て

同時に成立する通知は PROBLEM → FAILURE BANNER → 進行中/停止中 BANNER → WARNING BANNER → TOAST の順で1件を選ぶ。
PROBLEM は CENTER を置き換える。別の事実である空状態 CENTER は BANNER/TOAST と併存できる。
CHIP と CAPTION は常時残す。INLINE は項目局所の説明であり、選択済みの事実を再掲しない。
同順位の候補順は画面の純粋関数が決め、共通選択関数はその順を維持する。

`UiMessage.fact` は画面内で事実を識別する安定キー。文言が違っても同じ事実なら同じキーを渡す。
`screenMessages` は優先順位・重複排除だけを担当し、入力リストを書き換えない。
CAPTION に通知と同じ事実を渡すこと、CHIP に例外以外の重複を渡すことは組み立て側の誤りとして拒否する。
進行/停止中は `inProgress` で普通の WARNING と区別する。時計・処理・状態購読を共通関数に持ち込まない。

画面ごとの `captureMessages` / `libraryMessages` / `importMessages` / `developMessages` は後続PRで導入する純粋関数。
入力状態から文言・トーン・操作種別・操作可否を組み立てる。Composable はその結果を描画するだけ。
`MessageControl` のラベルと読み上げ説明も組み立て側が決める。`MessageAction` を既存処理へ割り当てるのは画面側。
TOAST の消去時刻、状態や資源、保存処理の所有権は既存側に残す。

## トーン

| CaptureChromeTone | トークン | 意味 |
| --- | --- | --- |
| FAILURE | Failed | 操作が必要なシステム側の失敗。撮影/保存/読込/復旧/GPU/メモリ/原本変化/権限/保存確認不能/保留 |
| WARNING | Amber | 進行中・未反映・注意・ユーザー取消後。熱・録画前のマイク権限・アクセス制限・顔保護なし |
| ACCEPTED | Accepted | 保存・受付・設定保持の完了 |
| NORMAL | Muted | 平常の補足 |
| RECORDING | RecordingText | 録画状態専用。メッセージには使用禁止 |

READY/DISABLED は既存の状態チップ用途だけに残す。PROBLEM は FAILURE、BANNER は FAILURE/WARNING、TOAST は ACCEPTED/NORMAL。
熱で録画を開始できない場合、録画を試みた直後だけ FAILURE とする。判定方法は PR-B で記録し、録画の受付条件を変えない。

## 文言と操作

- 「起きたこと。次にすること。」の2文以内、全角50字程度。PROBLEM 見出しは「〜できません」を目安に12字程度。
- 例外文・エラーコードを画面に出さない。詳細は既存ログ/診断へ残し、「詳細は診断へ」の操作を使う。
- owner、世代、admission、R7、ROI、Gate 等の内部語は使わない。受付・実測・適用・照合・原本・プレビューは使用可。
- GPU は端末能力/GPU障害の PROBLEM 内だけで使う。通常の表示は「プレビュー」と呼ぶ。
- 「明示的に」は使わない。「・」で文を省略する列挙は CHIP/CENTER に限る。
- 原本保持等の安心文は事実と一致する場合だけ PROBLEM の補足行に置く。Muted、Body、通常1行。
- 操作は「再試行」「再接続」「停止して復旧」「一覧に戻る」「プレビューを再開」「保存を取り消す」「もう一度確認」「通知を許可」「アクセスを許可」「選び直す」「詳細は診断へ」等の動作ラベル。
- 再試行/復旧操作を問題の枠内へ置く。説明とボタンの contentDescription/stateDescription を矛盾させない。

## 部品・アクセシビリティ

色と書体は CaptureDesign だけを使い、RectangleShape を使う。新しい色値・sp・MaterialTheme・Inkのalphaを部品に直書きしない。
Scrim は Ink 90%、ScrimLight は70%。Body は既存 Explanation。行内背景の8%もデザイントークンに集約する。

- CaptureTextAction: 最小44dp、Explanation、Text/指定トーン、無効時 Disabled。選択肢は既存 choice/control surface を維持。
- CaptureBanner: 左3dpのトーン線、Scrim（シートではPanel）、本文 Body/Text、操作は共通文字ボタン。
- CaptureProblem: Label/トーン色の見出し、Body/Text本文、Body/Muted補足、主副操作。
- CaptureCenter: Body/Muted、任意操作。CaptureInlineNote: 8%トーン背景とトーン文字。適用中の captureOutline は呼出側で維持。
- CaptureToast: Panel、1dp Line枠、下部配置と約4秒の消去は呼出側。
- CaptureDialog: 四角い Dialog/Panel/Border。INFO は閉じるだけ。既定 AlertDialog の角丸・M3書体は使わない。
- DevelopCaption: 1行・省略可。内容と更新中のトーンは呼出側で確定する。

BANNER/PROBLEM/TOAST に Polite liveRegion、PROBLEM見出しに heading を付ける。
全描画部品は modifier を受け、回転は呼出側の sensorRotation と既存横向き幅220dp/380dpで指定する。
本文2行＋操作1行を通常の目安とするが、拡大文字で安全説明や操作を切り捨てない。操作は狭幅で折り返し、ダイアログはスクロールする。

## 検証と適用境界

共通単体試験で全優先順位の組合せ・同順位・重複・常時枠・無効操作・トーン制約を確認する。
画面適用時には各状態の組み立て、読み上げ、回転、拡大文字、主要状態の screenshot と文言差を確認する。
Android手動回帰・最終headのrequired CI・独立レビューは別々の証拠とし、未実施を合格扱いしない。
PR-B〜E は本人の見た目承認前にマージしない。

### 後続ユーザー補足（原文とは別）

PR-C は #380/#381/#260 の表示マージ後。原文3.4(4)の保存状態表に #377 の「照合中」と「重複の可能性がある再保存確認」を追加予定。
再保存確認には CaptureDialog を使う。照合・再保存の条件を表示関数が新たに決めない。
PR-D は CPU 削除後、GPUリファクタリング前。PR-B/E は A 完了後に並行する。

## 表示契約違反の扱い

- デバッグ版と単体試験では、不正なメッセージの組合せを例外で検出する。
- 本番版では、組立時の不正候補、CAPTION/CHIP の許可されない重複、および誤った描画部品への入力を表示せず、理由コードだけをログに残す。本文・fact・URI は記録しない。
- 正常な組合せの優先順位と操作の有効条件は変えない。異なる事実を表す CENTER と BANNER は共存する。
