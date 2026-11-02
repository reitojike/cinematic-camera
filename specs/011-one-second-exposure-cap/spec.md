# Feature Specification: 通常MANUAL撮影targetの暫定1秒上限

**Feature Branch**: `feat/014-one-second-exposure-cap`
**Created**: 2026-11-03
**Status**: Draft
**Input**: ユーザー承認の通常撮影最長露光を暫定1秒へ制限する限定変更。既存Issue [#37の今回AC](https://github.com/reitojike/cinematic-camera/issues/37#issuecomment-6584761539) / [#44](https://github.com/reitojike/cinematic-camera/issues/44)を起点とする。

## User Scenarios & Testing

### User Story 1 - 通常操作で1秒以内を選ぶ (Priority: P1)

撮影者は最短SSを保った候補から1秒以内の通常MANUAL撮影targetを選ぶ。
**Why this priority**: 暫定の操作上限を一貫させる中心機能。
**Independent Test**: 候補/新入力の上限と最短端点、CINE fps交差をpure unitで確認する。
**Acceptance Scenarios**:

1. **Given** 1秒超対応のカメラ、**When** 通常SSを選ぶ、**Then** 最長候補は端末能力/最大frame周期/暫定1秒上限の交差内とし、最短SSやISO範囲は変更しない。
2. **Given** 新しい1秒超のmanual入力、**When** 送信を試す、**Then** 拒否しcameraへ送らない。CINEの要求fpsとそれによる既存frame周期制約を維持する。

### User Story 2 - 過去の長秒設定を明示して移行する (Priority: P1)

撮影者は以前保存した1秒超MANUAL設定を、ISOを維持して現在の合法な標準SSへ移行する。
**Why this priority**: 更新後の起動/復帰で隠れた長秒が残るのを防ぐ。
**Independent Test**: 保存入力・復帰target・bindseedの同じ正規化、再bindの冪等性とACK後保存をpure境界/静的照合する。
**Acceptance Scenarios**:

1. **Given** 合法だった旧1秒超pair、**When** 起動/再bind/背景復帰、**Then** ISOを保持し、交差内の最長標準SSへ短縮する。旧値→新targetと要求/受付/実測の区別を示す。
2. **Given** 端末条件内の移行先標準候補がない、**When** 復元、**Then** 架空の1秒pairを送信せず、AUTOまたは停止による復旧を案内する。
3. **Given** 移行要求、**When** 新runtime設定のAPI受付前/失敗、**Then** 移行先を受付済み保存設定や実測一致としない。API受付後だけ移行したpairを保存し、次のbindでも同値を保つ。
4. **Given** AUTOまたは全reset、**When** 復旧、**Then** 既存の手動解除/独立制御保持・全解除の区別を維持し、古い長秒復帰要求を残さない。

### Edge Cases

1秒ちょうど/1ns超過、端末上限が1秒未満、標準候補なし、ISO能力外/保存pair欠落、未受付resume、失敗/rebind、AUTO/reset、旧センサー長秒処理の残待ち。

## Requirements

- **FR-001**: 通常MANUAL targetとSS候補へ暫定1秒上限を共通policyで適用する。端末の実能力を改変しない。
- **FR-002**: 最短SS・ISO候補・CINE fpsと既存frame周期上限を維持する。
- **FR-003**: 旧1秒超MANUAL保存/復帰をISO保持で合法な最長標準SSへ明示移行する。新1秒超入力は拒否し、初期seed/復帰/runtimeで一致させる。
- **FR-004**: 移行先候補がなければmanual送信せず安全に停止する。保存値更新は新API受付後のみとし、実測とは区別する。
- **FR-005**: AUTO/resetと既存所有権/queue/世代処理を維持する。AUTOは手動override解除であり、AE実測露光1秒以下を保証しない。
- **FR-006**: 長秒capturefreeze原因の解消・実機改善と称さない。RAW/GPU/NDK/新権限/鍵/依存変更なし。

## Success Criteria

- **SC-001**: 候補/入力/旧保存/seed/復帰の全定義境界で1秒超の新MANUAL target送信を0件にし、CINE fps/最短SSの変化を0件にする。
- **SC-002**: 旧値/移行要求/受付/実測の混同と、合法候補の捏造を0件にする。
- **SC-003**: unit/build/lint/diffcheck/公開scan、最終head独立/canonicalレビュー・必須CI・thread 0を確認する。今回限定scopeのmerge/配布はユーザー承認の範囲内で親担当が判断する。実機は未確認を維持する。

## Assumptions

本上限は暫定製品policyでありsensor capabilityではない。AUTOのAE露光上限や既存freezeの原因対策は別課題。新規Issue投稿は拒否されており再試行しない。UI PR #48の主値≈実測/詳細診断集約と競合せず、移行要求は既存共通noticeへ一回示し、履歴/根拠は診断へ置く。
