# #377 統合の試験境界

提案中の Draft 検証表。合成 JVM の例外・再生成は OS kill / LMK の証拠ではない。
ローカル Gradle / AVD / 実機は #260 の計測優先により停止中。

## #365 後続の取り込み

`f30592c07b0611d0ec9da4648dee161da2627941` を統合。`7ca7892` 以後の独自差分は
`c4cfc98` の取消再試行 STARTED(N+1) 確定後・producer 開始前の host kill 試験追加。
owner / factory / storage の共有 API 変更はない。#376 の editor 解消は双方とも
`gpuPhotoRollProblemMessage` と `metadataRestored || exportPaused` を保持し、一致した。
自動 merge による editor の追加変更はゼロ。#365 側の実行結果を #377 の合格には転用しない。

## 安全な pending の範囲

完全 byte hash 一致の pending のみ、同一 URI の標準 PUBLISHING に接続する。
空・短い prefix・異内容は PENDING_BYTES_DIFFER として保留し、原本・旧記録・行を保持する。
この finding は「部分的に正しい JPEG」の証明ではない。private の有無から未公開と推定しない。
COPYING へ巻き戻すと既存行の truncate/rewrite を導入するため、この救済では行わない。
安全な追記方式や上書きへの同意 UI は未設計・未承認であり追加していない。
既存の非 UNKNOWN publisher の同一行再開契約は変更しない。

## 境界と確認する不変条件

| 境界 | 合成試験・準備 | Android host kill / UI |
| --- | --- | --- |
| intent / private 完成前後 | 既存 owner と intent-only HOLD、明示新要求モデル。旧 intent 保持 | 未実行。seed と cold-open を別 process に分離する |
| 元 journal / insert / copy / close | 既存 publisher 試験、照合の空・全 prefix・改変・資源 close 故障 | 未実行。各境界で旧 URI / bytes / journal を保存し再起動後に比較 |
| receipt / recovery seed | replace 前・commit 後 ack・readback・合成終了の再照合 | 未実行。公開 receipt 後 seed 前でも案 B 拒否 |
| publish / 最終 journal ack | 同一 URI の再開、公開後 journal 故障でも publish 一回 | 未実行。provider 成功とアプリ ack を区別 |
| consent / cursor / 新 intent | 固定 key・次世代、二重操作・ack 喪失。既存 owner intent に接続 | factory 試験を準備済み。実 process 終了は未実行 |
| cold metadata / Compose | explicit READY の固定設定と prepare / backend 世代 fence を静的確認 | 未実行。recipe と要求強度が異なる seed、表示後復元、操作なしで intent 不生成 |
| FGS / retained owner | prepared index に新 handle、旧 authority 拒否を factory fixture に追加 | 未実行。background / foreground / 二重タップ中の slot と通知 owner の同一性 |
| #365 取消証明 | 取消と UNKNOWN の分離、STARTED(N+1) 試験を取り込み | #365 単体結果と統合結果を分ける。#377 統合は未実行 |

権限喪失・複数候補・旧 codec・原本 hash / 世代変更は保留を確認する。照合後に
byte / owner / path / trashed が変わった場合も新規 insert・copy・publish を認めない。
試験用 DNG / JPEG は合成のみ。原本・履歴の削除で fixture を成立させない。

## 完了条件

最終 head の CI・canonical review・独立レビューを別々に記録する。
限定 JVM 約3〜5分、準備済み Android factory 約5分は計測終了後の資源調整待ち。
全境界 host kill / Compose / FGS はこの時間見積もりに含まない。
#377 全体および #260 通常配布版の判定緩和に必要な同版／先行導入 gate は未達。
マージ・配布・実機 LMK の実施をこの表は許可しない。
