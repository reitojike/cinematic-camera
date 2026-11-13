# 改修指示：アプリ共通のメッセージ表示ルールと、画面表示の整理（GPU一本化前提）

対象リポジトリ：reitojike/cinematic-camera（基準 main `d86a442`、2026-11-13）

## 目的

1. エラー・注意・進行中・完了などのメッセージの見せ方を、**アプリ全体で共通のルール**にする。対象は撮影・設定・診断・カメラロール・取り込み・現像のすべての画面。
2. そのルールを部品と組み立て関数として実装し、画面ごとにばらばらな表示を置き換える。
3. v0.1.0 に向けて CPU 経路を退役させ、GPU 経路に一本化する前提で、現像まわりの表示を整理する。

---

## 0. 前提と守ること

- **表示だけを変える。** 次の挙動・契約は一切変えない。
  - 撮影・録画・保存・取消・再試行・入力受付・プレビュー開閉・照合の各契約
  - 状態遷移の条件。例：`canSave`、`gpuSaveMustPauseLoadedPreview`、録画開始の可否、復旧の条件
- **製品版の現像画面は GPU 経路だけを正とする。** `defaultDevelopEngine("debug"|"release") = GPU` のため、製品版の現像画面は `GpuPhotoRollEditor` → `PhotoRollDevelopView`（gpuSession あり）の組み合わせになる。
- **CPU 経路と診断専用の画面には手を入れない。** #290 で削除予定のため。新しい部品にも、これらへの分岐を足さない。
  - `PhotoRollScreen` の CPU 分岐、`PhotoRollComparisonImage`
  - PRIVATE_TRIAL / STAGE_CLEAR / DNG_TRIAL の分岐
  - 「2並列」スイッチ、`DevelopEngineSettingView`、`DiagnosticHubActivity`
- **見た目の決まりは既存どおり。**
  - 色と書体は `CaptureDesign` のトークンだけを使う。
  - 次を新たに書かない：`Color(0x…)`、`fontSize = NN.sp` の直書き、`MaterialTheme.typography`、`MaterialTheme.colorScheme`、`Ink.copy(alpha = …)` の直書き。
  - 角は `RectangleShape`。角丸は撮影画面の切替スイッチ（既存）だけ。
- **HTML デザイン資料のコードは製品コードへ写さない**（specs/003 の方針）。
- **検証：**
  - 単体試験を追加・更新する。
  - 文言を変えた場合は、既存の文言試験（`DevelopSavePresentationTest` など）も更新する。
  - docs/GPU_ENGINE.md の規定どおり、マージ前に Android で手動回帰を実施し、PR に記録する。

---

## 1. アプリ共通メッセージ規則（新設・最重要）

この章の内容を `specs/ui-messages/spec.md`（新設）に正本として記載する。`specs/capture`、`specs/develop`、`specs/library`、`specs/diagnostics` の各 spec からは、そこを参照する。

### 1.1 表示の枠（スロット）

アプリのメッセージは、すべて次の枠のどれかに出す。

**同じ事実を2つの枠に文として重複させない。** 認める例外は次の2つだけ。
- チップの短い要約と、バナーや問題カードでの説明を組み合わせる場合
- 読み上げ用の説明を付ける場合

