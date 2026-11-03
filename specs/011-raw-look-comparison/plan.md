# Implementation Plan: PC RAW 4案ルック比較

**Branch**: feat/017-raw-look-comparison | **Date**: 2026-11-03 | [Spec](spec.md)

## Summary

既存固定decodeを再利用する別CLIと静的HTML。基準監査後にprivate recipeから4案を生成する。

## Technical Context

Windows x64/Python3.12、既存固定rawpy/NumPy/Pillow、素のHTML/CSS/JS。新依存なし。
row chunkでfloat配列を制限し原解像度を保持。実素材はCIへ送らない。

## Constitution Check

Issue #54、原本保持、非公開出力、実装/実測分離に適合。Android変更なし。
公開は一般コード/文書/合成テストのみ。Draft PR/独立/canonical reviewとCI、mergeは別承認。

## Project Structure

tools/raw_baseline/look_compare.py、compare_web/、test_look_compare.py。
spec/plan/tasksで受入/設計、CLI手順はtoolsのLOOK_COMPARISON.mdへ集約。

## Design

sceneはroot相対DNG、profile/WB、任意JPEG、手動skin ellipses、肌/衣装/照明boxを保存。
baseline.decodeからlinear sRGB輝度比カーブ、全体/局所EV、彩度、微小RGB gainを適用。
局所は幾何領域とfeatherであり自動人物認識ではない。血色差/陰影/衣装/照明を評価。
入力root外の未作成outputへstaging生成後rename。リンク/重複/脱出を拒否。
保存recipeで新outputへ再生成。環境/コード/source/output hashをprivate manifestに保存。

## Validation

合成neutral/原色/階調/局所境界、再生成、metadata、原本不変、path拒否、JS構文と座標数学。
実素材は独立baseline監査後に生成。local画像目視。既存file拒否は迂回せずHTML未描画を明示。
公開scan/diffcheck後にレビュー。Pixel成立/Stage Clear品質には読み替えない。
