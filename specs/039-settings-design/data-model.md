# Data Model

新しい永続データ/制御状態機械は追加しない。

| 表示 | 既存source/制約 |
|---|---|
| モード・ID・形式 | CameraProbe.mode/selectedId/format。要求と成功を分離 |
| 動画の現在値 | boundとactiveVideoChoice。候補はvideoChoices |
| 編集可否 | enabled、stillExposure.ownsCapture、controlReadyと既存項目別guard |
| 能力 | formats/reports/selectedReport。欠落を対応済みとしない |
| ロック | autoLocksとAUTO条件。ON/OFFの同値選択はno-op |
| リセット | 既存confirmReset/unlocked/resetAllControls |
| 再接続の説明 | 操作前の項目/値と変更先の一時snapshot。既存bindの状態と対応付け、UI終了/失効時破棄 |

snapshotは表示専用で、保存/再送/受付成功の判断を持たない。実装時の状態対応が曖昧な場合は該当表示を保留し報告する。
