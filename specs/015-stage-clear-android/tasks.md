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
- [x] T007 段1〜5を移植し、強度0/0.5/1を全画素で比較する（#78 AC1、plan C）。
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

## Phase 6: 段2のブラックミスト（独立PR、T007の一部）

- [x] T017 PCのブラックミスト・線形輝度の自動強度・元baseの肌色重み保持・後続Ortonへの強度受渡しを定義する（FR-014/015/016）。
- [x] T018 PC定数生成と単体/段1→2の合成全画素fixture・再照合を追加する（SC-008）。
- [x] T019 段2を純Kotlinで移植し、強度/面積の境界・保護・入力保持・不正入力と既存段を検証する（FR-014/015/016/017）。
- [x] T020 PC/Android検証・秘密除外、Draft PRと独立レビューを完了する（SC-009）。最終headのCI/canonical/独立再確認はPRへ記録し、mergeは別承認。

## Phase 7: 全体接続後の性能比較に備える整理

- [x] T021 copy/一時配列の箇所と12MPの単一配列容量、標準API候補と精度上の論点、標準ツールによる測定手順をresearch.mdへ整理する。容量算術を実測peakにしない。
- [x] T022 文書の参照・公開除外とレビューを確認し、性能未測定を明記したDraft PRで報告する。最終headのCI/canonical/独立確認はPRへ記録、mergeは別承認。
- [ ] T023 現行方式で全体をつないだ後、独立調査と照合し、既存RAW作業に干渉しない最小の測定入口・受入予算を選ぶ。新依存/算法変更を自動導入しない。
- [ ] T024 承認した測定入口でホスト/Pixelの表示用・12MP時間/観測peak/GC等を測り、goldenと撮影応答を分けて候補を判断する（T008の性能残件）。未実行を合格にしない。

## Phase 8: 段3のOrton（T007の一部）

- [x] T025 雰囲気/印象を最終目標とし、現行方式の接続後に画像変化と性能を比較する方針をspec/plan/researchへ反映する。厳密goldenを永続制約にしない。
- [x] T026 Ortonの段2量の受渡し・display転送/明部fade・肌保護・負値/HDR境界を定義する（FR-018/019）。
- [x] T027 PC定数生成と可逆gzipの合成全画素fixture、PC再照合を追加する（FR-020）。
- [x] T028 純Kotlin実装と単体/段1→3・入力保持/不正入力の検証を行い、既存goldenも維持する（SC-010）。
- [x] T029 PC/Android検証・公開検査、Draft PRと独立レビューを完了する（SC-011）。最終headのCI/canonical/独立再確認をPRへ記録し、mergeは別承認。

## Phase 9: 既存合成fixtureの保存形式（#98）

- [x] T030 Issueの重複を確認し、可逆圧縮/生成属性と対象外をIssue・spec/planへ定義する（FR-021/022）。
- [x] T031 Git blobをgzipへ変換し、解凍bytes/SHA-256一致を確認する。PC/JVM読込・生成・CI pathsを更新する。
- [x] T032 一時CLI再生成→展開/再照合、既存PC/JVM全画素と属性・公開検査を検証する（SC-012）。
- [x] T033 独立レビュー・公開検査と別Draft PRの準備を完了する（SC-013）。最終headのCI/canonical/独立再確認をPRへ記録し、mergeは別承認。

## Phase 10: 段4/5とR7 baseからの組立て（T007）

- [x] T034 段4/5・R7 base入力の全段組立て・顔減光時の再実行境界を定義する（FR-023/024/025）。
- [x] T035 PC定数/合成gzipと段4/5単体・全段の強度0/.5/1再照合を用意する（SC-014）。
- [x] T036 純Kotlinの段4/5と既存各段の組立てを実装し、全画素/顔係数/入力保持/不正入力を検証する。
- [x] T037 PC/Android検証・公開検査と独立レビュー、Draft PRの準備を完了する（SC-015）。最終headのCI/canonical/独立再確認をPRへ記録し、mergeは別承認。

- [x] T038 表示用rendererの停止シグナルを既存全段呼出しへ接続し、開始/途中/顔減光再実行/返却前停止と入力保持を検証する（FR-026）。最終headのCI/独立レビューを再確認する。

## R7共通土台（T006の一部）

- [x] R7-T001 公開共通recipeと外部EV/skin ROI、decode/W1の分離を定義する（R7-FR-001/003）。
- [x] R7-T002 BICUBICを共通resizeへ追加し、R7の6段を純Kotlinへ移植する（R7-FR-001/002）。
- [x] R7-T003 PC既存rendererと同じ共通設定・合成ROIから全画素fixtureを生成し、各段/不正入力/入力保持を比較する（R7-FR-003、R7-SC-001）。
- [ ] R7-T004 最終headのPC/Android CI、canonical/独立レビューを確認しDraft PRへ記録する。実DNG入力・ROI供給・W1・実機を完了扱いにしない。

## 高解像度化に向けたメモリ（#78）

要件・設計とMEM-T001〜004の完了境界は[buffer-memory.md](buffer-memory.md)を参照。既定1024の引き上げやPixel実機の合格とは分ける。
