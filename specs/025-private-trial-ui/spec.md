# 手動の私有試験JPEG表示

既存Issue #89/#129起点。顔SDKを初回試用で保留する方針に合わせ、完成JPEGの公開契約を弱めず、選んだDNGの縮小試験結果をアプリ内で確認する。

- 固定したSharedOriginalReferenceとtrial recipeを共通制御へ渡す。UIはID/世代を採番せず、別queue・private出力コピー・完成publisherを作らない。
- 最大1024px・実寸法、顔保護なし、診断露出・as-shot暫定色を表示する。試験結果を完成品質・顔検出済み・Pixel画質合格と扱わない。共有保存は無効のまま。
- 同一handle/世代/artifactのfresh private readbackだけを表示へ採用する。原本変化・失効権限・未知状態・失敗・停止は成功へ補完せず、旧選択の結果を採用しない。
- 撮影状態はCameraProbeごとのprocess ownerからその都度読み、旧ActivityのFinalize/不明を新Activityで上書きしない。CameraManagerの全カメラavailabilityだけで録画終了や将来の再接続なしを推測しない。
- provider取得/bind/unbindは明示状態、再開時の撮影優先はActivity lifecycle開始前に通知する。背景中に届くprovider callbackのbindは前景まで延期する。カメラ解放の要求受付と実ハードウェアavailabilityは別に確認する。
- 共通実行枠はnative/codec/streamの実close後にだけ解放する。cancel/metadata受領/Activity破棄はcloseの証拠ではない。

対象外: 完成品の共有保存/公開、自動現像登録、顔SDK、新しい権限・依存、原本/公開済み結果の置換、実機撮影・配布・画質合格。

依存: #131のtrial生成と#132後続のprivate-trial専用handle/fresh readback。依存未成立の状態を接続済みと表示しない。
