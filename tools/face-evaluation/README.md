# 顔検出の独立評価構成（#79）

ML Kit は第一候補であり未採用。この Gradle project は製品 root settings から include しない。
製品 app、診断 variant、保存/GPU/LibRaw/JNI/UI への参照はない。独立した applicationId、build 出力を使う。
本構成を製品依存や製品 APK に組み込まない。

## 範囲と入力

baseline（検出器なし・容量測定のみ）、mlkit（bundled 16.1.7 の FAST/ACCURATE）、
yunet（OpenCV 4.13.0 と固定 2023mar モデル）を同じ debug・minSdk33・arm64-v8a/x86_64 で比較する。
FAST/ACCURATE は同一 APK の設定差。輪郭・landmark・分類・tracking は無効、minFaceSize は 0.1。
これらは比較設定であり、製品の採用閾値ではない。

アダプターの共通入力は向きを適用済み・不透明・sRGB・ARGB_8888 bitmap。同じ画素を渡す。
入力前の DNG decode、linear→sRGB、crop、縮小、EXIF は本構成で行わない。
実写評価の前には、承認済み原本から PC の `r10h_refine.detect_faces` の
`linear_to_unit → clip → *255 → uint8` と同じ段階の画素を一度作り、入力hash・寸法・
色変換/decode版・crop/向き・source revision を非公開 manifest に固定する必要がある。
埋込JPEGを正本へ流用しない。既存PCスクリプトは変更しない。

YuNet アダプターは、その画素の RGB→BGR、0/25/-25/45/-45度探索、score .5・NMS .3・topK5000、
採用score .75/.85、中心逆変換・整数切捨て・既存の重複抑止を移したもの。
回転後bboxはPC同様に中心だけ逆変換する。完全な回転矩形の外接bboxではない。
PCとの実出力一致は未検証。モデルは repo 外から指定し、build と runtime の両方で
SHA-256 `8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4` を検証する。
新しいモデルへの置換はしない。OpenCV runtime の性能・数値差は比較時に別記録する。

## host とビルド

既存のJDK/SDK/共有Gradleキャッシュを設定し、repo rootから以下を順番に実行する。
同一build領域でGradleを重ねない。モデルは公式 OpenCV Zoo の固定2023marをrepo外へ置き、
`FACE_EVAL_MODEL` 環境変数に指定する。公開モデルの手動取得以外のdownload処理はprojectに含めない。

```powershell
python -B -m unittest discover -s tools/face-evaluation -v
.\gradlew.bat -p tools/face-evaluation :benchmark:verifyEvaluation --no-daemon --max-workers=1
python -B tools/face-evaluation/audit.py
.\gradlew.bat -p tools/face-evaluation :benchmark:dependencies --configuration mlkitDebugRuntimeClasspath --no-daemon
.\gradlew.bat -p tools/face-evaluation :benchmark:dependencies --configuration yunetDebugRuntimeClasspath --no-daemon
```

モデル未指定・hash不一致なら YuNet のbuildを失敗させる。容量は同じ条件の完成APKのbytesとbaselineとの差で測る。
APK圧縮済み容量とnative非圧縮容量を区別する。製品容量差やPlay配信サイズ、インストール後サイズとは異なる。
ABI、AGP、build type、署名方式、圧縮/縮小の条件を変えた値と混ぜない。
root製品CIは従来通り必須。本projectの検証は上記の独立コマンドで別途実行する。

## Android 合成評価の実行条件

本人はオフラインでも動くことを重視しつつ、ML Kit の利用・診断ログ通信を許容している。
完全無通信は要件ではない。画像・顔検出結果の送信、製品への組込み・依存採用は別判断。
現時点で実写・Pixel・AVD・SDK推論は未実行。写真入力のUI/ファイル読込経路は実装していない。

ML Kit flavor だけ INTERNET/ACCESS_NETWORK_STATE を持たせ、SDK の通常の診断通信を妨げない。
baseline/YuNet は通信権限を持たず、全 flavor のカメラ・写真・連絡先等の権限は認めない。
`audit.py` で完成 manifest の許可リストを検査する。固定 SDK/runtime/model は変更しない。
Google 向け利用・診断情報（評価用アプリ/端末情報、インストール単位ID、処理時間、
入力形式・解像度、イベント・エラー等）が通信対象。入力画像と顔結果は送信対象にしない。
これは公式説明を基にした許可範囲であり、この固定版の全payloadを観測した証明ではない。

`SyntheticRunner` は黒一色640×480だけを生成し、11回直列に実行する。
明示的な `syntheticRunApproved=true` がない場合に停止するが、provider 初期化は
runner より先に動き得る。引数で通信を遮断できるとは扱わない。
実行前に個人アカウント/写真のない評価環境と `docs/MANAGED_DEVICES.md` の排他を確認し、
#290 の AVD 利用を優先する。実機は都度承認。端末のセキュリティ/ネットワーク設定を勝手に変更しない。

1. 初回オフライン起動で bundled モデルの FAST/ACCURATE が動くか確認する。
2. オンライン時の SDK ログ通信は別項目で記録し、オフライン動作の合否と混ぜない。
3. 各条件で初期化・推論・待機・再起動を分ける。宛先/時刻/量だけで暗号化payloadやIPCの内容を断定しない。
   画像/顔結果や想定外データの送信が疑われる場合は中断して確認する。raw capture は公開しない。

