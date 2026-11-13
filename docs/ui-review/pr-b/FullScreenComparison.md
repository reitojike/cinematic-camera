# PR-B 全画面比較の条件

変更前 main 53e68ffc と変更後 ea2cb537 の画面ソースを試験専用に複写して描画しています。製品コードは変更していません。旧・新双方の CaptureScreen、CaptureSettingsSheet、CaptureDiagnosticsSheet、CapturePanels のレイアウトを維持しています。旧 CaptureFeedback も旧版を使用します。

## 差替え箇所

- CameraX PreviewView の生成・initialize 呼出しだけを、同じ領域を占める合成図形に差替え。カメラを起動せず、実写真・端末の写真一覧は使いません。未接続/再接続中は黒背景です。
- CameraProbe 初期化時の実OS熱listenerを試験側で解除し、登録時の初期callbackを排出してから固定状態を与えます。取得直前に bound/busy/ready/mode/format/thermal/音声/receipt/still状態が設定時から変わっていないことを assert します。
- CameraProbe には同じ固定状態を与えています。カメラ ID、形式・ISO/シャッター範囲は架空の共通入力で、実機能力の証拠ではありません。撮影・保存・設定変更の callback は実行しません。
- シート/リセット/保存完了通知の初期表示状態と、設定適用中の SettingsChange を固定。実際の selectFormat の順序に合わせ、現在形式 RAW・変更前 JPEG・変更先 RAW としています。
- 不正表示13/14は変更後に安全な汎用問題カードを描画。旧側に対応カードはなく、不正入力を旧画面で実行した結果ではありません。
- テーマ・色・書体・safeDrawingPadding・ヘッダー・操作領域・設定シートの高さ・enabled 判定は画面ソースのまま。合成入力に対して元の条件式を実行しています。
- 設定シートは実際の位置・高さ。スクロール下に続く項目は映りません。診断も先頭位置です。

## 取得と preflight

- 同じ固定 ATD、1080×2424、density 2.625、fontScale 1.0、portrait。
- 代表状態（接続待ち、リセット、設定適用中、診断）を先に取得し、前後の文字色・背景・シート位置・操作表示・寸法を確認してから全量取得します。
- 各状態の期待文言を accessibility tree で待ち、存在を assert してから取得。
- 画面全体の decorView をソフトウェア Canvas に描画。リセットは同プロセスの focused/shown Dialog 1枚を特定し、host 相対座標と実 WindowManager の dimAmount/FLAG_DIM_BEHIND で重ねます。寸法・density・dialog位置/減光設定は full/preflight.txt に記録。
- 画面画像は切り抜かず、一覧で前後を同倍率に縮小し、文言差を添えます。

## 未確認

実機の CameraX プレビュー、カメラ/録画/保存/再接続の操作結果、GPU描画、System UI、IME、動的な回転は未確認です。画面配置と表示の比較資料です。横向きの中央表示・警告の共存は従来の部品比較資料で別途確認しています。

画面ソースの一時コピーと合成fixtureは main に入れません。確認用PNGも本人承認後に差分から外し、承認された固定commitに証跡を残します。本人承認前にはマージしません。
resetはView描画に実座標・減光設定を適用した参考合成であり、OSコンポジタそのもののスクリーンショットではありません。