| 枠 | 役割 | 長さ | 操作 | 主な使用画面 |
|---|---|---|---|---|
| **CHIP** 状態チップ | 常時表示の状態の要約 | 8〜10字 | なし | 撮影・現像のヘッダー |
| **CAPTION** キャプション | いま画像やプレビューに何を表示しているか | 1行・省略可 | なし | 現像 |
| **CENTER** 中央表示 | 画像やプレビューがない理由、または空の状態 | 1〜2行 | 0〜2 | 撮影（接続待ち）、現像（準備中・保存中）、カメラロール（空） |
| **PROBLEM** 問題カード | 先へ進めない失敗。CENTER の位置に置き換えて出す | 見出し＋2文以内 | 主1＋副1まで | 撮影（復旧が必要）、現像（読み込み不可・GPU不可・保存失敗）、カメラロール（一覧取得不可） |
| **BANNER** 状態バナー | 進行中の処理、注意、先へ進める失敗。画面上部に同時に1件だけ | 2文以内 | 主1＋副1まで | 撮影（熱・マイク・撮影失敗）、カメラロール（アクセス制限・保存中）、現像（保存中・取消後）、取り込み |
| **INLINE** 行内注記 | シートやパネルの中で、その位置の項目に関する注意 | 1〜2文 | 0〜1 | 設定（処理中・適用中）、ダイアログ内 |
| **TOAST** 一時通知 | 完了・受付の知らせ。約4秒で自動的に消える | 1文 | なし | 撮影（保存しました・受付完了）、現像（調整設定を保持） |
| **DIALOG** 確認ダイアログ | 取り消せない操作の確認、またはまとまった選択 | 見出し＋本文 | 確定＋閉じる | 撮影制御のリセット、DNG取り込み |
| **INFO** 情報ダイアログ | ユーザーが開いたときだけ出す静的な説明 | 自由 | 閉じる | 現像の ⓘ |

**補足：**
- INFO に、エラーや進行中の状態を入れない。
- 画像やプレビューの上に重ねてよいのは、CHIP、CAPTION、CENTER/PROBLEM、BANNER だけ。操作パネルや単独の再試行ボタンを重ねない。
- **診断シートの数値・ログの色分け**（受付・実測の値を Amber や Failed で塗るもの）はメッセージではなくデータ表示なので、枠の対象外とする。ただし色の意味は 1.3 に従う。
- **撮影画面のフォーカス点の表示**（「要求」「失敗」）は、その場の位置を示す指標なので、枠の対象外とする。色だけ 1.3 に従う。

### 1.2 優先順位

同じ画面で同時に複数の事象があるときは、上のものだけを出し、下のものは出さない。CHIP と CAPTION は常に出す。

1. PROBLEM（先へ進めない失敗）
2. FAILURE トーンの BANNER（先へ進めるが、失敗が起きた）
3. 進行中・停止中の BANNER（保存中、録画停止処理中、プレビュー停止中 など）
4. WARNING トーンの BANNER（熱、マイク権限、アクセス制限、通知未許可 など）
5. TOAST

**既存の `captureMessage()` との違い：** 現状は熱やマイクの警告を撮影失敗より優先している。これを上の順に揃える。ただし熱で録画を開始できないときは、録画を試みた直後だけ FAILURE として扱う。どう判定するかは実装者に任せ、PR に書く。

### 1.3 トーン（色）

メッセージのトーンは `CaptureChromeTone` に一本化する。`FeedbackTone`（INFORMATION / WARNING / FAILURE）は廃止して置き換える。

| トーン | 色 | 使う場面 |
|---|---|---|
| FAILURE | Failed | **ユーザーの操作が必要なシステム側の失敗だけ**：撮影・保存の失敗、復旧が必要、GPU不可、メモリ不足、読み込み不可、原本変化、権限なし、保存確認不能、保存保留 |
| WARNING | Amber | 進行中・注意・未反映：保存中、適用中、準備中、更新中、熱、マイク権限なし（録画前）、アクセス制限、**ユーザー自身が取り消した後の状態**（赤にしない）、顔保護なし |
| ACCEPTED | Accepted | 完了：保存しました、受付完了、調整設定を保持した |
| NORMAL | Muted | 平常の補足 |
| RECORDING | RecordingText | 録画中の表示だけ。メッセージには使わない |

### 1.4 文言の書き方

**文の形**
- 「何が起きたか。次に何をすればよいか。」の2文以内。目安は全角50字以内。
- PROBLEM の見出しは「〜できません」の形で12字程度。例：「撮影状態を確認できません」「読み込めません」「GPU を使えません」「JPEGを保存できませんでした」。

**例外メッセージを出さない**
- `${e.message}`、`CameraX camera error 3` などの例外メッセージやエラーコードを、画面の文言に含めない。
- 詳細はログや診断（ProbeLog・診断シート）に残し、画面には利用者向けの文と「詳細は診断へ」の操作を出す。
- 該当例：`CameraProbe` の `lastFailure = "$operation: ${e.message}"` など。

