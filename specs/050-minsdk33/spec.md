# Feature Specification: Android 13を最低対応とする

**Feature Branch**: `chore/minsdk33`
**Created**: 2026-11-07
**Status**: Draft
**Input**: #211。最低対応をAndroid 13以上へ限定する本人承認。GPU試作とは独立する。

## User Scenarios & Testing
### User Story 1 - 対応範囲が明確なアプリ (Priority: P1)
利用者はAndroid 13以上でアプリを使い、古い端末は対応対象外と分かる。
**Independent Test**: 生成APKの最低対応が33で、既存の静的検証・単体テスト・ビルドが通る。
**Acceptance Scenarios**:
1. Android 13以上向けのAPKに最低対応33が宣言される。
2. Android 12以前への新規インストールは対象外。既存インストールを変更・削除しない。
### Edge Cases
compileSdk/targetSdk、署名、保存形式とgoldenは変更しない。実機の動作保証は別確認。

## Requirements
- FR-001: 最低対応をAndroid 13に限定する。
- FR-002: 画像処理・製品設定・依存・署名・配布を変更しない。
- FR-003: GPU試作と独立したPRとする。

## Success Criteria
- SC-001: 最低対応の宣言が33。
- SC-002: lint、unit、debug buildと変更検査の結果を明記する。
- SC-003: CIとレビューを経て個別マージ承認待ちで停止する。
