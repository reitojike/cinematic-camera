# Implementation Plan: PC RAW基準現像と比較PoC

**Branch**: `feat/013-p1a-raw-baseline` | **Date**: 2026-11-02 | **Spec**: [spec.md](spec.md)
**Input**: [Issue #49](https://github.com/reitojike/cinematic-camera/issues/49)、[002計画](../002-live-capture/plan.md)のP1基準現像候補。

## Summary

Androidから独立したPC CLIとして、入力非破壊・private出力・固定条件の基準現像／トーン比較と再生成を実装する。
RAW提供の有無を先に確認し、合成経路の成功を実DNG decodeやPixel品質へ読み替えない。Stage Clearは未実装の比較予約枠だけ。

## Technical Context

| 項目 | 採用と理由 |
|---|---|
| Runtime | Windows x86-64 / Python 3.12、専用venv。global環境を変更しない |
| Dependencies | 公式PyPI rawpy0.28.0、NumPy2.5.4、Pillow13.0.0。CPython3.12 Windows wheel SHA256を固定 |
| RAW | rawpy同梱LibRawを用いruntime版・flagsを記録。独自decoderやbinary配布なし |
| Tests | 標準unittest、合成RGB／metadata-only TIFF／mocked decode契約。実DNG品質は別受入 |
| Data | root明示、DNGだけ、リンク／junction非追跡、512MiB／80MP上限 |
| Output | `tools/raw_baseline/.private/`のみ。NPY16bit、比較JPEG2点、小型manifest。EXIF複写なし |
| Scope | tools/raw_baseline、specs/010、private除外の.gitignoreと合成専用raw-baseline.yml。Android/UIは変更しない |

## Constitution Check

- 仕様・Issue追跡: Issue49と本featureへ限定。日々の進捗はroot担当のIssue/PRコメントへ。
- 標準優先: PCライブラリを使い、CameraX1.6.2／Android制御・保存を変更しない。
- 実装と合格の分離: 合成と実素材を分ける。未説明の色転び／clipは画作り停止条件。
- 原本・公開境界: 非破壊読取、private派生、個人パス／GPS／固有ID／写真をGitと外部送信へ出さない。
- 最小依存・段階: 3直接依存のみ。Stage Clear、AI、multi-frame、独自NR、NDK/GPU等なし。
- レビュー: 実装者の検証を独立承認に数えず、commit/push/PR/merge/配布はrootの運用へ渡す。

## Project Structure

```text
tools/raw_baseline/
  baseline.py
  test_baseline.py
  requirements-win-py312.txt
  README.md
  EVALUATION.md
  .private/                 # ignored; inputs are never copied here automatically
specs/010-p1a-raw-baseline/
  spec.md
  plan.md
  tasks.md
  checklists/requirements.md
```

## Research decisions

rawpyはLibRawへの公式wrapperで、固定公式wheelがあるため採用。独自TIFF/RAW decoderは作らず、camera admission用のIFD0 Make/Model/DNGVersionだけをboundedで読み、pixel decodeはLibRawへ任せる。Pillowによる画像全体のDNG読取やExifTool追加依存、ImageIO追加層は不要として採用しない。

基準はAHD、metadata black/white scaling有効、最大値自動調整OFF、自動明るさOFF、露出倍率1、as-shot WB（未知なら停止）、sRGB primaries/D65、gamma1、uint16。JPEG出力だけsRGB区分伝達関数を適用。実WB係数とlevelsをmanifestへ記録する。入力matrix等はLibRawに委ね、camera profileを独自校正と称しない。基準の詳細と一次根拠・licenseは[tools README](../../tools/raw_baseline/README.md)に集約し重複researchを増やさない。

トーンは同RGBへの全画面輝度カーブでRGB比を保ち、範囲超過を計測してからclamp。NR・肌選択・Stage Clearの設計を混ぜない。uint16やgamma1はscene-linear/HDR／無clipの証拠ではない。

## Data model and CLI contract

- 入力: kind（synthetic/dng）、private root相対名（DNGのみ）、SHA256。rootはmanifestに保存せずreplay時に明示。
- 設定: pipeline条件、camera profile（pixel9a/sigmafp/synthetic）、WB、user multipliers、tone strength。
- 観測: black/white levels、camera/daylight/使用WB係数。GPS/serial/撮影日時等は保存しない。
- 環境: Python／rawpy／LibRaw／NumPy／Pillow版、LibRaw flags、platform、toolファイルSHA256。
- 出力: uint16 RGB NPY、baseline/tone JPEG、処理後clip指標、画像3点のhash、Stage Clear未実装、quality未確認。
- run: `private staging → 全出力完了 → hash検証 → run公開`。既存runへ上書きしない。失敗stagingは専用private範囲でcleanup。

CLIはinventory/develop/synthetic/replay。path入力と選択肢の詳細・終了code・再生成はREADMEを契約とする。replayはschema、固定条件、環境、入力hash、出力hashを検証し、不一致なら成功を返さない。外部API／networkはないためcontracts専用文書は増やさない。

## Validation and rollout

US1は生成／replay／privacy／境界／未知WB／I/O／出力重複を合成unitとCLIで検証。US2はknown profile分離、比較枠、明示未実装を確認する。実素材が提供された場合だけ、許可rootからcamera別にimport/develop、原本hash・levels・WB・色／clipの説明可能性を確認する。実素材なしではSC004の実DNG受入を完了しない。

[READMEのquickstart](../../tools/raw_baseline/README.md)を実行ガイドとして兼用する。独立レビュー、公開scan、Draft PRを経て、merge／Android移植／Releaseは別承認。`.github/workflows/raw-baseline.yml`はWindows/Python固定・合成unitのみを実行し、実写真取得・upload・Secrets追加を行わない。既存Android CIや保護設定は変更しない。

## Complexity Tracking

新サービス／DB／独自UI基盤なし。仕様上の例外・未解決の設計質問なし。SIGMA fpの代表decodeは確認済みだが品質受入・Pixel素材・edited作例は未確認につき、機能実装を妨げない実素材受入の残条件として扱う。
