# Cinematic Camera P0の作業範囲

- P0の要件・実機AC・停止条件は`docs/P0_REPORT.md`、ビルド証拠は`docs/BUILD_VALIDATION.md`、branch / CI / APK配布運用は`docs/OPERATIONS.md`を読む。
- CameraXは安定版1.6.2を維持する。実機証拠なしでPixel 9a対応・RAW有効性・手動露出反映を合格扱いにしない。
- 初期化後のmain変更は作業branchとDraft PRを使い、利用者のmerge許可がなければmerge前で停止する。APPROVE代行、admin merge、保護回避を行わない。
- 無断の端末操作・撮影、署名秘密の新規作成や継続利用設定、他プロジェクトの鍵の流用は行わない。
- DNG/JPEG/動画、撮影対象の個人情報、秘密、local.properties、ビルドキャッシュをGitへ入れない。
- LibRaw/NDK、GPU/LUT、動画、SNS、21:9保存、バックエンド、製品UIは今回の範囲外。追加は別タスクとする。
