# GPU 原寸書き出しの検証と Phase 2（#286 / #276）

## 現在の判断

現行 main は #354 により通常 debug/release の現像を GPU 既定としている。診断 CPU、保存開始時の engine 固定、原本保護・明示 retry/HOLD は維持する。GPU 既定化は 24MP 対応や実機性能の合格を意味しない。

既存計画の管理先は #276 / #286。新規の重複計画は作らず、実行時の条件・結果・停止理由は Issue/PR に記録する。以下の既存 CPU 比較は参考記録であり、追加 24MP 試験は引き続き後回しとする。192 MiB の製品 export texture 予算を増やさず、旧 #286 の 24MP/256 MiB 案を現行製品契約と扱わない。

#348 の CPU 差参考記録化は main に反映済み。#349 は本人が既知の共通黒化を含む現状出力の基準登録を承認した手動回帰基盤の PR で、本計画記述時は未マージ。Mesa 全工程 CI への移行は #290 の D0 として別途進める。#355 の共通黒化は未修正で、本人の方針により実機使用で気になる事象が出た場合に再調査・修正検討する。基準採用を画質正常や黒化解消と呼ばない。

### 既存の受入方針と履歴

2026-11-11の最新ユーザー判断は [#276](https://github.com/reitojike/cinematic-camera/issues/276#issuecomment-6809283080) に記録した。

- **Gate 2の合否はユーザーが実写を見て違和感がないかで決める。**
- CPUとの数値差（near-white count、Mist強度、最終8bit、分位点など）は参考記録であり、合否・停止条件にしない。
- 自動試験は、帯対全画面GPUの一致、shader compile、原本保護・再試行・資源解放を維持する。
- #341の結合試験はこの扱いで再開する。合成AVD・数値結果を実写のGate 2合格に読み替えない。
- 追加24MP試験は後回し。未実施の画像比較や実機確認を済んだ扱いにしない。

当時の通常CPU既定の記録は履歴として保持する。製品数式・処理閾値・192 MiBのexport texture予算は本計画で変更しない。
製品実装・GPU既定化・基準画像更新・実機操作の許可は別途必要。
参照: [#286](https://github.com/reitojike/cinematic-camera/issues/286)、[#338](https://github.com/reitojike/cinematic-camera/pull/338)、[#341](https://github.com/reitojike/cinematic-camera/pull/341)。
エンジンは `9e36037` の実装で、#338の `5bc474d` まで数式の変更はない。

## 判断の時系列

1. 当初はCPUとnear-white count完全一致、tone quantile差1e-4以内、最終8bit最大2・p99.9差1とOrton境目例外で判定した。
2. host 24MPでcount差2を記録し、当時の条件で停止。ユーザー判断で追加24MP条件を後回しにした。
3. AVD 1600×1200の既存全画面GPUでもcount差1が出た。ユーザーは閾値付近のfloat計算によるGPU共通の性質と判断し、
   countを診断記録、auto factor差≤0.005と最終8bitを判定に使う変更を承認した。
   記録: [#286](https://github.com/reitojike/cinematic-camera/issues/286#issuecomment-6809218781) / [#276](https://github.com/reitojike/cinematic-camera/issues/276#issuecomment-6809219806)。
4. **その後、上記「現在の判断」へ更新。** CPUとのauto factor・最終8bitを含め、数値差は参考記録とした。
   0.005や旧8bit上限を自動停止条件として残さない。旧測定値・旧条件での失敗は履歴として保持する。

## 保全した実測

### host 12MP: 4000×3000、64行と256行

実 `OriginalStageClear24` 対実 `GpuExportRenderer`、Mesa llvmpipe GLES 3.2。
既存 GREY / HDR_LIGHTS / SKIN / NOISE の4象限MIXED、strength=0.5、warmth=0、external EV=0。

- CPU near-white count = 3,191,683。GPU64行・256行とも同値。
- tone quantileのCPU最大差 = 1.0728836059570312e-6。
- 両帯の最終8bit全画素最大差1、旧Orton例外除外後最大差1、全画素p99.9=0。
- 旧Orton例外候補151 / 12,000,000画素 = 0.001258333%。Kotlin入力の境目±1e-5を使用し、p99.9には候補も含む。
- 64行対256行は全画素ビット一致。帯境界付近のCPU差も最大1。
- texture台帳peakは64行20,120,804 B、256行75,419,108 B。各6回走査、終了時台帳0。
- 当時の条件では合格。現在はCPU差を参考記録として扱う。大判全画面GPUはこの試験に含まない。
  実12MP DNG保存・実機速度・実機画質の証拠ではない。

### host 24MP: 6000×4000、64行のみ

同じMIXED / strength0.5 / warmth0 / EV0。

- CPU near-white count = **6,379,550**、GPU64行 = **6,379,548**。差2。
- tone quantileのCPU最大差 = 1.7285346984863281e-6。
- 当時のcount完全一致条件で停止。最終8bit判定、256行、全画面GPU、追加の編集条件は未実施。
- 24MP全画面GPUにも同差があるかは未測定。ユーザー判断と実測の有無を混同しない。
- `CompareLargeCpu.kt`、`large-cpu12.log`、`large-cpu-24-neutral.log` と入力生成条件を保全。
  ローカル検証ログ・生データ・個人環境パスはリポジトリへ追加しない。

### AVD 1600×1200: strength0、warmth0、EV0

#341 `2058c1f` の `GpuExportParityAndroidTest` は、CPU count334,133 / 全画面GPU334,132で旧条件に停止。
帯64/256と最終8bit判定に入る前だった。旧ログを保持し、CPU診断記録とGPU同士の自動判定を分けた新headで再開する。

## Phase 2 の24MP比較計画

追加24MP実行を再開する際に資源と範囲を確認する。新たな数式oracleは作らない。

1. 同一の決定論的6000×4000 RGB入力、EV、正規化済みwarmth gains、strengthを固定する。
   入力全体/行のhash、source/GLSL版、GL vendor/renderer/versionを記録する。
2. 実OriginalStageClear24、既存#284全画面GPU、実export64/256行を順番に実行する。
   全画面GPUのメモリとGL上限を先に確認し、不足なら未実施とする。縮小・タイル代替を24MP全画面と呼ばず、製品192 MiB予算を上げない。
3. GLOWのnear-white判定前luminanceとmaskを捕捉し、count、不一致座標、閾値からの距離、RGB/luminance、帯端からの距離を記録する。
   全画面GPU対帯64/256の比較で帯版固有の差を確認する。CPU差だけでは停止しない。
4. tone quantiles、count、実auto factor、strengthを掛けた実amountを別々に記録する。
   auto開始境界・線形区間・飽和境界を含む合成入力で、差の意味を確認する。
5. 最終8bit最大差・p99.9、旧Orton例外候補割合/除外後最大差、帯境界と内部の差を参考記録する。
   CPUとの差をGate 2合否や自動停止に使わず、帯対全画面GPUの一致と資源解放等は自動判定する。
6. 実写のGate 2判断はユーザーに残す。意図した見た目変更や基準画像更新を診断結果だけで実施しない。

## 数値の意味（参考、受入閾値ではない）

現式は `auto = 1 - 0.5 * clamp((count / pixelCount - 0.01) / 0.025, 0, 1)`、`amount = auto * strength`。
線形域のauto差0.005はcount比率差0.00025、24MPなら約6,000画素差に相当する。
今回24MPの両countは飽和域なので式からauto=0.5、strength0.5ならamount=0.25となるが、これはcountからの計算であり未取得の最終8bit結果ではない。
飽和域ではcount差がautoに現れず、後段Orton境目や量子化で表示差が出ることもあるため、各値を分けて残す。

## GPU基準画像回帰の先行と移行

[#290](https://github.com/reitojike/cinematic-camera/issues/290) は、現行GPU出力を対象にAndroidで基準候補を生成・比較し、既定切替を待たず様子見期間と並行して進める。
基準の採用・意図した見た目変更・driver/AVD版変更に伴う再作成は、差の場所と大きさを示した本人の目視確認後に行う。差分を消す自動更新はしない。
GPU/ルック変更PRのマージ前とCPU経路の削除・リファクタリングの各PR前に手動実行する。v0.1.0前のD0でMesaから全工程を呼び、比較をMesa CIへ移す。
来歴・環境・実行記録と承認の詳細は [GPUエンジン契約](GPU_ENGINE.md#gpu基準画像回帰-android先行とd0のmesa移行) を参照する。
製品のGPU既定切替・GLSL正本化・CPU削除・実機操作は、この先行試験の許可に含めない。

このexport段階の参考値化は対象2試験に限定した。広範な旧CPU差の参考記録化は#290前段A、GPU基準画像の先行試験は別の変更として扱う。
旧試験のassert残存は移行範囲の差であり、CPU差をGate2条件へ戻すものではない。
