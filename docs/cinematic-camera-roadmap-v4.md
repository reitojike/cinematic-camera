# cinematic-camera ロードマップと作業計画（v0.1.x）

対象：reitojike/cinematic-camera  
前版：[計画 v3](cinematic-camera-roadmap-v3.md)（履歴として保持）  
基準：[v0.1.0](https://github.com/reitojike/cinematic-camera/releases/tag/v0.1.0)（ソース `2d14f981`）

## 1. 最優先：保存時の中断確認を軽くする

- [#475](https://github.com/reitojike/cinematic-camera/issues/475)：12MP 合成 DNG の AVD 診断で保存1回につき中断確認62,062回、永続状態の照合66,076回（累積104.62秒）。累積は並行区間の重複や待ちを含み、短縮見込みではない。公開コピーにも確認があるが、診断では公開中の原本照合の寄与がより大きかった。計測有無で JPEG はバイト一致。Pixel の背面保存が遅い理由や改善幅は未確定。
- 初回候補は、永続状態の照合を 50ms／100ms 間隔で間引く案。メモリ上の取消印は毎回確認する。帯・工程の境界、公開直前、journal 書き込み前は間引かず毎回永続照合し、照合内容と順序は変えない。置き換え検知の最大遅延が許容上限内に収まることを試験で実証する。効果または安全条件が不足する場合に限り、メモリ上の世代方式を次候補とする。この変更は v0.1.0 に含まれない。
- 間引きは `DngContentVerifier.checkContinue` と、公開側・所有者側の確認にも適用する。公開時の原本照合 5 回そのものの削減は別件とし、初回候補に混ぜない。正式 APK の依存・マニフェストも含めた書き込み元・プロセス境界の確認を維持する。
- 取消の永続化、置き換え、復旧承認、再試行、[#365](https://github.com/reitojike/cinematic-camera/issues/365)・[#377](https://github.com/reitojike/cinematic-camera/issues/377) のプロセス終了・再起動時の回復、12/24MP の JPEG バイト一致を先に確認する。その後、同条件の AB/BA で性能を比較し、差分規模・試験結果・時間を示して採否を決める。
- #464 の Pixel 確認回までに候補の安全試験が済み実機確認可能なら、同じ 12MP・同じ設定で前面と背面の保存を各1回追加する。事前に #464 と一緒に手順と操作時間を示し、実機操作の承認を得る。候補が間に合わなければ #464 を待たせない。
- その後、16bit 入力＋シェーダー変換（画素一致を確認し、不一致なら差を示す）、解析用縮小のネイティブ化、JPEG・公開の書き込みや照合の調査、背面時の CPU 割り当てと ADPF の小実験を順位付けする。いずれも未採用。

## 2. 別枠で採否を決めるもの

- [#464](https://github.com/reitojike/cinematic-camera/pull/464) の FULL／half は別回で実写品質と実機性能を分けて比較する。採用までは FULL 既定・原寸 AHD 保存を維持。half 採用なら PPG は不要。非採用の場合のみ [#441](https://github.com/reitojike/cinematic-camera/pull/441) で `dcraw_process` 区間だけ AHD と PPG を比較する。追加の見た目比較はしない。
- [#406](https://github.com/reitojike/cinematic-camera/pull/406) は製品外の診断のまま。合成確認済み。実機のリアルタイム処理時間とプレビューへの影響を測った後に採否を決め、製品依存への追加は先行しない。
- 埋め込み JPEG の追加診断（[#241](https://github.com/reitojike/cinematic-camera/issues/241)）は既存上限・構造検査・フォールバックを維持。現像 owner の prewarm、Mist の同期除去は別変更。保存 GPU owner は再利用しない。

## 3. 継続する回帰と限定残件

| 追跡先 | 残る範囲 |
| --- | --- |
| [#469](https://github.com/reitojike/cinematic-camera/issues/469) | 12MP 保存の条件別安全性と資源解放の不足証拠。合成と実 OS の挙動を分ける。 |
| [#470](https://github.com/reitojike/cinematic-camera/issues/470) | D2 入力・GL 境界分離と固定版 Mesa の全工程 golden CI。製品と同じ shader・renderer を使う。 |
| [#471](https://github.com/reitojike/cinematic-camera/issues/471) | 試験環境の再発と開始前の台数・用途・所有者確認。環境失敗と製品失敗を分ける。 |
| [#415](https://github.com/reitojike/cinematic-camera/issues/415)・[#416](https://github.com/reitojike/cinematic-camera/issues/416)・[#417](https://github.com/reitojike/cinematic-camera/issues/417) | DNG 再取り込み、SIGMA 互換条件、大判 GPU 帯書き出しの一致と資源収支の不足分。 |
| [#451](https://github.com/reitojike/cinematic-camera/issues/451) | 短画面・文字拡大時の現像操作への到達性。低優先。 |

## 4. 運用改善（製品の性能改善とは別）

- [#476](https://github.com/reitojike/cinematic-camera/issues/476) は #475 と並行する。**次回公開前に必須なのは、新旧 versionCode メタデータの互換対応のみ**。新形式の検証可能なメタデータ資産を優先し、旧版の本文内メタデータにフォールバックする。混在履歴でも単調増加を確認し、欠損・不整合時は停止する。
- Draft Release（または非公開署名成果物）で固定ソース・署名・SHA-256・versionCode・承認済みノート全文を照合し、明示承認後に同一成果物を公開する仕組みは後続。次回公開の必須条件にしない。既存の署名 Environment と main 保護を維持する。

## 5. 守る条件

- 元の DNG と既存 JPEG を変更しない。私有一時出力を検証して公開し、不確定な保存は保留とする。自動再保存・二重公開、取消・置き換え後の旧処理による公開を防ぐ。
- Android の GPU 90 条件 RGB8 回帰・28帯比較・保存安全性試験を維持する。CPU 経路や CPU 一致ゲートを戻さない。意図的な出力差は提示し、本人確認前に基準画像を更新しない。合成や AVD の結果を実機性能・互換性の保証とはしない。
- 計測は端末内にとどめ、共有は匿名集計のみ。実写真・URI・端末識別情報・未加工ログは公開しない。
- 実機操作・APK 配布・正式公開は都度承認。作業の担当・環境を確認し、AVD やビルドの競合を避ける。
