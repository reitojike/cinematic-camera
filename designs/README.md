# 撮影UI・アイコン比較

[camera-ui-comparison.html](camera-ui-comparison.html)を保存し、ブラウザーで開く。ネットワーク不要の単一HTML/SVGで、
Androidアプリを置き換えず、撮影・録音・保存・ルック処理を行わない比較用simulation。
Issue [#19](https://github.com/reitojike/cinematic-camera/issues/19)、FR-012/SC-006に対応する。

| 案 | 方向 | 比較したい点 | tradeoff |
|---|---|---|---|
| A 計器型 | 上段に大きいISO/シャッター/focus、機器スイッチ | 設定の読み取りと構図の往復、数値の見つけやすさ | 上段の情報密度と視線移動を要確認 |
| B ファインダー型 | previewと余白を優先、控えめな数値 | 被写体への集中と設定確認の両立 | 暗所での小さい数値・操作領域を要確認 |
| C 下段操作型 | 親指に近いcontrol deckとmode切替 | 片手の到達性、持ち替え、modeの誤操作 | 下段の混雑と誤タップを要確認 |

各案に異なるmonochrome SVGアイコンを添える。Aの角形フレーム/抽象絞り、Bの開いたfinder/中心点、
Cのレンズ/機器パネルを比較する。いずれも正式アイコンではなく、配色・配置・アイコンは別案同士を組み合わせられる。

全案の合成stage画像と初期値は同じ。STILL/CINE別の例示ISO/時間を記憶し、WB/focus等のハードウェア動作を模倣しない。
21:9はoverlayだけで元の合成画像をcropしない。数値・UHD60・SDR・AF表示は端末対応や画質の証拠ではない。
Stage Clear / Stage Dramatic / Cinema T&Oはラベル配置だけで、画像へ処理していない。
ユーザー写真・外部フォント・通信・分析・localStorageなし。比較メモの候補もページ内だけで送信/正式採用しない。

確認操作：全案STILL/CINE、各案のISO/シャッター/focus、ガイド、lookラベル、撮影simulation、候補メモ、reset。
keyboardのTab/Enter、狭い画面の横overflow、画面renderを検査する。実機の指到達性・暗所視認性は本人確認へ残す。
正式選択後に別IssueでAndroidの限定UIへ落とし込む。現行probe UIと撮影基盤はこの比較だけでは変更しない。

機械的確認は専用のheadless Edgeで1440/820/390px幅を描画し、横overflow・画面下端の欠け・script例外・
外部requestなしを確認した。mode別数値保持、guideによる原図不変更、lookによる画像不変更、
仮録画中のcontrol抑止、候補/reset、keyboard Tabも確認した。これは実機の到達性/画質/視認性の合格ではない。
