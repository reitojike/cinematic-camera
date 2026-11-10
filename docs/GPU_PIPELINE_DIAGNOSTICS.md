# GPU 全工程の診断記録

Issue #284 / Draft PR #319。製品既定のCPU経路と数式は変更しない。表示判定はユーザーが承認したCPU-only Orton境界例外に従う。
R7 → Glow → Mist → Finish の接続は診断用。本記録は各候補の限定結果であり、最新の受け入れ・merge状況は [PR #319](https://github.com/reitojike/cinematic-camera/pull/319) で管理する。
Orton以降は新しい正式判定で検証を再開する。全行列の合格と前半接続・解放順・メモリ観測は区別する。
preview 96MiB、export 192MiB は既存の承認値から増やさない。compute 置換はここに含めない。

## Orton 境界

Mesa の全工程 200 条件行列は 155 条件目で停止した。154 PASS、1 FAIL、45 未実行。
条件は MIXED、256×192、strength 0.5、warmth 0、user EV +1。
当時の全画素比較はDISPLAY8最大差5、p99.9=0で、旧最大差2を満たさなかった。

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
このCPU/GPU union除外後p99.9は診断値。後のユーザー承認はCPU-only対象を最大差だけから外すもので、この旧診断の定義は正式判定へ使わない。

採取は CPU の別名テストコピーへ value 保存だけを追加し、実 private mix と実 Orton の全 raw bits 一致を確認。
GPU は原 mixed 出力を維持する診断 MRT へ value 出力だけを追加し、原 shader の mixed と全 raw bits 一致を確認した。
CPU参照や製品shaderは改変していない。
指定Android（cf159f2 debug）も同画素の分岐逆転を確認し、GPU入力0.9999984502792358、value0.8232093453407288、
mixed0.8232093453407288、ORTON0.955801248550415、表示240となった（Kotlin表示245）。
全画素最大5・全RGB p99.9=0、表示差11画素。CPU採取・GPU MRT・再生のraw bits一致、両台帳終了時0。

その実測を受けた正式判定は[GPU_ENGINE §4.11](GPU_ENGINE.md#411-正しさの検証)を参照。
Kotlin MISTだけのanyRGB±1e-5対象は2画素（0.0040690104%）、対象外最大1、全RGB p99.9=0で、
Mesa/Androidともこの1条件は新基準PASS。
その後2e0a2aaで全200条件Mesaを新規実行し全PASS。実行対象74ソースhash一致、実Kotlin判定helperと
独立histogram集計200件一致。全画素最大5、対象外最大1、全RGB p99.9最大1。
対象は延べ245画素/34条件、最大比率0.0081380208%。上流linear最大正規化差1.4722347e-5、
局所分位点最大差3.0994415e-6、Mist差最大5.0862631e-5で旧基準内。
20写真のclose後0。このMesa記録は指定Android200条件や最終headの受け入れの代替ではない。

## メモリの限定観測

1024×769 MIXED の strength 0.5/1.0 × (warmth=0, EV=0)/(warmth=60, EV=+1) の 4 条件について、
全工程の実 shader を Mesa で再生した host 側 texture 計数の最大は 83,301,388B（79.44MiB）、
96MiB まで 17,361,908B（16.56MiB）。終了時 0 を確認した。
最大は Orton の blur 拡大中で、原寸 RGBA32F 6 枚（AS_SHOT、R7_FINAL、bloom、Mist、bright、soft）、
原寸 R32F 2 枚（skin、halation）、縮小 83×63、横軸中間 1024×63、重みテクスチャが生存する。
旧cf159f2のAndroidはNONEのstrength0.5/1.0の2条件で同じ83,301,388B・終了時0を確認した。
次の(60,+1)prepare時に192MiB Java heapのOOMとなり、残2条件は未実行。GPU予算超過ではない。
87202fcではテストだけimage/adjustment/strengthを非inline関数に分け、次prepareへ旧CPU参照が残らない構造にした。
旧baselineとテスト保持寿命修正版は分けて記録し、heap・式・GPU予算を変えず再実行する。

## 指定エミュレーターでの採取

既存の isolated 合成試験枠と指定 GPU 構成を使用する。実機・配布・カメラ入力は対象外。

- `OrtonBoundaryDiagnosticAndroidTest` は失敗条件 1 件の全 tap、CPU/GPU value、mixed、表示 8bit を採取する。
  actual pipeline を閉じてから別 ledger で診断再生し、診断 MRT の追加画像を pipeline peak に混ぜない。
  新候補では承認済みCPU-only表示判定を別記し、全画素最大と全RGB p99.9も保存する。1条件の成功を全行列合格と扱わない。
- `PipelineMemoryDiagnosticAndroidTest` の現候補は、承認された4形状（1024×768、768×1024、1024×576、886×886）×
  2調整×2強さの16条件について R7_FINAL / GLOW / MIST の既存linear基準とMist決定値を検証し、
  全工程のledger peak、余裕、終了時0を採取する。別testで1024²、1024×769、769×1024、1025×4を
  allocation前に明示拒否する。Orton以後の画素一致は判定しない。
  cf159f2の旧testは1024×769の4条件と正方形予算診断であり、現候補の試験内容と混ぜない。
- `StageClearGpuPipelineAndroidTest` は承認済み境界最大差例外・全RGB p99.9・比率上限と毎回の全対象ログを適用し、200条件を再検証する。

合成 raw float / CSV は private な写真ではなく、決定論的 fixture の診断出力。
診断 artefact はテスト対象アプリの専用領域へ保存し、実行時の正確な source commit、GL 情報、試験件数と共に記録する。

### 旧baselineの問題と承認された入力上限

cf159f2時点の旧入力契約は長辺1024のみで、面積制限がなかった。1024×769のfixtureから
1024×1024の最大メモリを保証できず、原寸RGBA32F6枚だけで96MiBに達する点を報告した。
この点を受け、ユーザーは長辺1024以下かつ786,432画素以下をGPUプレビューの入力契約として承認した。
通常撮影の4:3/16:9は従来どおり、近正方形だけ入力縮小段階の共通倍率とfloorで面積上限に収める。
正方形例は886×886。CPU通常プレビューと書き出しは変更せず、GPU側では決定済み寸法を受け取り、
超過入力を黙って再縮小せず明示拒否する。

旧1024×769のAndroid台帳値はcf159f2で照合し、履歴として保存する。現在の最大メモリは上記4形状で
改めて測定する。preview96MiB、export192MiB、数式は不変。
新4形状Mesaは16条件・R7/Glow/Mistの48比較PASS、台帳終了時0。最大/96MiB余裕は
1024×768:83,094,576/17,568,720B、768×1024:83,092,528/17,570,768B、
1024×576:62,373,256/38,290,040B、886×886:82,968,592/17,694,704B。
1328生成/2656生成解放イベントの独立再計数も一致。87202fc / gpuDiagnosticの指定Androidでも16条件と超過4拒否を完走（2 tests、failure/error/skip各0）。
全4形状が上記Mesaのpeak/余裕と完全一致し、前半一致・close0。CPU参照保持寿命修正後はOOM再発なし。
メモリは後半も含み、全16条件でORTON/CHARACTER/SKIN_CAP/SHOULDER/DISPLAY8の到達を回収JSONで確認。
1024×768ではMIST時点のpeak76,223,392BからOrtonで83,094,576Bへ増え、DISPLAY8まで同じpeakを保持する。
MIST tapの指定は読み戻し対象だけであり途中停止ではない。Window present/swap・ドライバ内部メモリは対象外。
超過拒否はすべてGPU allocation前でpeak0。新正式判定候補の最終head検証とは区別する。

### 寿命の最小改善

blurが自分で作った縮小画像を第1拡大軸後に解放し、Glowの第2accumulatorを最初のblurが完成するまで
生成しない。public resizeの借用契約、shader、式、演算順、texture生成回数は変えない。
依存側の単体Mesa80条件180比較と所有権shim32試験で確認済み。1024×769のGlow/Mist単体peakは
78,008,636Bから76,278,488Bへ1,730,148B減少した。統合全工程の新4形状Mesa値とAndroid台帳の一致は上記のとおり。