**用語**
- 内部用語は出さない：owner、世代、admission、R7、ROI、Gate など。
- 製品で定義済みの語は使ってよい：受付・実測・適用・照合・原本・プレビュー。
- 「GPU」は、端末能力や GPU 障害の PROBLEM の中だけで使う。GPU 一本化後は「GPU プレビュー」を「プレビュー」とする。

**言い回しと定型**
- 「明示的に」は使わない。
- 「・」で語を並べて文を省略する書き方（例：「接続待ち・プレビュー停止中」）は CHIP と CENTER だけで使う。BANNER と PROBLEM では文で書く。
- 「原本は変更していません」「撮影データは保持しています」などの安心させる一文は、各文に混ぜない。PROBLEM の補足行（Muted・1行）として定型で付ける。

**操作ラベル**
- 動詞で終える：`再試行` `再接続` `停止して復旧` `一覧に戻る` `プレビューを再開` `保存を取り消す` `もう一度確認` `通知を許可` `アクセスを許可` `選び直す` `詳細は診断へ`。
- 再試行や復旧のボタンは、その問題を出している枠の中に置く。画面の別の場所に単独で置かない。

### 1.5 読み上げと回転

- BANNER と PROBLEM は `liveRegion = Polite`。PROBLEM の見出しには heading の役割を付ける。
- TOAST は `Polite` で、表示中に読み上げが終わる長さにする。
- ボタンの `contentDescription` と `stateDescription` は、枠に出している文と矛盾させない。
- 撮影画面では、すべての枠の部品を `sensorRotation()` と横向き時の幅制限（現行の 220dp / 380dp）に対応させる。部品の引数に `modifier` を受け取り、回転は呼び出し側で付ける。

### 1.6 構造（画面ごとに1か所で組み立てる）

**共通の型**（`CaptureMessages.kt`）
```kotlin
enum class MessageSlot { CHIP, CAPTION, CENTER, PROBLEM, BANNER, INLINE, TOAST }
enum class MessageAction { RETRY, RECONNECT, STOP_AND_RECOVER, BACK_TO_LIST, RESUME_PREVIEW,
    CANCEL_SAVE, RECHECK_SAVE, ALLOW_NOTIFICATIONS, GRANT_ACCESS, CHOOSE_AGAIN, OPEN_DIAGNOSTICS, ... }
data class UiMessage(val slot: MessageSlot, val tone: CaptureChromeTone, val body: String,
    val title: String? = null, val note: String? = null,
    val primary: MessageAction? = null, val secondary: MessageAction? = null)
data class ScreenMessages(val center: UiMessage?, val banner: UiMessage?, val toast: UiMessage?, ...)
```
各フィールドは例。必要に応じて調整してよい。

**画面ごとの組み立て関数**
- 撮影：既存の `captureMessage()` を置き換える `captureMessages(...)`
- カメラロール：`libraryMessages(...)`
- 取り込み：`importMessages(...)`
- 現像：`developMessages(...)`

いずれも純粋関数とし、優先順位（1.2）とトーン（1.3）を単体試験で網羅する。

**文言源の集約**
- 現像側：`gpuPhotoRollProblemMessage`、`gpuFailurePolicy(...).reason/title`、`GpuSaveFailureReason.description`、`developSaveLabel`、`ForegroundSaveNotice` の文言
- 撮影側：`captureMessage`、復旧案内の直書き文言

**Composable の役割**
- 描画だけを担当する。文言・トーン・操作の種類を自分で決めない。
- `MessageAction` から実際の処理への割り当ては、各画面の呼び出し側で既存の関数に結びつける（挙動は変えない）。

---

## 2. 共通部品（新設・置き換え）

`CaptureMessages.kt`（新設）と `CaptureDesign.kt` に置き、全画面で使う。

