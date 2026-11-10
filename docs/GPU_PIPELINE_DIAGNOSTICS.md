# GPU 全工程の診断状態

Issue #284 / Draft PR #319。製品既定の CPU 経路、数式、表示一致基準は変更しない。
R7 → Glow → Mist → Finish の接続は診断用であり、全工程の受け入れは未完了。
Orton 以降の一致判定だけを保留し、前半接続・解放順・メモリ観測を進める。
preview 96MiB、export 192MiB は既存の承認値から増やさない。compute 置換はここに含めない。

## Orton 境界

Mesa の全工程 200 条件行列は 155 条件目で停止した。154 PASS、1 FAIL、45 未実行。
条件は MIXED、256×192、strength 0.5、warmth 0、user EV +1。
正式な全画素比較は DISPLAY8 最大差 5、p99.9=0。最大差 2 の基準を満たさない。

最大位置は (x=248, y=102) の赤。CPU/GPU の Orton 入力はそれぞれ
1.000000238418579 / 0.9999969601631165 で、既存の `image[j] > 1f` 分岐を跨いだ。
分岐前 value は 0.8232096434 / 0.8232077956、分岐直後 mixed は
1.0000002384 / 0.8232077956。保護と amount 適用後の ORTON tap は
1.0000002384 / 0.9557998180、最終表示は 245 / 240。
`value`、分岐直後、ORTON tap を混同しない。

全 49,152 画素のうち、分岐が反対側の画素は 1（1 channel）。表示差があるのは 17 画素で、
他の 16 画素の最大差は 1。CPU/GPU どちらかの入力のいずれかの RGB が
`abs(input - 1) <= 1e-5` を満たす画素は 2（0.0040690104%）。
その 2 画素の全 RGB を診断上だけ除くと、残り 49,150 画素 / 147,450 channel は最大差 1、p99.9=0。
除外は正式な判定に採用していない。全画素基準で FAIL のまま。

採取は CPU の別名テストコピーへ value 保存だけを追加し、実 private mix と実 Orton の全 raw bits 一致を確認。
GPU は原 mixed 出力を維持する診断 MRT へ value 出力だけを追加し、原 shader の mixed と全 raw bits 一致を確認した。
CPU 参照や製品 shader は改変していない。指定エミュレーター結果はまだ未取得。

## メモリの限定観測

1024×769 MIXED の strength 0.5/1.0 × (warmth=0, EV=0)/(warmth=60, EV=+1) の 4 条件について、
全工程の実 shader を Mesa で再生した host 側 texture 計数の最大は 83,301,388B（79.44MiB）、
96MiB まで 17,361,908B（16.56MiB）。終了時 0 を確認した。
最大は Orton の blur 拡大中で、原寸 RGBA32F 6 枚（AS_SHOT、R7_FINAL、bloom、Mist、bright、soft）、
原寸 R32F 2 枚（skin、halation）、縮小 83×63、横軸中間 1024×63、重みテクスチャが生存する。
これは Android wrapper 台帳の実測ではなく、全 200 条件の合格でもない。

## 指定エミュレーターでの採取

既存の isolated 合成試験枠と指定 GPU 構成を使用する。実機・配布・カメラ入力は対象外。

- `OrtonBoundaryDiagnosticAndroidTest` は失敗条件 1 件の全 tap、CPU/GPU value、mixed、表示 8bit を採取する。
  actual pipeline を閉じてから別 ledger で診断再生し、診断 MRT の追加画像を pipeline peak に混ぜない。
  診断完走を画素一致 PASS と扱わず、`formalDisplayPass` を全画素の元の基準で別記する。
- `PipelineMemoryDiagnosticAndroidTest` は最大寸法の上記 4 条件について R7_FINAL / GLOW / MIST の既存 linear 基準と
  Mist 決定値を検証し、全工程の ledger peak、余裕、終了時 0 を採取する。Orton 以後の画素一致は判定しない。
- `StageClearGpuPipelineAndroidTest` の全工程正式基準は変更しない。境界問題の判断が終わるまで全行列の受け入れを主張しない。

合成 raw float / CSV は private な写真ではなく、決定論的 fixture の診断出力。
診断 artefact はテスト対象アプリの専用領域へ保存し、実行時の正確な source commit、GL 情報、試験件数と共に記録する。

### 許容最大形状の追加確認

長辺 1024px という入力契約には面積・縦横比の制限がない。1024×769 の fixture だけでは
1024×1024 の上限を保証できない。正方形では原寸 RGBA32F 6 枚だけで 96MiB に達し、
一時画像・重みを加えると超過するおそれがある。正方形も同じ 96MiB で拒否位置と解放を診断する。
試験のための縮小・新しい入力制限・予算追加は行わない。正方形の結果は未取得。
