# Research decisions

- Decision: 既存固定LibRaw基準を監査し再利用。Rationale: WB/gamma/露出とlookを分離。Alternative: 本人JPEGへの自動fitは採らない。
- Decision: 手動局所領域で肌明度を比較。Rationale: 色照明/衣装/背景へ一律補正しない。Alternative: 顔AI/多frameは対象外。
- Decision: linear RGBの輝度比とsRGB変換一回。Rationale: clip前の指標と再生成を追跡。Alternative: CSS filterは採らない。
- Decision: 撮影者本人作例とAdobe公式局所明度/控えめ彩度を評価仮説に使う。人気保証/数値転用はしない。

公式: [Color adjustments](https://www.adobe.com/learn/lightroom-cc/web/precise-color-adjustments)、[Masking](https://helpx.adobe.com/lightroom-classic/desktop/process-and-develop-photos/masking.html)。
作例リンクと見所はlocal比較に掲載、画像転載なし。
