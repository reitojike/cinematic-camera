# 熱チップ Amber 統一後（PR #434）

[主な4画面を1枚で見る](after-overview.png)

製品候補 source `295590501daa4c9ce9a4925921bbc234e8587b91`。本人指示により熱チップ1〜6を WARNING/Amber に統一し、深刻度の文字を維持。0・不明は NORMAL のまま。録画停止・拒否条件、失敗のバナー、操作、文言は変えません。見た目の本人承認までマージ HOLD。

## 原寸
- [SEVERE 通常](full/thermal.png)
- [SEVERE 録画開始拒否](full/thermal-rejected.png)
- [CRITICAL 通常](full/thermal-critical.png)
- [CRITICAL 録画開始拒否](full/thermal-critical-rejected.png)

通常バナーは Amber、拒否直後は赤。チップはどちらでも Amber のままです。深刻度5/6も同じ規則で、全段階は単体試験しています。停止処理そのものはこの静止画で検証したとは扱いません。

## 表示採取
最終製品ソースの撮影画面・共通部品を一時 androidTest package に複写し、カメラ映像部分だけ合成図形へ置換。固定の合成状態を入力しました。色は製品値そのままで画像加工なし。原寸全画面を等縮尺で配置。個人写真は含みません。

API37 ATD read-only snapshot prepared_v1、1080×2424、density2.625/fontScale1、Canvas描画。4状態各2回、8枚採取は20.514秒成功。主要文言、状態不変、チップが設定ボタンに隠れない境界assertを通過しました。通常/拒否のバナー線pixel(45,210)は両深刻度で E6A35C/FF6B6B。Pixel・実カメラ・物理高温の確認ではありません。一時fixtureは製品PRに含めません。

APK SHA-256:
- product `E259C59AA5F843BE1E8C185A97CE287AB089ADC5993A1608B8678904E40D39CE`
- temporary test `0500A44E2614D9A61219D769467BB8F692AD4B461A7645ED3D5BFC54887C0976`

## 製品候補の検証
- Android checks1187 / 43670701993 success。
- lint/unit/build success。単体1380件、失敗0/error0/skip12。確定headで通常検証を再実行し成功。
- AOSP read-only cold boot、正式instance登録、90秒system_server安定/ANRなし。
- GPU run `golden-20261115-030957-29559050`：90条件＋帯28成功（87.01秒）。承認済み `golden-20261112-131916-51b691ad` と全RGB8画素一致。環境差あり、基準・閾値変更なし。
- canonical・独立コードレビュー：指摘なし、明示的OK。

確認用画像だけ別ブランチに置き、製品PRのheadを変えていません。