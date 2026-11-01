# P0 技術検証レポート

対象: Cinematic Camera / `dev.cinematic.p0` / CameraX **1.6.2固定**

判定対象リリース: [v0.0.2-dev.1](https://github.com/reitojike/cinematic-camera/releases/tag/v0.0.2-dev.1)

検証結果の整理日: 2026-11-01

**結論: RAW保存・PCでの読み込み、手動露出の実測反映、AUTO/MANUAL切替・背景復帰はユーザーから問題なしと報告された。P1判断案は GO_WITH_LIMITATIONS。**

この判断案はユーザー報告を根拠とする。エージェントによる実機測定や証拠原本の独立検証ではなく、P0全ACの無条件合格・P1着手承認を意味しない。

## 検証対象と証拠の範囲

最初の対象端末はGoogle Pixel 9a。配布物は次のとおり同定済みだが、実機に入ったAPKとの同一性は未確認。

| 項目 | 配布物の検証結果 |
|---|---|
| source main | `e166ddb4975e755ee91604448b9fc38429fc8b33` |
| versionName / versionCode | `v0.0.2-dev.1` / `2` |
| applicationId | `dev.cinematic.p0` |
| APK SHA-256 | `d7b93dcee70ab1684815df989a1a7f4a7b4d4f322ca3accdc4b57dbc527d8a7f` |
| 署名証明書SHA-256 | `13b532a690a29dfd48dff4f6df9c3b39c90b77ad6033271aee4f440e2e8143c6` |

実機結果は上記リリースの案内後のユーザー報告として扱う。端末名、Android version/build、camera ID、
使用APK hash、個別ファイル名/サイズ/hash、閲覧アプリ名/版、指定値とCaptureResult原本は未取得。
未提示の値は推定して埋めない。写真本体・未編集の実機ログは公開履歴へ保存しない。

## 最終検証サマリー

| 確認事項 | ユーザー報告による結果 | 独立確認・詳細証拠の範囲 |
|---|---|---|
| インストール・カメラ操作 | 成功 | 実機の端末/使用APKの同定は未完了 |
| JPEG撮影・保存先 | `Cinematic Camera`フォルダーへの保存を確認 | MediaStore URI、JPEGの閲覧アプリ名は未取得 |
| RAW撮影・ギャラリー表示 | DNGをギャラリーで表示できた | アプリ名/版不明。ギャラリー表示だけではRAW全体のdecodeを証明しない |
| DNGを5回以上保存しPCのRAWビューアで各ファイルを開く | 問題なしとの確認報告 | 個別ファイル名/サイズ/ID、PC viewer名/版、RAW全体のdecode経路は未取得 |
| ISO・露光時間をそれぞれ2値以上変更し実測値へ反映 | 問題なしとの確認報告 | 各指定値、CaptureResult値、誤差、frame durationの原本は未取得 |
| AUTO/MANUAL切替・バックグラウンド復帰 | 問題なしとの確認報告 | 試行回数、詳細手順、lifecycle/AE解除ログは未取得 |

「残りの確認もいずれも問題なし」との報告は、直前に案内したDNG 5回以上＋PC読み込み、
ISO/露光時間それぞれ2値以上の実測反映、AUTO/MANUAL切替・背景復帰に適用する。
能力一覧の採取、権限拒否/再許可、明示Stop/Start、RAW+JPEG同時出力、非対応/API失敗処理まで
包括的に確認済みとはしない。先の「露出・感度は操作できていそう」という報告と、今回の実測反映確認を区別する。

## ACごとの評価

`USER_REPORTED_PASS`は該当する機能確認の成功報告を指し、個別測定資料を独立検証した印ではない。

| AC | 評価 | 残る証拠・範囲 |
|---|---|---|
| AC1 許可・preview開始/停止/復帰 | 部分確認（ユーザー報告） | インストール・カメラ操作・背景復帰は報告あり。権限フロー全体、明示Stop/Start、端末/版の同定は未完了 |
| AC2 実機能力記録 | 未確認 | camera ID別のRAW/MANUAL_SENSOR、ISO/時間範囲、RAWサイズ、CameraX出力形式の実測一覧 |
| AC3 DNG 5回以上＋PC viewer | USER_REPORTED_PASS / 詳細証拠未取得 | 個別ファイル名/サイズ、camera ID、viewer名/版、RAW全体のdecode確認資料 |
| AC4 ISO/時間各2値以上の実測反映 | USER_REPORTED_PASS / 数値証拠未取得 | 指定値とCaptureResult原本、誤差・フレーム周期等の制約 |
| AC5 AUTO→MANUAL→AUTO | USER_REPORTED_PASS / 詳細ログ未取得 | 切替は問題なしとの報告。試行条件と停止/クラッシュ有無を裏付ける記録 |
| AC6 非対応/APIエラー | 実装済み・実機未確認 | 範囲外入力、非対応、API失敗時の非クラッシュ・UI/log・回復 |
| AC7 ビルド・実機証拠・採否報告 | 最終サマリー・判断案作成 / 証拠整備未完了 | 対象同定、能力/数値/ファイル資料、残るAC、P1採否の承認 |
| AC8 共有保存と閲覧 | ユーザー確認済み範囲あり | JPEGフォルダー保存とDNGギャラリー表示の報告。Googleフォトの利用や全viewer対応は未確認 |

実機非対応の報告・再現根拠は得られておらず、現時点でBLOCKED/端末非対応には分類しない。

## 実装・機械的検証

- Kotlin / Jetpack Compose、PreviewView、CAMERA permission、lifecycleへのbind、Start/Stopを実装。
- Camera2の各camera IDの能力と、CameraXのsupportedOutputFormatsを別々に記録。Camera2のRAWフラグだけでCameraX対応を推定しない。
- 対応形式を確認後にJPEG / RAW / RAW+JPEGを構築。AE_OFFとISO/露光時間は必要なCamera2Interopだけで指定し、
  AUTO復帰・再bind時はclearCaptureRequestOptionsの受付を待つ。指定/受付/CaptureResultは別に記録する。
- CameraXが管理するCameraDevice / CameraCaptureSessionは直接操作しない。固定絞りの操作UIはない。
- 新撮影はcache一時出力から再エンコードせずMediaStore Imagesへコピー。`Pictures/Cinematic Camera/`、
  `image/jpeg` / `image/x-adobe-dng`、IS_PENDINGを使用し、保存URI・byte数をUI/logへ記録。
- RAW+JPEGは両callbackと全stream close後に公開。失敗時は今回作ったrowを削除し、cleanup失敗も表示。
  公開はrowごとの順次updateであり、一括DB transactionではない。旧内部写真の移行/一括削除は行わない。
- 独立レビューで再bind時のInterop残存とID変更時の旧能力表示を修正済み。共有保存の独立レビューでも追加の具体的不具合なし。
- [自然main CI](https://github.com/reitojike/cinematic-camera/actions/runs/41321192520)：
  build成功、unit **11件・failure/error/skip 0**、lint **error 0 / warning 4**。
- [Release workflow](https://github.com/reitojike/cinematic-camera/actions/runs/41321498215)：
  lint/unit/build、署名検証、配布成功。公開URLからの実ダウンロードとAPKのpackage/version/hashを照合済み。

SDK 37.0 / Build Tools 36.0.0、JDK 25、Gradle 9.8.1、AGP 9.4.1を使用。
CameraXは全依存1.6.2でcompile済み、1.7系APIは使用していない。
詳細な機械的検証は[BUILD_VALIDATION.md](BUILD_VALIDATION.md)、配布・署名は[OPERATIONS.md](OPERATIONS.md)を参照。

## 既知の制約と未取得資料

- RAW画質・ノイズ・色・性能の定量評価はP0対象外。ギャラリーの埋め込みpreviewとRAW全体のdecodeを区別する。
  PC RAW viewer読み込みの成功報告はあるが、現時点の資料だけでdecode経路や全画素の有効性を独立検証できない。
- CameraXの受付成功はCaptureResult一致を保証しない。ユーザーは実測反映を確認したと報告しているが、
  quantization、frame duration、長時間露光、HAL/静止画処理の制約を判断できる数値資料は未取得。
- Camera2能力とCameraXのbind可能な出力組合せは別。RAW+JPEG両出力、未対応ID、
  MANUAL中の出力変更/Stop/Start、遅延callback、カメラ占有/API失敗の実機確認は未完了。
- AUTO/MANUAL切替・背景復帰で問題なしとの報告はあるが、全条件で競合がないとは断定しない。
  再現されたCameraX/Interop競合や端末非対応を示す具体的な失敗条件は未提示。
- 共有保存はAPI29以上。広範囲ストレージ権限は追加せず、DNG表示は閲覧アプリに依存する。
- 配布は一時debug署名。旧v0.0.1-dev.1や別CI候補とは署名が異なり上書きできない。
  固定鍵・Secrets・継続署名運用は未導入。今回の旧内部テスト写真の消失許可を将来へ一般化しない。
- エージェントは実機を操作していない。実機端末情報、ファイル原本、CaptureResult原本を推測・捏造しない。

## P1判断案と残る確認

**提案: GO_WITH_LIMITATIONS（採否承認前）**。

中核となるRAW素材の保存/PC読み込みと手動露出の実測反映、切替・復帰で成功報告があり、
CameraX中心の構成を直ちに破棄すべき実測の不具合は得られていない。
この範囲を根拠に、制約付きでP1の仕様検討へ進む案を提示する。

残る条件は、端末/Android build/camera ID/使用APKの同定、能力一覧と数値・DNG個別資料の補完、
AC1の未確認範囲とAC6のエラー処理、RAW+JPEG等の未確認条件の評価、およびP1採否の承認。
これらが未完了のため、P0全ACの無条件合格や制約なしGOにはしない。P1実装は未依頼であり開始しない。

AC3/4が今後端末仕様で不成立と判明した場合は、指定値/実測値・形式・エラー・再現条件を根拠に
BLOCKEDを記録し、制限付きで進むか最小のCamera2比較を行うかを相談する。
現時点で全面Camera2移行や独自回避を正当化する失敗根拠はない。

## 記録の管理

このファイルにはP0の最終検証結果・制約・採否根拠を残す。確定した振る舞いと受入条件は
[spec](../specs/001-p0-camera-probe/spec.md)、ACに対応する完了状態は
[tasks](../specs/001-p0-camera-probe/tasks.md)で管理する。
日々の作業状況、確認のやり取り、試行ログはIssue等で扱い、時系列の進捗追記をこのファイルへ積み上げない。
