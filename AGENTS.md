# リポジトリの案内

このファイルは現在の判断材料への入口です。仕様や運用規則はここへ複製せず、対応する文書を参照します。
think-canvasの適用可能な方針を本プロジェクトへ合わせ、製品固有の規則や過去のIssueは移植しません。

- 変更目的と作業の完了境界は依頼内容と対象PRで確認します。古いbranchを現行要件の根拠にしません。
- 要件の根拠は合意したP0ブリーフと[ライブ撮影の製品方針](specs/002-live-capture/spec.md)です。
  [Constitution](.specify/memory/constitution.md)が原則を、[P0 spec](specs/001-p0-camera-probe/spec.md)が既存基盤の
  振る舞い、planが設計、tasksがAC対応の完了状態を扱います。P0残件は[Issue #4](https://github.com/reitojike/cinematic-camera/issues/4)へ集約します。
  Draft PR中の成果物は、mergeされるまで現行ブリーフやmainの契約を暗黙に置き換えません。
- [Spec Kit導入・利用](docs/SPEC_KIT.md)を参照し、機能別にspecify → clarify（必要時）→ plan →
  checklist（必要時）→ tasks → analyze → implement → convergeを進めます。標準の生成skillsを直接改変せず、
  必要なカスタマイズはreview可能なpreset/overrideへ限定します。
- Android標準API、Jetpack、lifecycle・権限の標準動作を優先します。標準から外れる理由・代案・範囲は
  対応するspec/planへ記録します。CameraXは指定された安定版1.6.2を維持します。
- [P0_REPORT](docs/P0_REPORT.md)が実機AC・停止条件・結果を、[BUILD_VALIDATION](docs/BUILD_VALIDATION.md)が
  機械的検証の証拠を扱います。指定/受付/実測、保存/DNG有効性を区別し、実機証拠なしで合格にしません。
- 日々の作業状況・確認のやり取りはIssue等で管理します。specは確定した仕様・受入条件、tasksはAC対応の完了状態、
  P0_REPORTは最終検証結果・制約・採否根拠に限定し、時系列の進捗ログをリポジトリ内の文書へ積み上げません。
- PR前は変更一覧と秘密除外を確認し、`./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug --no-daemon`
  と`git diff --check`を変更に応じて実行します。Windowsでは`./gradlew`を`.\gradlew.bat`に置き換えます。
  実際の[Android CI](.github/workflows/android.yml)を合否の根拠にし、未実行・環境失敗は明示します。
- [branch/配布運用](docs/OPERATIONS.md)に従い、初回main初期化後は作業branchとDraft PRを使います。
  現行headのCI・レビュー・未解決会話を確認し、merge許可がなければ停止します。APPROVE代行、admin merge、
  チェック免除、保護回避は行いません。実際の設定状態は[REPOSITORY_STATUS](docs/REPOSITORY_STATUS.md)を確認します。
- [公開前検査](docs/PUBLICATION_REVIEW.md)に従い、秘密・認証情報・署名秘密・個人情報・個人用絶対パス・
  非公開サービス情報・会話ログ・写真実体をGit/ZIPへ入れません。サンプルには実ユーザー名や端末固有識別子を使いません。
- 実機撮影、固定署名秘密の新規生成や継続利用設定、他projectの鍵の流用を無断で行いません。
  AC3/4が非対応なら根拠を残し、独自回避やCamera2全面移行は勝手に進めません。
- LibRaw/NDK、GPU/LUT、動画、SNS、21:9保存、バックエンド、製品UIはP0の対象外です。文書は原則日本語で書きます。
- 後続の範囲・順序は[ライブ検証計画](specs/002-live-capture/plan.md)で確認します。Stage Clearを最優先とし、
  原本保持・写真/動画の入力差を守ります。新計画の存在だけで未依頼の実装・素材撮影・公開・Releaseを開始しません。