| 部品 | 内容 |
|---|---|
| `CaptureDesign.Scrim` | 画像やプレビューの上に敷く半透明背景。`Ink.copy(alpha = .9f)` を1つのトークンにする。`.7f / .8f / .85f / .9f` の直書き（CaptureScreen、PhotoRollDevelopView、GpuPhotoRollImage ほか）をすべて置き換える。濃さを2段階にしたい場合は `Scrim` と `ScrimLight` までにする |
| `CaptureDesign.Body` | メッセージ本文の書体。既存の `Explanation` を流用してよい。`fontSize = 12.sp / 13.sp` の直書きを置き換える |
| `CaptureTextAction` | 文字ボタン。`RectangleShape`、最小 44dp、文字は Explanation、色は Text（`tone` 指定時はそのトーン色）、無効時は Disabled。全画面の `TextButton` をこれに置き換える（後述の除外を除く） |
| `CaptureBanner(message)` | BANNER の描画。左に 3dp のトーン色の縦線、背景は Scrim（シート内では Panel）、本文は Body（Text）、操作は `CaptureTextAction`。高さの上限は約2行＋操作1行 |
| `CaptureProblem(message)` | PROBLEM の描画。見出しは Label（トーン色）、本文は Body（Text）、補足は Body（Muted）、操作は主＋副 |
| `CaptureCenter(message)` | CENTER の描画。1〜2行、Body（Muted）、操作は任意 |
| `CaptureInlineNote(message)` | INLINE の描画。現行の設定シートにある「Amber 8% の背景＋Amber の文字」の形式を正式な部品にし、トーンで色を変える。適用中は `captureOutline(pending = true)` との組み合わせを維持する |
| `CaptureToast(message)` | TOAST の描画。下部、Panel 背景、1dp の Line 枠。約4秒で消える処理は呼び出し側に置く（撮影画面の現行の `delay(4000)` を共通化してよい） |
| `CaptureDialog` | DIALOG と INFO の描画。現行の `CaptureResetDialog`（四角の `Dialog`、Panel 背景、Border 枠）を汎用化する。`AlertDialog` の既定の角丸（28dp）と M3 の書体は使わない |
| `DevelopCaption` | 現像画面のキャプション。`GpuPhotoRollImage` と `PhotoRollComparisonImage` で重複している実装を1つにする（GPU側から使う形でよい） |

**`CaptureTextAction` に置き換えない例外：** 選択肢そのものを担うボタンは、既存の `captureChoiceSurface` / `captureControlSurface` を使う。例：撮影のキー、設定の選択肢、診断のタブ、カメラロールの絞り込み。

---

## 3. 画面ごとの適用

### 3.1 撮影画面

| 現状 | 変更後 |
|---|---|
| 「接続待ち・プレビュー停止中」（中央、Ink 70%、13sp） | CENTER（NORMAL） |
| 「設定状態を確認・復旧してください」＋［再接続］（中央、Ink 80%、Failed 13sp、既定の TextButton） | PROBLEM：見出し「設定状態を確認できません」、本文「再接続して設定を確認してください。」［再接続］ |
| 「撮影状態が不明です。停止して接続をやり直してください」＋［停止・復旧］（中央、Ink 90%、12sp） | PROBLEM：見出し「撮影状態を確認できません」、本文「停止して接続をやり直してください。」補足「撮影データは保持しています」（事実と合う場合だけ）［停止して復旧］ |
| `captureMessage()` のバナー（上部、Ink 80%、12sp、優先順位が熱 > マイク > 失敗） | `captureMessages()` に置き換え、優先順位を 1.2 に揃える。失敗は BANNER（FAILURE）、熱とマイクは BANNER（WARNING） |
| `notice`（4秒で消える。「保存しました」「回転: …」「設定API受付完了・実測は診断で確認」 など） | TOAST。完了系は ACCEPTED、回転の案内は NORMAL |
| `lastFailure` / `recordingFailure` の例外メッセージ（`$operation: ${e.message}`、`CameraX camera error N` など） | 利用者向けの文に置き換え、［詳細は診断へ］を付ける。原文は ProbeLog と診断シートに残す（記録内容は変えない） |
| 「共有画像・動画の保存にはAndroid 10以降が必要です」 | minSdk 33 のため到達しない分岐。削除してよい |
| STILL パネルの［停止・復旧］（既定の TextButton、Failed） | `CaptureTextAction(tone = FAILURE)`。パネル内の状態文は INLINE |

