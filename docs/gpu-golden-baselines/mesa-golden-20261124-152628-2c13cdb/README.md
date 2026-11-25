# Mesa 回帰基準（llvmpipe）

Mesa のソフトウェア描画（llvmpipe）で製品のパイプラインを全90件実行した出力の基準です。GPU-A の基準（`golden-20261112-131916-51b691ad`）とは別の基準であり、Mesa の出力同士の回帰判定に使います。

## 起点と環境

- 起点: commit `2c13cdb88948ae6db684c1d799102f50a0aca455`、tree `507784a8e0d65adeb4df5ba79975b7ce098b345b`（#551 マージ後の main）
- Mesa: **26.0.8-1ubuntu0.3**（版を固定した .deb 一式から導入。マニフェストは `tools/gpu-mesa/mesa-debs-manifest.txt`）
- llvmpipe の設定: **未指定**（`LP_NATIVE_VECTOR_WIDTH`、`GALLIUM_OVERRIDE_CPU_CAPS` ともに未設定。renderer の表示は llvmpipe (LLVM 21.1.8, 256 bits)）
- 採取した実行: **full-1**、Actions Run ID **45424947920**、ランナーの CPU は AMD EPYC 7763 64-Core（AVX2 対応、AVX512 非対応）
- capture.json の baselineApproved=false は採取時点の不変フィールドであり、採用の状態とは別です。採用の記録は approval.json にあります。

## 再現性の確認（登録の根拠）

同じ起点で3回の実行を行い、すべて候補の検証に成功しました。

| 実行 | Run ID | ランナーの CPU | AVX512 | llvmpipe |
|---|---|---|---|---|
| full-1 | 45424947920 | AMD EPYC 7763 64-Core | 非対応 | 未指定 |
| full-2 | 45425002238 | Intel Xeon 6975P-C | 対応 | 未指定 |
| full-w128 | 45425098133 | Intel Xeon Platinum 8573C | 対応 | ベクトル幅128 |

- full-1 と full-2 は、全90件で RGB8 が完全一致しました。CPU の機種と AVX512 の有無が違うため、CPU の違いをまたいだ一致です。
- full-1 と full-w128 も、全90件で RGB8 が完全一致しました。ベクトル幅の設定（未指定=実効256 と 128）で出力は変わりませんでした。

## GPU-A のゴールデンとの差の統計

full-1 と `golden-20261112-131916-51b691ad` の比較です。公式ツール `gpu_golden.py compare`（Mesa の候補への対応後）で再計算し、別の集計と全体・群別・ケース別のすべてが一致することを確認しています。

| 範囲 | ケース数 | 異なる画素 | 割合 | 最大差 | 完全一致 |
|---|---|---|---|---|---|
| 全体 | 90 | 8,291 / 43,258,739 | 0.019% | 1 | 9件 |
| small | 58 | 799 / 2,850,816 | 0.028% | 1 | 8件 |
| preview | 18 | 3,444 / 13,564,302 | 0.025% | 1 | 0件 |
| export | 14 | 4,048 / 26,843,621 | 0.015% | 1 | 1件 |

異なる画素はすべて差がちょうど1（8ビットの1階調）でした。帯の比較（export 14件 × 64行・256行 = 28件）は、Mesa の中での帯と全面の一致として、すべて差ゼロでした。

## CP3 の承認の経緯

- CP3 段階1の成果物（3回の実行、差の統計、代表画像、再現性の確認）を本人が確認し、2026-11-25 に、Mesa（26.0.8-1ubuntu0.3、llvmpipe 未指定）の出力を Mesa の基準として登録することを条件つきで承認しました。llvmpipe の設定は未指定のままです。
- 条件の1つ（公式ツールの Mesa 候補への対応と比較のやり直し）は、登録に先立って満たしました。
- mesa ジョブに、この基準との完全一致の判定を加える変更は、ワークフローのパッチとして別に提示します。PR のたびの全件実行を必須判定にするかは、A3（合否判定の移行）で決めます。

## 使い方

新しい Mesa の候補との比較は、公式ツールで行えます。

```text
python tools/gpu-golden/gpu_golden.py compare docs/gpu-golden-baselines/mesa-golden-20261124-152628-2c13cdb <new-candidate> <new-report-directory>
```

基準の自動更新はしません。出力が変わる場合は、場所と大きさを示して本人の確認を得ます。
