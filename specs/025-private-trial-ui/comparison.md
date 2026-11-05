# RAW基準表示との長押し比較

関連: #129 / #101。現像結果を長押ししている間だけ同じ写真の基準表示へ切り替え、離す・中断・遷移で結果へ戻す。

## 定義と受入条件

- 比較元は同じ原本URI・全バイトhash・grantのRAWを、現像結果と同じas-shot暫定色のnative decoderで1024px以下へ再現像した基準表示とする。撮影時WBとSDR sRGB伝達のみで、診断EV・R7・Stage Clearを加えない。RAW本体の直接表示、撮影JPEG、埋込サムネイル、W1/Pixel profile適用済みとは扱わない。
- 結果の私有JPEGをfresh readbackし、比較元も同じ共通実行枠の中で生成する。明示opt-inした同じ選択写真session内では、正常に生成・原本再照合・資源close済みの比較画素を再利用できる。原本参照全体・input/geometry版・向き/実寸法を照合し、現在のrequest binding/epoch・前後の原本確認・fresh JPEGとの一致を必須とする。原本や実寸法が一致しない場合は比較画像を渡さない。別queue・恒久コピー・追加JPEG・公開ownerを作らない。
- UIは同じ画像枠・Fitで切り替え、基準表示中は「RAWの基準表示（撮影時WB・暫定色、露出補正・ルックなし）」と明示する。指を離す、タッチcancel、Back、選び直し、背景化、再生成で基準表示を解除する。
- 比較decode中の撮影優先・停止は、当該native frameのCancellationSignalへ既存のcancel executorから渡す。遅い取消は次のreadbackのframeを止めない。停止要求だけでは枠を解放せず、decode復帰と実際のFD/stream closeを待つ。
- 2枚を同じclaimで引き渡し、未claimの中断では両方をcloseする。claim後はUI向けsoftware copyを2枚作り、queue所有画像をfinallyでcloseする。比較元copyだけの失敗・OOMでは結果copyを維持する。取消では部分copyを両方破棄する。Compose向け画像は画面から外してGC管理し、表示中にrecycleしない。

## 設計と検証

AndroidAutoDevelopBackend.privatePreviewの既存coordinator枠でAndroidOwnedJpegFramesを再利用する。TrialDisplayPixelsで露出補正なしのsRGB表示へ変換する。PrivateTrialPreviewは結果と比較元の両方を所有し、PhotoRollRenderResult.Previewのoptional comparisonBitmapで画面へ渡す。比較元だけの生成/copy失敗では、資源closeの成功・原本fresh照合が確認できた結果画像を維持し、比較を無効にする。取消・原本不一致・close不明は結果全体を返さない。

合成の回転12MP DNGで向き/実寸法・独立native基準との画素一致、結果と基準の区別、同一attempt再読込、原本不変・共有JPEG非生成を確認する。JVMでは比較中の取消の別executor実行・遅延取消の対象保持・実処理終了前の枠保持を確認する。claim後の取消はどちらの画像もrecycleせず、closeで両方を解放する。

再利用miss時は読み込み時にRAWを再decodeするため、表示までの時間と一時メモリが増える。session保持は[034](../034-trial-session-reuse/spec.md)の総16MiB payload上限と失効条件に従う。比較arrayは独立copyから新Bitmapを作り、cacheをUI/queueとrecycle共有しない。最大1024pxのUI copyは2枚で最大8MiBであり、native decode等の一時メモリは別に存在する。実機の処理時間・peak memory・画質・タッチ操作は未検証と区別する。原本変更、共有公開、完成画質への昇格、native/W1の変更は対象外。