- 横向き時の配置（中央寄せと幅の制限）は現行の値を維持する。

### 3.2 設定シート・診断シート

- **設定シートの注記を INLINE にする。** 対象は「撮影・録画・設定処理中です。設定変更は完了後に行えます。」と「適用中：…」。見た目は現状とほぼ同じで、部品化が主目的。
- **［撮影制御をリセット…］（Amber 12sp の直書き）：** 文字を `CaptureDesign` のスタイルにする。ボタンの外枠は既存の OutlinedButton の形を維持してよい。
- **閉じる［×］（22sp の直書き）：** `CaptureTextAction` にするか、`CaptureDesign` にアイコン用のスタイルを追加する。
- **撮影制御のリセット確認：** `CaptureDialog` を使って作り直す（見た目は現状維持）。
- **診断シート：** 数値やログはデータ表示のため対象外。ただし `fontSize` の直書きは、`CaptureDesign` のスタイルへ置き換えられる範囲で置き換える。表示内容の削除は #290 の作業範囲なので、ここではしない。

### 3.3 カメラロール・取り込み

**カメラロール**
- `notice`（Failed の行内表示）→ BANNER（FAILURE）
- アクセス制限の説明と［選び直す］［設定］→ BANNER（WARNING）。操作は主＋副の2つまで。
- 空の状態（`CameraRollEmptyState`）→ CENTER（NORMAL）。操作は［アクセスを許可］［Filesで開く］。
- `listing.message`（Muted）→ 内容に応じて INLINE か CENTER。
- 保存中の通知帯（`ForegroundSaveNotice`）→ BANNER。文言は 3.4(6) と同じにする。
  - 実行中の保存を拾う処理（`ForegroundExports` の購読）は、小さな状態取得関数に分けて残す。
  - `ForegroundSaveNotice` という Composable 自体は廃止する。

**外部DNG取り込み**（`DngImportPanel`）
- **入口の位置：** 見出し行（「‹ 撮影 / カメラロール」）より上に置かれている入口を外す。一覧の下にある操作行（現「Filesから DNG…」）にまとめ、違いが分かるラベルを並べる。空の状態の画面にも同じ2つを並べる。
  - `Filesで開く`：コピーせずに直接現像する
  - `取り込む`：共有フォルダへコピーしてから現像する
- **ダイアログ：** `CaptureDialog` を使う。
  - 説明文は INLINE（NORMAL）
  - `state.message` と `preparationNotice` は INLINE（トーンは進行中なら WARNING、失敗なら FAILURE、完了なら ACCEPTED）
  - 進捗バーは Amber / Line の色
  - 操作は `CaptureTextAction`
- **一覧の項目：** 「取り込み 1」のような番号だけの表示をやめ、状態ラベル（`完了` / `中断` / `権限の解放待ち`）を付ける。ファイル名を出すかどうかは `docs/DNG_IMPORT_CONTRACT.md` に従う。
  - 取り込み記録にファイル名を持たない契約なら、記録には足さない。
  - 表示時に共有URIから表示名を取得できる場合だけ表示する。

### 3.4 現像画面（GPU 経路）

**(1) ⓘ の説明文の誤りを直す**
- `GpuPhotoRollEditor.kt` の `info` にある「GPU の診断用プレビューです。通常版の既定は CPU です。…」を削除する。
- INFO（`CaptureDialog`）の内容は次の3つにする。
  - 原本名
  - 「原本は読み取り専用です。コピー・上書き・公開は行いません。」
  - 試験品質の注意（顔保護なし・暫定色・24MP以下の対応DNGのみ）
