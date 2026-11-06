# 前景開始した単一JPEG保存の背景継続

関連: #81 / #89 / #143、既存spec038。写真原本・品質・画像処理は変更しない。

- FR1: 表示中の明示Save操作で1件だけ開始する。背景からの新規開始、並行保存、Service再配達による再現像を許可しない。
- FR2: 開始済み保存はアプリ切替だけでは中断せず、Serviceの通知と画面の両方で取消可能とする。通知許可拒否は保存許可に転用せず、FGS通知の提出は必須。
- FR3: 撮影予約・危険/未知の資源観測・取消・timeoutは同じownerに停止を要求する。停止要求と実解放確認を分ける。単一slotと原本保護を維持する。
- FR4: API29〜34はdataSync、35+はmediaProcessing。既存queue/保存handle/journalのみ使う。Activity/preview/BitmapをServiceに保持しない。
- FR5: process death後は旧Service tokenから処理しない。次の明示操作時もintent-only/不明は既存HOLD、検証済みjournalは同target照合のみ。新しいJPEGやMediaStore行を自動作成しない。
- FR6: 保存結果は既存ownerの証拠で表示する。Service受付、Future完了、通知消失だけを保存成功とは扱わない。

範囲外: 撮影中の保存継続/自動再開、複数保存、画素処理高速化、品質変更、実機操作、配布。
成功条件: 純粋な受付/停止/復旧試験、既存保存journal試験、API別typeとServiceの合成試験、lint/unit/build・独立/canonicalレビュー。
実機未確認: Pixelの背景継続、通知拒否、熱/低メモリ、process kill/復旧、撮影中断、12MP/24MPの実解放。合成試験を実機合格に読み替えない。
