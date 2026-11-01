# Tasks: P0.5-A 最小STILL/CINE probe

**Issue**: [#8](https://github.com/reitojike/cinematic-camera/issues/8)

**Input**: [spec.md](spec.md) / [plan.md](plan.md)

対象はUS1とFR-002/003/004/010/011/013/014の限定実装。ルック・crop・製品UIは実装しない。
実装の完了と、V01〜V08の実機合格を区別する。進捗・レビューはIssue/PRコメントで管理する。

## Setup / 基盤

- [x] T001 CameraX1.6.2のcamera-video直接依存と音声permissionを `app/build.gradle.kts` / `app/src/main/AndroidManifest.xml` に追加する。
- [x] T002 能力候補の優先順位・ユーザー希望・fallbackと録画状態遷移を `app/src/main/java/dev/cinematic/p0/VideoPolicy.kt` に定義する。
- [x] T003 出力close/検証/pending公開/失敗cleanupを `app/src/main/java/dev/cinematic/p0/VideoPublication.kt` と `PendingVideoOutput.kt` に実装する。

## US1: 撮影と切替

- [x] T004 quality/dynamic range/組合せ別fpsとsession対応を `app/src/main/java/dev/cinematic/p0/CineSession.kt` で照会し、対応候補だけを構築する。
- [x] T005 Record/Stop/Finalize・音声状態・背景中断・遅延callbackを `app/src/main/java/dev/cinematic/p0/CineRecorder.kt` へ実装する。
- [x] T006 mode別ユーザー設定・露出・動画選択を `app/src/main/java/dev/cinematic/p0/CapturePreferences.kt` で保持する。
- [x] T007 `app/src/main/java/dev/cinematic/p0/CameraProbe.kt` へSTILL/CINE bind、AUTO解除/復元、静止画高解像度と出力metadataを統合する。
- [x] T008 `app/src/main/java/dev/cinematic/p0/MainActivity.kt` へ最小mode/動画候補/音声/録画状態UIと操作時のマイクpermissionを追加する。
- [x] T009 選択/fallback・世代付き録画状態・出力失敗cleanupを `app/src/test/java/dev/cinematic/p0/VideoPolicyTest.kt` / `VideoPublicationTest.kt` で検証する。

## 収束と実機境界

- [x] T010 build/unit/lintと独立レビューを実施し、検証結果と限界を `docs/P05_REPORT.md` へ記録する。最終headのcanonical review/必須CIは運用条件に従いPR/Issueで証跡を管理する。
- [ ] T011 ユーザー操作でV01/V06/V08と高解像度/fps/HDRの実機確認を行い、`docs/P05_REPORT.md` へ最終結果を統合する。自動検証で代用しない。

T001〜T003 → T004/T005/T006 → T007/T008 → T009/T010。T011はユーザー実機確認。
独立したファイルの調査は並行できるが、カメラ/マイクの自動作動は行わない。
P0証拠補完はIssue #4、運用設定はIssue #6。次のAF/WB/LED検証導線は別の限定Issueへ切り出す。
