# 原寸現像の要求と成功証跡

## 目的と現状

本契約は原寸JPEG保存・撮影後自動現像の後続設計であり、実装済み宣言ではない。
現在の`AutoDevelopRequest.publishable`は実行前の顔・EV・profile成功を要求するが、
実際のcontrols準備は`JpegGenerationAttempt`のdecode後に行われる。
`AutoRenderReceipt`にはprofile証跡がなく、`AutoDevelopRecord.completed()`には要求値からの補完経路がある。
実FULL providerを登録する前に、処理の宣言と、同attemptで得た成功の証跡を分ける。
新たな画像解析preflight・controller・queueは作らず、既存Runner/backend/attempt/台帳を使う。

現在のFULL登録0、撮影callerのintentなし、1024px private試験、手動bounded契約は維持する。
検証済みprofile・顔・EVを仮の定数や要求値から補完しない。

## 宣言・実行前確認・実観測

以下の名前は契約上の役割であり、採用済みの実装型名やprofile名ではない。

| 区分 | 保持する情報 | 成功を意味しない情報 |
|---|---|---|
| 固定した原寸要求 | capture ID/世代、read-only source URI/FP/権限由来、固定recipe、renderer/input-policy契約、原寸出力方針 | 顔検出結果・実EV・profile成功・完成JPEG |
| 実行前のsource/資源確認 | DNGから読んだ寸法・有効領域・向き、当該source寸法に対する実装能力とメモリ計画 | headerが読めたことは画像処理成功やprofile採用の証明ではない |
| 同attemptの成功証跡 | 下表の実観測と、検査済み出力へのbinding | codecのsRGB判定だけではRAW入力profileの成立を意味しない |

原寸出力方針は、追加のcrop/縮小を加えないDNG有効画像の物理回転後の寸法を対象とする。
有効領域・DNG標準crop・回転の解釈はdecoderの契約版へ固定し、採用前にsource/出力双方の寸法で検証する。
不明な寸法を1や1024で埋めず、入力メタデータや実decoderから取得する。

| 成功証跡 | 必須条件 |
|---|---|
| binding | capture/世代/attempt、source FP、固定recipe、renderer契約が当該要求と一致する |
| source geometry/coverage | 実sourceの有効寸法・向きが分かり、原寸の画素を処理したことをproducerが報告する。縮小入力の拡大では代用しない |
| input profile | 実処理に使ったprofile/変換の識別子・版と成功状態が、採用済みinput-policyを満たす。UNKNOWN/UNSUPPORTED/暫定色は新FULLの成功にしない |
| face | 採用済みdetector識別子・版で実行成功し、検出あり/なしを報告する。ゼロmaskやNOT_RUNを未検出へ読み替えない |
| exposure | 採用済み測光policy識別子・版と実適用EV値（有限・当該policyの範囲内）を保持し、実結果がSUPPORTEDである。診断p97・UNKNOWNを補完しない |
| output | writer/engineの実close後、全byte hash、JPEG codec、sRGB、物理向き1、実寸法・長さを検査し、原寸方針と一致する |

profile/face/測光policyの実ID・版と採用基準は#78/#79の検証で確定する。ここで成功する名前を仮登録しない。
顔検出の「なし」は、採用されたdetectorを実行した結果であり、写真に顔が絶対にないという保証ではない。
顔/測光の座標は当該geometryへ対応させ、その変換もpolicy版に含める。
マスク画像・画像バッファ・顔の特徴量は台帳へ保存しない。

## 共通実行枠と資源の確認

1. 撮影時の固定intentと正常な全形式受領、SHARED_ONLY/同FPを確認し、process ownerが要求を作る。
   intentなしの旧撮影へ後付けせず、Activityの弱参照通知を唯一のauthorityにしない。
2. 実FULL providerが当該宣言を受理できるまで投入を保留し、未対応の実行要求を繰り返し作らない。
3. source/資源確認は既存Runner/backendの原本確認段階と同じslot内で行う。
   boundedなheader/stream確認と、画像全体のdecode/画素配列確保を区別する。
