# 実装と検証

- [x] FR1/3: process gateと対象attempt限定export受付・安全観測。
- [x] FR4/5: 既存handle/queueへ接続したService、再配達・取消・timeoutの停止。
- [x] FR2/6: UI保存開始・通知権限・状態購読・取消の接続。
- [x] FR1/3/5: 純粋試験と既存保存journal回帰試験。
- [x] FR2/4: API別typeの単体試験、API37で通知拒否・Service停止の合成Android試験。
- [ ] FR4/5: API33/34のruntime、OS timeout、process death後journal復旧の端末統合確認。
- [x] lint/unit/build、通常サイズAPI37の実画面・実owner保存/取消。
- [ ] 小画面・文字拡大のUI統合再試験。
- [ ] 最終headの独立レビュー・CI/canonicalレビュー（PR #181参照）。
- [ ] 実機検証（別途許可・素材確認が必要）。
