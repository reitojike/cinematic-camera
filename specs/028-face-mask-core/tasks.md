# Tasks: 固定bboxからの保護FaceMask

入力: [spec](spec.md) / [plan](plan.md)。#79の限定作業。新しい基盤や検出器は作らない。

## Phase 1: Setup

- [x] T001 最新mainと#122の固定bbox/mask・PC fixture境界を照合し、spec/planで対象外を固定する。

## Phase 2: User story 1（P1）

- [x] T002 [US1] `app/src/test/resources/detected_face_masks_v1.csv.gz`と`tools/raw_baseline/export_android_face_masks.py` / `test_android_face_masks.py`を再利用し、検出器/BGR生成を含めないPC基準を固定する（SC-001）。
- [x] T003 [US1] `app/src/main/java/dev/cinematic/p0/raw/DetectedFaceMasks.kt`へbboxとmax合成を移し、宣言済み枠・上限・非接続の契約を明記する（FR-001〜004）。
- [x] T004 [US1] `app/src/test/java/dev/cinematic/p0/raw/DetectedFaceMasksTest.kt`でPC全画素、不正境界、空/非空の協調停止、入力/出力の不変性を検証する（FR-005、SC-002）。

## Phase 3: 検証・レビュー

- [ ] T005 PC合成検証とfocused JVMテスト、利用できる単独実行枠でAndroid lint/unit/buildを行い、差分/秘密除外とSpec Kit整合を確認する（SC-003）。
- [ ] T006 独立レビュー、Draft PR、最新head CI/canonicalとthreadを確認する。権限/環境の未実行は具体的な結果と共に引き渡す。

## 依存と進め方

T001 → T002 → T003 → T004 → T005 → T006。既存prototypeの移植であり、既存検証を新規TDDのredとして偽らない。
同じmask/fixtureを触る作業は直列にする。他担当の保存・原寸診断・編集UIファイルを編集しない。
重いbuild/AVDの並行実行例は設けない。MVPは固定bboxの幾何境界だけで、productionへの接続やmergeは別承認。
