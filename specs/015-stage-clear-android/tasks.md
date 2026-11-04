# Tasks: #78の最初の縦切り

## Phase 1: 境界と契約

- [x] T001 PC正本・handoff・#78を確認し、線形sRGB入力と段階別検証境界・CPU選択をspec/planへ定義する（FR-001/002/006）。

## Phase 2: 合成比較と移植

- [x] T002 PC定数・行列のKotlin生成と全画素合成golden、生成物のPC検証を実装する（FR-001/005、SC-001/002）。
- [x] T003 線形RGB/顔マスク契約と保護処理、顔減光時の前段再実行を純Kotlinで実装する（FR-002/003/004）。
- [x] T004 JVMで全画素、係数、入力保持、不正入力、再実行境界を検証する（FR-005、SC-001/002）。

## Phase 3: 検証とレビュー

- [x] T005 PC/Androidチェック・秘密除外検査を行い、#78へ関連するDraft PRを作り独立レビューを得る（SC-003）。最終headのCI/canonical reviewはPRで確認し、mergeは別承認。

## #78全体の後続（今回の完了に含めない）

- [ ] T006 DNG decode→線形sRGBとR7土台を独立検証する（#78 AC2、plan A/B）。
- [ ] T007 段1〜5を移植し、強度0/0.5/1を全画素で比較する（#78 AC1、plan C）。
- [ ] T008 Pixel 9a実DNGのPC並列比較・表示用/全解像度の時間/メモリ・撮影応答条件を記録する（#78 AC2/3）。

## Phase 4: ぼかし共通基盤（独立PR、T007の一部）

- [x] T009 PC/Pillowの座標・偶数丸め・端・Float32・軸順序を確認し、限定した契約と検証境界を定義する（FR-007/008）。
- [x] T010 BOX中間・BILINEAR復元の合成全画素CSVとPC再照合を追加し、fixture変更時のPC CIを保証する（FR-009、SC-004）。
- [x] T011 純KotlinのFloatPlane、PC互換ぼかしとRGB経路、入力保持/不正入力/境界テストを実装する（FR-007/008/009）。
- [x] T012 PC/Androidの機械検証・秘密除外を行い、Draft PRを作成し独立レビューを得る（SC-005）。最終headのCI/canonical/独立再確認はPRへ記録し、mergeは別承認。

## Phase 5: 段1のにじみ（独立PR、T007の一部）

- [x] T013 PCの段1・skin weight・強度0の境界と保存/URIからの分離を定義する（FR-010/011/012）。
- [x] T014 PC定数生成と肌色重み/段1の合成全画素fixture・再照合を追加する（FR-013、SC-006）。
- [x] T015 OKLab共通計算を共有し、段1を純Kotlinへ移植して既存保護段/ぼかしの互換性も検証する（FR-010/011/012、SC-006）。
- [x] T016 PC/Android検証・秘密除外、Draft PRと独立レビューを完了する（SC-007）。最終headのCI/canonical/独立再確認はPRへ記録し、mergeは別承認。
