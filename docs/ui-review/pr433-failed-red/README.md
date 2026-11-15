# Failed 色案 A の製品反映後（PR #433）

[4画面を1枚で見る](after-overview.png)

製品候補 source: `058b0409c6cce543e2721eb5172d8547901acdd8`。Failed を `#FF6B6B` に変更。WARNING と録画色、文言、操作条件は変更していません。本人選択済み案 A の反映後を確認する一覧です。マージ状態は PR #433 を参照してください。

## 原寸
- [カメラ権限なし](full/permission.png)
- [通常の高温 WARNING](full/thermal.png)
- [熱による録画開始拒否](full/thermal-rejected.png)
- [録画中](full/rotation-recording.png)

通常高温のバナー線は Amber のまま、SEVERE 熱チップは既存の FAILURE 用途なので新しい赤です。録画は従来の赤を維持し、REC・時間・枠でも区別します。

## 採取条件と限界
最終製品 commit の撮影画面・共通部品を一時 androidTest package に複写。CameraX の映像部分のみ合成図形に置換し、固定の合成状態を入力しました。Failed は製品ソースの値をそのまま使用。色の画像加工はせず、原寸画面を同じ縮尺で一覧化しています。

API 37 ATD、1080×2424、density 2.625、fontScale 1。read-only snapshot prepared_v1 を使用。Canvas で全画面を描画した非 GPU 表示確認で、Pixel・実カメラ・実録画・物理高温の合格ではありません。個人の写真は含みません。一時 fixture は製品 PR に含めません。

4状態を各2回、合計8枚採取する試験は 18.081 秒で成功。状態不変・主要文言・チップが歯車に隠れないことを検証。公開は同内容の4枚だけです。独立した目視レビューでも切れ・重なり・状態ラベルの齟齬なし。

原寸のバナー線の画素 `(45,210)` は、通常高温 `#E6A35C`、拒否直後 `#FF6B6B` を確認。色覚・端末輝度などへの十分性はこの表示試験だけで保証しません。

採取 APK SHA-256:
- product: `DA1E36D59B3B33E43569F6988362211468582BB08F036530E47EF41C05ADA5ED`
- temporary androidTest: `2FDFF5B27F5403DFE6179EF56450C21A2FA5D514F008F9E8E8F575852E56F6F4`

## 製品候補の検証
- Android checks run 1185 / 43665682250: success。
- lint / unit / assembleDebug / assembleDebugAndroidTest: success（4分50秒）。単体1379件、失敗0・error0・skip12。
- AOSP read-only cold boot、正式 instance 登録・90秒 system_server 安定・ANRなし。
- GPU `golden-20261115-020521-058b0409`: 90条件と帯28比較成功（87.454秒）。承認済み `golden-20261112-131916-51b691ad` との全 RGB8 画素一致、比較 pass。環境差あり、基準更新・閾値変更なし。
- canonical / 独立コードレビューとも指摘なし。

この文書・合成画像だけを別の確認用ブランチで公開し、製品 PR の head は変更していません。