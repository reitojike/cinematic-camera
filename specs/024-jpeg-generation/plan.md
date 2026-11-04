# 設計

新namespace `dev.cinematic.p0.jpegrender`に限定する。raw/native、rawcapture、photorollを編集しない。
Android標準Bitmap/ContentResolverと既存AndroidJpegInspector、R7Base/StageClearLookを再利用し、新依存なし。

## 接続

- `JpegGenerationRequest`: source/captureId/generation/recipe。recipeはinput contract、controls contract、1024上限、strength、qualityをcanonical payloadへ固定する。
- `JpegFrameSource`: 同じ原本から物理向き適用済み線形sRGBとsource寸法を返す。最初のAndroid adapterはDngPreviewDecoderのas-shotを利用する。W1専用の再実装はしない。
- `JpegControlsProvider`: decodeされた同寸法画像から外部で確定したEV、foundation ROI、face maskと#118の顔/露出状態を返す。契約IDをrecipeと照合。defaultの顔/露出providerは登録しない。
- `PrivateJpegTarget`: caller所有のprivate attempt。captureId/generation一致、createOutput/openRead。path/URIをrendererの出力引数にしない。公開・削除はしない。
- `JpegGenerationAttempt`: single-use/single-flight。resource trackerで全source/JPEG reader/writerのclose失敗を保持し、`hasUnreleasedResources`/`releaseResources`を制御側が確認する。
- 成功は#118 CompletedRender。固定recipeと実codec output、providerの検査済み顔/露出状態を返す。登録集合はcallerが明示する。

bitmap encoderは行ごとにsRGB/ARGBを変換する。JPEG圧縮はhash/byte上限/stopを持つstreamへ直接出力する。encoder自身はJPEG全体のbyte配列を作らない。
codec検査の既存8bit/SDR sRGB・未設定/normal orientationを維持する。画像は再回転せず、入力Exifをコピーしない。
成功前にsourceを再照合。失敗出力のcleanup/attempt再作成は制御側の権威に任せる。

標準から外れるものは追加しない。encoder中止はstream callbackと復帰後の確認であり、ネイティブ計算中の即時中断ではない。
decoder Readyはそのread-only FDの通常closeが完了した境界として扱い、decoder本体のresource契約を二重実装しない。

## 検証

pure JVMでrecipe/inputs/target一致、fake encoder/inspector/streamによるfault、close再試行とsingle-useを検査。
Android合成で実codec、非正方形/Exif、原本hash、bounded1024、cancelと誤入力を確認。
Spec/plan/tasksの整合・公開除外を独立レビューする。実機/品質・公開権威の残件を登録済みにしない。

JPEG codecには全画素ARGB配列を渡さず、行IntArrayとBitmapのみを用いる。既存AndroidJpegInspectorは検査時に圧縮データのByteArrayOutputStream/byte[]と最大64pxのBitmapを保持する。入力streamは実際の符号化byte数（最大8MiB）で制限し、全attemptがbyte配列を使わないとは扱わない。
