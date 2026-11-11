# GPU 原寸書き出し Phase 2 検証計画（#286）

## 状態と対象

これは未承認の受入基準案を含む検証計画であり、現行の合否基準を変更しない。
24MP の near-white count 差は記録して Phase 2 に送る。追加の24MP試験は現在実施しない。
12MP 以下を対象とする #338 / #341 の仕上げと Gate 2 準備は別に継続する。
通常ビルドの CPU 既定、製品の数式・閾値、192 MiB の export texture 予算は変更しない。
製品採用・GPU既定化・実機確認の完了を意味しない。

参照: [#286](https://github.com/reitojike/cinematic-camera/issues/286)、
[#338](https://github.com/reitojike/cinematic-camera/pull/338)、
[#341](https://github.com/reitojike/cinematic-camera/pull/341)。
エンジンは `9e36037` の実装を使用し、#338 の `5bc474d` まで数式の変更はない。

## 保全した実測

実 `OriginalStageClear24` と実 `GpuExportRenderer` を用いた host Mesa GLES 3.2 の合成入力試験。
入力は既存 GREY / HDR_LIGHTS / SKIN / NOISE の4象限を組み合わせた MIXED、
strength=0.5、warmth=0、external EV=0。実機速度・実機画質の証拠ではない。

### 12MP: 4000×3000、64行と256行

- CPU near-white count = 3,191,683。GPU 64行・256行とも完全一致。
- tone quantile の CPU 最大差 = 1.0728836059570312e-6（既存上限 1e-4 以下）。
- 最終8bitは両帯幅とも全画素最大差1、例外除外後最大差1、p99.9=0。
- 既存 Orton 例外候補151画素 / 12,000,000画素 = 0.001258333%。
  Kotlin入力が境目 ±1e-5 の既存マスクを使用。p99.9には例外候補も含む。
- 64行と256行の最終出力は全画素でビット一致。帯境界付近の CPU 差も最大1。
- texture 台帳ピークは64行20,120,804 B、256行75,419,108 B。各6回走査、終了時台帳0。
- この大判試験に全画面GPUは含まない。小・中サイズの既存3者比較とは区別する。

### 24MP: 6000×4000、64行のみ

- CPU near-white count = **6,379,550**、GPU 64行 = **6,379,548**。2画素不一致。
- tone quantile の CPU 最大差 = 1.7285346984863281e-6（既存上限内）。
- count 完全一致の既存条件で停止。**最終8bit判定、256行、全画面GPU、追加の編集条件は未実施**。
- 既存全画面GPU由来の差か、帯版固有かは未確認。原因を推定で確定しない。
- `CompareLargeCpu.kt`、`large-cpu12.log`、`large-cpu-24-neutral.log` と入力生成条件を検証証拠として保全。
  ローカル検証ログ・生データ・個人環境パスはリポジトリに追加しない。

## Phase 2 で行う比較

再開は Phase 2 の範囲・実行資源を確認した後とする。数式を再実装した oracle は作らない。

1. 同じ決定論的 6000×4000 RGB 入力、EV、正規化済み warmth gains、strength を固定する。
   入力全体と各行のハッシュ、ソース / GLSL の commit・hash、GL vendor / renderer / versionを記録する。
2. 実 `OriginalStageClear24`、既存 #284 全画面GPU、実 export 64行・256行を同じ入力で順番に実行する。
   全画面GPUの一時メモリ見積もりと GL 上限を先に確認する。必要資源がなければ未実行とし、
   縮小・タイル代替を「24MP全画面」と呼ばない。製品192 MiB予算を引き上げない。
3. GLOW 出力の near-white 判定前 luminance とマスクを捕捉し、CPU / full GPU / 64行 / 256行の
   count と不一致座標を比較する。差分画素の閾値までの距離、RGB / luminance、帯端からの距離を記録する。
   full GPU と帯64/256をビット比較し、既存GPU共通差か帯版固有差かを切り分ける。
4. tone quantiles、near-white count、auto factor、strengthを掛けた実 amount を別項目で記録する。
   値が飽和しているケースだけでなく、auto の開始境界・線形区間・飽和境界を含む合成入力を用意する。
5. 同じ最終8bit変換で最大差 / p99.9、既存Orton例外の割合、除外後最大差を測る。
   帯端前後と内部を分け、差の集中・継ぎ目がないことを確認する。strength=0は既存の例外なし判定を維持する。
6. 実測を添えて採否を判断する。新基準は承認前にテストの assert、製品数式、仕様へ反映しない。

## 未承認案: 24MP の count 完全一致を auto factor 差に置き換える

候補は `abs(GPU auto factor - Kotlin auto factor) <= 0.005`。
これは strength を掛ける前の **auto factor の絶対差**であり、割合の0.5%や count 差0.5%ではない。
適用を24MPに限るか、count完全一致と併用するかを含めて、Phase 2 でユーザーが判断する。
現在の受入基準は count 完全一致のまま。

現式:

```text
ratio = clamp((nearWhiteCount / pixelCount - 0.01) / 0.025, 0, 1)
auto = 1 - 0.5 * ratio
amount = auto * strength
```

影響と注意:

- 線形区間では auto 差0.005は count比率差0.00025に相当する。
  24,000,000画素なら約6,000画素の count 差を許すため、今回の2画素だけを許す小変更ではない。
- 今回の両 count は飽和域にあり、式から両方 auto=0.5、strength=0.5なら amount=0.25。
  これは記録した count からの計算であり、未取得の最終8bitを合格扱いするものではない。
- 飽和域では count の差があっても auto が同じになり得る。autoだけで判定すると上流の欠陥を見逃すため、
  count / 不一致画素の診断記録と full GPU 対帯64/256の照合を残す必要がある。
- strengthを0〜1とすると amountの絶対差は最大0.005、strength=0.5なら最大0.0025。
  後段のOrton境目・clip・8bit量子化で差が増える可能性があり、これだけで画質は保証できない。
- **最終8bitの最大差2・p99.9差1と承認済みOrton例外は維持する案**。
  例外候補はKotlin入力 ±1e-5、割合0.01%以下、p99.9には含める。帯境界差の停止条件も緩和しない。
- 採用する場合は根拠と適用範囲を Issue / PR に記録し、承認後の別変更で受入試験と仕様をそろえる。