- INFO に `message` を入れない。
- 実態と合わない記述も直す。
  - コード内コメント：`GpuPhotoRollEditor.kt` 冒頭の「opt-in GPU path」
  - docs/GPU_ENGINE.md 314 行付近の「通常debug/releaseはCPU」

**(2) 保存中の画像領域**
- 現状：`exportPaused` のときに中央へ「原本の確認待ち」が出る。
- 変更後：CENTER（WARNING）に次を出す。
  - 保存中：「保存中 · プレビューを止めています」
  - 保存後の再開待ち：「保存のためプレビューを止めました」（NORMAL）
- 最後のフレームを静止画として残す案は採らない（保存中にメモリを空けるという契約に反するため）。

**(3) 上下で重複するメッセージをなくす**
- `statusOverlay`（上）と下部の `message` に、同じ内容が二重に出ている。これを `developMessages()` に寄せ、保存関連は BANNER の1件だけにする。
- 下部の「message を TextButton で出して ⓘ を開く」仕組みは廃止する。

**(4) 保存状態の表示**
`developSaveLabel` と保存ボタンを次の表に揃える。PRIVATE_TRIAL の分岐は持ち込まない。

| 状態 | CHIP | トーン | 枠と文言 | 保存ボタン | 読み上げ |
|---|---|---|---|---|---|
| 準備未確認（null） | 保存準備中 | NORMAL | なし | ↓ 無効 | JPEGを保存（準備中） |
| 調整中 | 調整中 | NORMAL | なし | ↓ 無効 | 調整を閉じると保存できます |
| READY | 未保存 | READY | なし | ↓ | JPEGを保存 |
| SAVING / pending | 保存中 | WARNING | BANNER「JPEGを保存中です。アプリを離れても続きます。」［保存を取り消す］ | ↓ 破線 | 保存中 |
| PUBLISHED | 保存済み | ACCEPTED | 保存直後に TOAST「JPEGを保存しました」 | ↗ | 保存したJPEGを開く（開く前に照合します） |
| CANCELLED | 保存取消 | **WARNING** | BANNER「保存を取り消しました。同じ設定で再試行するか、プレビューに戻って調整できます。」［プレビューを再開］ | ↻ | 同じ設定で保存を再試行 |
| UNCONFIRMED | 保存未確認 | FAILURE | BANNER「保存できたか確認できません。もう一度確認してください。別のJPEGは作りません。」 | ↻ | 保存結果をもう一度確認 |
| GPU_FAILED | 保存失敗 | FAILURE | PROBLEM：見出し「JPEGを保存できませんでした」、本文は `GpuSaveFailureReason.description`＋「再試行してください。」［プレビューを再開］ | ↻ | 同じ設定で保存を再試行 |
| HOLD | 保存保留 | FAILURE | PROBLEM：見出し「保存を停止しました」、本文「保存先の状態を確認できません。保存済みとは扱いません。」 | 無効 | 保存保留中 |

- 「下の再試行ボタンで、同じ保存を再試行します」という案内文は削除する。
- 保存ボタンを押せる条件（`canSave`）は変えない。

**(5) 配置**
上から次の順にする。
1. ヘッダー（CHIP）
2. 画像領域（CAPTION／BANNER／CENTER または PROBLEM）
3. 調整パネル（`TrialStrengthEditorPane`）
4. パラメータキー行
5. 保存行

- 調整パネルを画像に重ねない。
- 下部に重ねている帯（`Column(Modifier.align(BottomCenter)…)`）と、そこに置いた BLOCKED / PAUSED の再試行ボタン・更新中の文言は廃止する。それぞれ PROBLEM、CENTER、CAPTION へ移す。
- 画像領域の高さは、調整パネルの開閉に合わせて変わってよい。高さを固定したい場合はパネル分を予約する方式にし、PR にどちらにしたかを書く。
- 小さい画面（`captureNeedsFullScroll` が真）では、高さ 260dp の画像の中に BANNER と CAPTION 以外を重ねない。
- `statusOverlay` 引数は廃止する。

