# リポジトリの案内

このファイルは現在の判断材料への入口です。仕様や手順を重複させず、対応する正本を更新します。

## 仕様と変更記録

- 開発は原則 Issue 起点です。重複を確認し、目的・背景・範囲・対象外・完了条件・検証と停止条件を Issue に定め、作業 branch / PR を関連付けます。途中で Issue を作った場合は経緯を偽りません。
- 現在の製品契約は [Living Spec](specs/README.md)、横断制約は [Constitution](.specify/memory/constitution.md) を正本とします。改修ごとに番号付き spec / plan / tasks 一式を積み上げず、既存の機能仕様を更新します。
- 改修内容・計画・差分・実装タスク・進捗・検証証拠・レビューや採否の判断は Issue / PR に残します。長期的に必要な技術設計は docs、再実行する運用手順は docs の手順書に残します。
- 番号付きの既存資料は移行中の参照資料です。集約済み範囲は Living Spec が優先し、未集約の詳細はそこから参照します。古い Draft / 承認待ち / tasks の状態を現在の進捗とみなしません。根拠を移す前に削除しません。
- Draft PR の仕様は提案であり、マージまで main の契約を置き換えません。検証用・既定オフ・未採用の経路を製品採用済みとは書きません。
- [Spec Kit の運用](docs/SPEC_KIT.md) に従い、必要な specify / clarify / plan / tasks / analyze / implement / converge を使います。成果物を作ること自体を目的にせず、生成物を自動的に現行仕様の正本にしません。標準生成 skills は直接改変しません。
- 文書は原則日本語です。モック画像の寸法や例の数値を実測と扱いません。

## 実装と検証

- Android 標準 API、Jetpack、lifecycle・権限の標準実装を優先します。標準から外れる必要が実測された場合は理由・代案・範囲を技術設計に残します。CameraX 管理の CameraDevice / session を直接操作しません。合意した CameraX 1.6.2 の固定は、依存更新候補があるだけでは変更しません。
- 要求・受付・センサー反映、保存完了・DNG妥当性を区別します。静的検証・合成試験・エミュレーターを実機の速度・画質・操作感の合格に読み替えません。未確認は未確認と明記します。
- 原本保持・写真と動画の入力差・少数の完成したルックを重視し、Stage Clear を優先します。新しい案の存在だけで未依頼の実装・実機撮影・公開・Release を始めません。
- 変更に応じて `./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug --no-daemon` と `git diff --check` を実行します。Windows は `.\gradlew.bat` を使います。必須検証の正本は [Android CI](.github/workflows/android.yml) であり、文書のみの変更でもサーバー側の必須 CI を免除しません。ローカル未実行・環境不成立は明記します。
- 共有端末の利用は [MANAGED_DEVICES](docs/MANAGED_DEVICES.md) に従います。実機確認待ちだけで独立した限定作業を止めませんが、重大な欠陥・失敗・権限判断・範囲拡大では停止して報告します。

## 作業管理と権限

- 作業の稼働状況は Dot が管理する。着手前に担当・対象・共有試験枠の所有を確認し、保留・引き継ぎ・再開・終了時に実際の状態と検証結果を更新する。Windows GPU 測定枠は単独、未確認を終了済みと扱わない。[#489](https://github.com/reitojike/cinematic-camera/issues/489) は過去の記録で、新規の稼働記録は追記しない。公開する Issue／PR には個人用パス、実写、URI、端末識別子、内部スレッド URL、生ログを書かない。
- 本人の継続承認の範囲：作業ブランチへの push（main 直接 push を除く）、Draft PR の作成・更新・ready for review 化、Issue／PR へのコメント（レビュー依頼を含む）、Issue 作成。独自実装から公式根拠のある標準実装への移行は、コード量・複雑性を過度に増やさない範囲で、必要な実装・PR・レビュー・マージを進めてよい。ただし既存 CI、独立レビュー、main 保護の条件は維持する。通常のマージは合意した範囲の承認を確認する。
- リリースの公開・配布、実機操作は都度承認。制限外の Windows AVD 起動・停止に継続承認は現時点でなし。作業フォルダの指定、本書の記述、一度限りの承認は、サンドボックス例外、アカウント・ネットワーク・システム設定の変更を許可しない。最新の本人指示、実行環境の権限、別途の承認要件を優先する。

## PR・公開・安全性

- [OPERATIONS](docs/OPERATIONS.md) に従い、作業 branch と Draft PR を使います。最終 head の必須 CI、canonical `@codex review`、独立レビューの明示的 OK、未解決会話0件と、適用範囲のマージ許可が条件です。
- 作者の自己レビューを独立承認に数えません。formal APPROVE の代行、admin merge、チェック免除、保護回避はしません。[REPOSITORY_STATUS](docs/REPOSITORY_STATUS.md) と実際の設定を確認します。
- [PUBLICATION_REVIEW](docs/PUBLICATION_REVIEW.md) に従い、秘密・認証情報・署名秘密・個人情報・個人用絶対パス・非公開サービス先・会話ログ・写真本体を Git / ZIP に入れません。
- 実機撮影、固定署名秘密の新規生成・継続利用設定、他プロジェクトの鍵の流用を無断で行いません。DNG原本は非破壊で扱います。
- P0 の調査範囲・実測は [P0_REPORT](docs/P0_REPORT.md) と [Issue #4](https://github.com/reitojike/cinematic-camera/issues/4) の歴史的文脈です。当時の対象外一覧を後続で実装済みの RAW / NDK / UI 等の禁止へ読み替えません。