排他取得済みの評価エミュレーターを明示指定し、各 FAST/ACCURATE/MULTI を別プロセス起動する。
対象評価packageのforce-stop後に `am instrument -w -e syntheticRunApproved true -e mode FAST
org.cinematic.evaluation.mlkit.test/org.cinematic.evaluation.SyntheticRunner` を実行する。
ACCURATEはmodeだけ変更、YuNetはmode MULTIと `org.cinematic.evaluation.yunet.test` を使う。
終了bundleのresultまたはerror_typeを確認し、失敗を0件成功と扱わない。baselineは容量用で実走対象外。
結果はtarget appの内部files/synthetic-results.jsonlに保存される。取得先もrepo外とし、公開ログへ貼らない。
`first-call` は新しい detector の最初の呼出し、`warm` は同一detectorの後続10回。
OS cache・SDK provider初期化を含む process cold は別測定し、first-callだけでcold完了としない。
初期化時間、SDK呼出しのwall時間（ML Kitは非同期待機込み、YuNetは5方向のdetect合計）、
adapter時間（SDK入力変換・回転・結果変換込み）、bitmap複製を含むtotal時間を別記録する。
DNG decodeや元画像縮小はtotalに含まれない。GPU/CPU kernelだけの推論時間とは異なる。
OpenCV Java detector に公開closeがないため、反復してdetectorを作らずプロセス終了で資源を解放する。
ML Kit の中断待ちはSDK完了までdrainしてからbitmapとdetectorを解放する。

## 結果と正解ラベル

JSONL schema=1、sampleは合成ID、detector版、geometry、寸法、phase、iteration、初期化/処理時間、
status、boxes（x,y,width,heightの入力pixel座標）を保持する。FAILED/CANCELLED/NOT_RUNを成功0件にしない。
例外message・写真名・パスは出力しない。runnerは同期で所有し、別sourceへの非同期公開は行わない。
実写runnerを拡張する際は入力hashとrevisionの一致を検証し、旧結果の棄却を追加する必要がある。

host `evaluate.py` は画像を読まず、人のラベルと結果のJSONだけを読む。すべての入出力はGit checkout外に限定する。
label形式は `authority: human`、`split: tuning または held-out`、`samples` の各IDに
`geometry`, `size: [width,height]`, `condition`, `boxes` を記録する。
曖昧/完全遮蔽/極小顔の扱いと対象条件は本人が先に定め、確定できない画像をこの単純な全対象評価へ混ぜない。
YuNetをラベル生成の正解にはしない。`--iou` は必須で既定値なし。
最大対応数の1対1照合でTP/FN/FPを数え、重複検出はFPとする。同じ画像の反復を精度件数へ水増ししない。
失敗回数を別計上し、反復間の検出差は自動平均せず停止する。条件別に時間のn/median/p95を返す。
結果に現れる各detectorについて、人ラベルにある全条件を必ず出力する。結果行がない条件も
`expected_samples` と `unscored_samples` に含め、未評価を検出漏れFNや成功0件へ変換しない。
全失敗の条件は未評価のまま、状態別件数のみを記録し、精度件数・時間へ含めない。
完全に空の結果はdetectorを特定できないためエラーにし、空ラベルも拒否する。
結果に一度も現れないdetectorの存在は推測できないため、比較予定の全detectorが含まれることは
収集時に確認する。未実行detectorを含める場合も、そのIDとラベルに一致するsample/geometry/寸法を
持つ明示的なNOT_RUN行が必要。集計は採用合格を判定せず、`adoption: UNDECIDED` を維持する。
少数sampleでp95を安定性能の根拠にしない。

```text
python evaluate.py --results <repo外の結果> --labels <repo外の人ラベル> --output <repo外の新規集計> --iou <事前合意値>
```

画像・顔切抜き・画像別値・個人用パス・元JSONLはGit/公開Issue/PRへ載せない。
集計にも少人数条件から個人を推測できる値があり得るため、公開前に本人が確認する。
`.gitignore` は補助であり、写真の保管場所は必ずrepo外。個人写真の新規読み取りは未許可。

## ライセンスと未取得項目

- [ML Kit入力・SDK](https://developers.google.com/ml-kit/vision/face-detection/android)、
  [Terms](https://developers.google.com/ml-kit/terms)、[data disclosure](https://developers.google.com/ml-kit/android-data-disclosure)。
  モデル同梱は無通信保証ではない。SDKはWebサンプルのApache licenseとは分け、GoogleのAPI条件と推移依存のnoticeを確認する。
- [YuNet MIT](https://github.com/opencv/opencv_zoo/blob/main/models/face_detection_yunet/LICENSE)、
  [OpenCV Apache-2.0と第三者通知](https://github.com/opencv/opencv/blob/4.13.0/LICENSE)。
  モデルとruntimeのlicenseは別。AAR/JAR内のlicense/notice/third_party_licensesを座標別にAPKへ収載する。
  `assets/dependency-inventory.tsv` に実解決版・artifact hash・notice件数を記録する。
  件数0やnative内部の第三者通知などを含む網羅性の確認は配布前ゲートとして残し、収載だけで法務確認済みとはしない。
- 未取得: 実写精度、PC/Android出力一致、初回offline/IPC/通信、Pixel cold/warm、PSS/native/graphics、
  電力/熱、撮影/保存競合、mask被覆/背景漏れ、顔測光とguardの画作り。すべて未合格。
  Camera2撮影補助、製品接続、採用閾値、依存採用、写真送信、リリースは本人の後続判断。