**(6) 保存の通知（保存中の帯）**
文言の目安：
- 保存中：「JPEGを保存中です。アプリを離れても続きます。」［保存を取り消す］
- 停止処理中：「保存を止めています。」
- 停止中：「保存の終了を確認できないため停止しています。次の保存は始めません。」（FAILURE）
- 通知未許可：「保存の通知はオフです。取消はこの画面から行えます。」［通知を許可］

**(7) キャプションと比較操作**
- キャプション（`DevelopCaption`）：
  - 通常は「現像結果」、基準表示のときは「基準表示（撮影時WB・調整なし）」。
  - 表示が要求値に追いついていないとき（`presented != requested`、初回表示前を含む）は「更新中」を後ろに付け、Amber にする。デザインの「未反映はアンバー」の決まりを GPU 経路で復活させる。
- 比較操作：
  - 表示と比較の切替を、比較を保つ主な操作にする。
  - 画像の長押しは、押している間だけ基準表示にする補助操作として残す。キャプションの「長押しで基準表示」という案内は外し、INFO に1行で記載する。
  - パラメータキー行の「比較」キーは、既存の判断どおり変更しない。

---

## 4. 進め方（推奨する PR の分け方）

CPU 削除（#290）の PR とぶつかりにくい順に並べる。

1. **PR-A 規則と部品：**
   - `specs/ui-messages/spec.md` の新設と、各 spec からの参照
   - `CaptureMessages.kt`（型と 2章の部品）、`CaptureDesign.Scrim` などのトークン
   - 単体試験
   - この PR では画面をまだ切り替えない
2. **PR-B 撮影・設定：** 3.1 と 3.2。`captureMessage()` を `captureMessages()` に置き換え、`FeedbackTone` を廃止する。
3. **PR-C 現像（GPU）の表示：** 3.4 の (1)〜(4) と (6)。`developMessages()` を追加する。`PhotoRollDevelopView` は、引数の追加と描画の差し替えにとどめる。
4. **PR-D 現像の配置：** 3.4 の (5) と (7)。CPU 削除が先にマージされていれば、CPU 用の引数（preview、comparison、comparisonLabel、stage、mode の分岐）もここで外す。CPU 削除がまだなら、CPU 分岐の見た目は崩れてもよい（診断用ビルド限定のため）。その旨を PR に書く。
5. **PR-E カメラロールと取り込み：** 3.3。

**各 PR に書くこと**
- 変更前後のスクリーンショット（主要な状態ごと）
  - 撮影：接続待ち、復旧が必要、熱、マイク、撮影失敗、TOAST
  - 現像：準備中、表示中、調整中、保存中、取消後、保存失敗、GPU不可
  - カメラロール：アクセス制限、空、取り込み中
- 手動回帰の結果
- 文言の変更一覧（変更前 → 変更後）

---

## 5. 完了条件

- [ ] `specs/ui-messages/spec.md` に共通規則があり、各領域の spec から参照されている
- [ ] 撮影・設定・カメラロール・取り込み・現像（GPU）で、同じ内容のメッセージが2か所に出る状態がない
- [ ] 上記の画面に次が残っていない（CPU 分岐・診断専用コード・診断シートのデータ表示を除く）
  - `MaterialTheme.typography`、`MaterialTheme.colorScheme`
  - 既定形状の `TextButton`、既定の `AlertDialog`
  - メッセージ用途の `fontSize` と `Ink.copy(alpha = …)` の直書き
- [ ] 撮影画面の画面上の文言に、例外メッセージやエラーコードが出ない（記録は診断に残っている）
- [ ] `captureMessages()` が、失敗 > 進行中 > 警告 > 通知 の優先順位になっている
- [ ] 現像画面で保存中に「原本の確認待ち」が出ない。「診断用」「既定は CPU」の文言が出ない。保存取消が WARNING で出る
- [ ] 各画面の組み立て関数の単体試験が、優先順位・トーン・保存状態の全表（3.4(4)）・PROBLEM の各種類を網羅している
- [ ] 撮影・保存・取消・再試行・復旧の挙動に関する既存の試験が、文言の更新以外の変更なしで通る
