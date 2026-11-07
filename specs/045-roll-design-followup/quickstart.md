# カメラロール表示の検証

[Issue #187](https://github.com/reitojike/cinematic-camera/issues/187)

## 機械的検証

- ベース: main `a3beed6`（R7 tone補正を含む）。
- `lintDebug` / `testDebugUnitTest` / `assembleDebug` / `assembleDebugAndroidTest` 成功。
- 単体テスト: 869件、失敗0、エラー0、skip 4件。既存のskipを合格件数へ含めない。
- `git diff --check` 成功。新規依存・SDK導入なし。
- Windowsでは既存SDK/JDKを指定し、`gradlew.bat`を `--offline --no-daemon --max-workers=2` で実行した。

## 合成UI試験

専用の空エミュレーターで、次のclassだけを `-e isolated true` とともに実行する。

```text
adb -s <dedicated-emulator> shell am instrument -w -e isolated true -e class dev.cinematic.p0.photoroll.CameraRollBrowserAndroidTest dev.cinematic.p0.test/androidx.test.runner.AndroidJUnitRunner
```

4テスト成功。CameraRollAccessと一覧を合成データで直接渡し、権限要求・Files・設定のcallbackを実操作へ接続しない。
既存PhotoRollActivityを起動してUIを置き換えるため、起動直後の既存repository readはあり得る。
実写真、実権限付与、撮影、RAW現像、共有保存、外部viewerは試していない。

- 全面許可で案内帯がない。選択のみ・写真/動画の混合選択は、正確な案内と「選び直す」を維持。
- 片方のみ許可・未許可・非対応の中立な説明を維持。未許可でも合成の既存項目を表示できる。
- 空の2操作は各1回のcallbackを発火。無効化後の追加呼出は0回。
- 読込中0件では空の案内なし。既存タイルは読込中も残る。
- 412×924dp、320×480dp、924×412dp、412×924dp/fontScale 1.3で案内・2操作・フッターに到達。
  合成viewportを端末内へ縮尺表示する試験であり、実機の回転・タッチ操作感の合格ではない。

## 画素確認と残る確認

テストが生成したPNGを開き、全面空の説明/2操作が一覧領域の中央にあること、
選択・混合の帯がアンバーの破線/本文であること、保持タイルのplaceholderが共通Panel色であることを確認した。
狭小/横長/大きい文字はスクロールで操作とフッターへ到達する。初期画面ですべてが同時に見えるとは限らない。
画像・ログはローカルの合成証拠とし、リポジトリへ含めない。

実機では、通常文字/大きい文字、縦/横、全面・部分・混合・未許可を確認し、
空状態の2操作が既存のpermission/Filesへ遷移すること、読込中に既存写真が残ることを確認する。
実素材と実権限での確認は未実施。実装commit `8336569` は独立レビューOK・ローカルconverge済み。
Issue #187はローカル実装後に関連付けた。公開後の最終headのCI・canonical review・未解決会話・merge条件の確認は未完。
