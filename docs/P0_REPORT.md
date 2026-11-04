# P0 技術検証レポート

対象: Cinematic Camera / `dev.cinematic.p0` / CameraX **1.6.2固定**

判定対象リリース: [v0.0.2-dev.1](https://github.com/reitojike/cinematic-camera/releases/tag/v0.0.2-dev.1)

検証結果の整理日: 2026-11-01（追加確認を2026-11-04に反映）

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

初期の実機結果は上記リリースの案内後のユーザー報告として扱う。当時の端末/版と個別ファイルの同定、
閲覧アプリ名/版、指定値とCaptureResult原本は未取得。下記の後続診断は別の版であり、初期報告のAPK同一性や個別証拠を補完するものではない。
未提示の値は推定して埋めない。写真本体・未編集の実機ログは公開履歴へ保存しない。

後続の診断レポートではPixel 9a、Android 17（SDK 37、build `CP3A.261005.005`）、アプリ自己報告 `v0.0.26-dev.1` / code 26、CameraX 1.6.2、背面camera ID 0 / STILLを確認した。APKハッシュによる端末上のアプリ同一性は未確認。

| カメラ能力 | Camera2 characteristics | CameraX supportedOutputFormats |
|---|---|---|
| ID 0 背面 / FULL | RAW・MANUAL_SENSOR、ISO 29～7276、露光 56,968～16,000,002,085 ns、RAW 4000×3000 / 4000×2256 / 2000×1500 | JPEG / RAW / RAW+JPEG |
| ID 1 前面 / FULL | RAW・MANUAL_SENSOR、ISO 45～5714、露光 12,428～1,000,007,525 ns、RAW 4208×3072 / 2104×1536 | JPEG / RAW / RAW+JPEG |

能力値はAPI報告であり、全範囲・全カメラの撮影成功やアプリ上で選択可能な範囲を意味しない。同じ診断ではID 0・AUTO・RAW+JPEGの1回でDNGとJPEGの保存完了を確認した。画像品質やDNG全体のdecodeはこの記録だけから判定しない。

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
後続版でユーザーは明示Preview停止→開始・撮影復帰と、MANUAL中の保存形式変更・保存に問題なしと報告した。権限なし表示とAndroid設定からの許可後復帰も確認された。繰り返し拒否後に許可ダイアログが再表示されない症状はAndroidの制限と整合するが、拒否フラグ未取得のため原因未確定であり、ユーザーは現状維持に同意した。非対応/API失敗処理まで包括的に確認済みとはしない。先の「露出・感度は操作できていそう」という報告と、実測反映確認を区別する。

## ACごとの評価

`USER_REPORTED_PASS`は該当する機能確認の成功報告を指し、個別測定資料を独立検証した印ではない。

| AC | 評価 | 残る証拠・範囲 |
|---|---|---|
| AC1 許可・preview開始/停止/復帰 | 部分確認（ユーザー報告） | 明示Stop/Start、背景復帰、権限なし表示と設定から許可後の復帰は確認。繰り返し拒否時の原因は未確定、現状維持を了承 |
| AC2 実機能力記録 | 後続版の診断要約を確認 | ID別Camera2能力とCameraX出力形式は上記。全範囲の撮影成功を意味しない |
| AC3 DNG 5回以上＋PC viewer | USER_REPORTED_PASS / 詳細証拠未取得 | 個別ファイル名/サイズ、camera ID、viewer名/版、RAW全体のdecode確認資料 |
| AC4 ISO/時間各2値以上の実測反映 | USER_REPORTED_PASS / 数値証拠未取得 | 指定値とCaptureResult原本、誤差・フレーム周期等の制約 |
| AC5 AUTO→MANUAL→AUTO | USER_REPORTED_PASS / 詳細ログ未取得 | 切替は問題なしとの報告。試行条件と停止/クラッシュ有無を裏付ける記録 |
| AC6 非対応/APIエラー | 実装済み・実機未確認 | 範囲外入力、非対応、取得/保存/制御API失敗時の非クラッシュ・UI/log・回復は未検証 |
| AC7 ビルド・実機証拠・採否報告 | 結果・制約・判断案を記録 | 過去の個別数値/ファイル資料は未取得。P1判断は制約付き |
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
- Camera2能力とCameraXのbind可能な出力組合せは別。後続版のID 0でRAW+JPEG保存1回の診断記録、MANUAL中の出力変更とStop/Startのユーザー確認はある。未対応ID、遅延callback、カメラ占有/API失敗は未検証。
- 権限の繰り返し拒否後にダイアログが出ない症状は[Androidの仕様](https://developer.android.com/about/versions/11/privacy/permissions#dialog-visibility)と整合するが原因未確定。設定で許可を戻した後の復帰はユーザー確認済みで、現状維持を了承済み。
- AUTO/MANUAL切替・背景復帰で問題なしとの報告はあるが、全条件で競合がないとは断定しない。
  再現されたCameraX/Interop競合や端末非対応を示す具体的な失敗条件は未提示。
- 共有保存はAPI29以上。広範囲ストレージ権限は追加せず、DNG表示は閲覧アプリに依存する。
- 配布は一時debug署名。旧v0.0.1-dev.1や別CI候補とは署名が異なり上書きできない。
  固定鍵・Secrets・継続署名運用は未導入。今回の旧内部テスト写真の消失許可を将来へ一般化しない。
- エージェントは実機を操作していない。後続診断の要約と初期ユーザー報告を区別し、未取得のファイル原本やCaptureResult値を推測・捏造しない。

## P1判断案と残る確認

**提案: GO_WITH_LIMITATIONS（採否承認前）**。

中核となるRAW素材の保存/PC読み込みと手動露出の実測反映、切替・復帰で成功報告があり、
CameraX中心の構成を直ちに破棄すべき実測の不具合は得られていない。
この範囲を根拠に、制約付きでP1の仕様検討へ進む案を提示した。初回整理時はP1実装は未依頼だった。

過去のDNG個別資料・ISO/露光時間の数値原本、初期APKの同一性、AC6の異常系は未取得または未検証のままとする。後続版の診断・ユーザー確認は上記の範囲に限る。権限の制約は原因未確定のまま現状維持を了承済み。P0全ACの無条件合格や制約なしGOにはしない。この判断案はP0の検証結果に関するものであり、後続フェーズの進捗や実装承認を遡って判断するものではない。

AC3/4が今後端末仕様で不成立と判明した場合は、指定値/実測値・形式・エラー・再現条件を根拠に
BLOCKEDを記録し、制限付きで進むか最小のCamera2比較を行うかを相談する。
現時点で全面Camera2移行や独自回避を正当化する失敗根拠はない。

## 記録の管理

このファイルにはP0の最終検証結果・制約・採否根拠を残す。確定した振る舞いと受入条件は
[spec](../specs/001-p0-camera-probe/spec.md)、ACに対応する完了状態は
[tasks](../specs/001-p0-camera-probe/tasks.md)で管理する。
日々の作業状況、確認のやり取り、試行ログはIssue等で扱い、時系列の進捗追記をこのファイルへ積み上げない。
