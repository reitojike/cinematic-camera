# P2/P3への引き継ぎ: Stage Clear v1

Stage Clear v1（[spec](spec.md) / [plan](plan.md)）を前提に、P2/P3の作業へ入るための入口。
進捗は各Issueで管理し、この文書には現在の前提と順序だけを置く。

## 確定している前提

- ルックの正本は`tools/raw_baseline/stage_clear.py`の`render(base, faces, strength)`と、
  入力となる第7回の土台`tools/raw_baseline/r7_final.py`の`render`。処理順とパラメータは[plan](plan.md)の表。
- 既定の強度は1。写真ごとに編集で0〜1へ下げられる（ユーザー決定）。強度で変わるのはにじみ・ミスト・Orton・周辺減光・彩度だけ。
- 顔の赤みの抑え・顔の白飛びガード・ハイライトのなだらかさは、どの強度でも常にかかる。
- 顔の範囲は外部の検出器からマスクで渡す。顔が見つからない写真は全体の処理だけになり、手動で補う。
- 露出はルックに含めない。方針は「顔があれば顔で測り、なければ中央の上位3%」。PCの比較は仮の露出。
- 原本（DNG/JPEG）は書き換えない。比較素材・写真ごとの数値・撮影情報はGit/Issue/PRに入れない。
- 第三者のプロファイル・プリセット、リファレンスへの自動合わせ込みは使わない。粒子は既定で使わない。

## 移植の順序（推奨）

1. [#78](https://github.com/reitojike/cinematic-camera/issues/78) Androidへの移植。まず合成入力で`golden/stage_clear_v1.json`と一致させ、次にPixel 9aの実DNGでPCと並べる。処理時間とメモリもここで測る。
2. [#79](https://github.com/reitojike/cinematic-camera/issues/79) 端末の顔検出と顔測光。白飛びガードと二重に暗くならない順序を決める。
3. [#80](https://github.com/reitojike/cinematic-camera/issues/80) 撮影previewの近似。#78の結果を基準に差を比べる。
4. [#83](https://github.com/reitojike/cinematic-camera/issues/83) Stage Dramatic / Cinema T&O。土台と保護処理を共有する。
5. [#81](https://github.com/reitojike/cinematic-camera/issues/81)（P3）編集画面での強度・色温度・明るさ。
6. [#82](https://github.com/reitojike/cinematic-camera/issues/82) 暗所高感度・実ステージ素材での確認。素材が揃った時点でいつでも行う。

## PCでの確認方法

- CI（PC RAW checks）: `tools/raw_baseline`の`test_*.py`。Stage Clearは`test_stage_clear.py`。
  goldenを更新するのは処理を意図して変えるときだけで、版（`stage_clear.VERSION`）も上げる。
- 実写真の比較: `r11_stage_clear.py`（顔検出つきの診断用環境とYuNetモデルが必要）。手順は`tools/raw_baseline/README.md`。

## 未検証・注意

- 暗所高感度（ISO 6400以上）の人物、実ステージのPixel 9a、横顔・隠れた顔。
- 暗い部屋のPixel素材は、中央の上位3%による自動露出で明るく持ち上がり、照明の色が淡く見える。顔測光（#79）で見直す。
- PCの顔検出（YuNet）と端末の検出器は範囲が違い得る。顔マスクの形（楕円の比率）は移植時に比べる。
- 本人の編集と比べて色が合わなくてもよい（本人の方針）。目指すのは照明に浸る雰囲気と、顔の保護。