4. 1024用のmemory許可を原寸へ流用せず、実source寸法・native/codec/renderer作業領域を含む当該jobの
   計画が成立した場合だけ重処理へ進む。未確認/不足なら重処理開始0、資源close後に枠を解放し、要求は保全する。
   一時的な不足と恒久的な非対応を区別し、hot retryや黙示縮小を行わない。
5. 顔/EV/profileの画像解析は同じattemptで行い、結果を得るためだけの二重decodeを先行設計にしない。
6. 撮影予約/録画/背景化/熱等の停止を優先し、停止通知をclose ackにしない。
   非協調native/codecの即時停止や撮影への影響ゼロは保証せず、実機測定を有効化の条件にする。

全解像度処理は、妥当性を検証した分割処理でもよい。全画素を一度にFloat32配列へ載せることは要件ではない。
6000×4000ではFloat32 RGB一枚だけで288,000,000 bytesを使う。入力読込と原寸JPEG出力は別々に検証し、
既存の1024表示や1536のkernel-only結果を24MP縦断の合格へ置換しない。

## 永続化と公開ゲート

- 成功証跡を既存の現像台帳へ、出力FP/実寸法と同じattemptに結び付けてcommit/readbackする。
  `CompletedRender`への変換は実証跡を必須とし、要求値や欠落値から成功を生成しない。
- strictな原寸公開契約では、profileを含む必要な証跡を公開台帳にも保持する。
  復旧後も同じ証跡・artifact・公開操作/URIを照合し、不明状態で再render/再insertしない。
- schemaは明示版・bounded値・4KiB aggregate上限・checksum/CASを維持する。
  識別子/版は小さな定義IDとし、任意画像や巨大な処理結果を入れない。収まらなければ切り詰めず保留する。
- 保存成功だけでなく、開始時/公開直前の原本全byte照合・原本不在/権限失効/変化の停止を維持する。
  同attemptへのbindingは非排他的な外部書換えを原子的に排除する保証ではない。
- 私有試験のNOT_RUN/DIAGNOSTIC/UNKNOWNは私有型のままとし、新FULLへ変換しない。
  検証失敗・中断でもDNG/仮JPEG/途中出力を保全し、既存写真は移動・削除しない。

## 旧記録と移行

旧版のFULL記録に実証跡が欠けている場合、新しいstrict契約での公開成功へ補完しない。
読み取れる旧記録の証跡欠落と、破損/未知schemaを区別する。
前者は当該jobの公開を保留し、旧private表示/別jobまで一律に成功へ昇格したり停止したりしない。
後者の不完全inventoryによる保全停止は既存方針を維持する。

既知URI/成功artifact/attempt情報を捨てず、公開途中なら新しい行を挿入しない。
既に公開済みの記録は履歴として保持し、画像を削除/再作成せず、新しいstrict合格とは区別する。
旧manual-bounded/privateは既存の品質・schemaのまま読み、全解像度への暗黙変換は行わない。

## 実装へ進む前の確認と回帰

- #78/#79と要求/証跡のfield・policy ID/版・原寸geometryを合意し、実FULL登録は検証後だけ行う。
- requestに成功値がなくても同attemptの実証跡が揃えば判定でき、どの必須証跡が欠けても公開0となる。
- 6000×4000のcodec出力でも、入力が1024縮小画像の拡大なら原寸成功を拒否する。
- source/recipe/世代/attempt/出力FPの取り違え、profile unknown、face未実行、診断EV、誤寸法を拒否する。
- 証跡commit/readback前後・公開commit応答喪失・close不明・取消・旧世代の故障で原本保持/重複公開0を確認する。
- 旧記録の欠落/未知版/破損を区別し、private互換、既存公開行非再作成、intentなし非投入を確認する。
- actual 24MP入力と原寸出力のhash/寸法/向き/色/画質、ピークメモリ/熱/処理時間、撮影復帰を別に実測する。

この設計だけでrenderer、顔SDK、Pixel profile、原寸出力、自動投入、実機性能が成立したとは扱わない。
