# #143 合成12MP/24MP基準JPEGの計測

- FR001: 小型候補の次段階として、専用診断APKだけで12MP、次に24MPを順に計測する。公開承認済みの診断変更をDraft PRでCI/レビューへ出すが、mergeは別確認とする。実写、実機、撮影/保存UI、Stage Clear/製品FULLは対象外。
- FR002: native64MiB入力/格納24MP/各辺8192/LibRaw512MiBを診断固定上限とする。小型上限と製品16MP/1024/128MiBの経路を維持する。標準crop/回転後の原寸実寸法と全画素pack数を確認する。
- FR003: 大画像はRGB16 oracleのJava転送、JPEG全ByteArray化を禁止する。原本hash/出力readback hashは4KiB streaming、encoder Bitmapは実close後に解放してから標準codecで原寸再読込する。書込全byte SHA/寸法/sRGB/方向を検証する。
- FR004: 時間・managed/native/PSS観測最大・段階別snapshotを同じattemptで記録する。サンプリング最大と真のpeak、AVDとPixel、基準JPEGとルック適用を区別する。固定入力生成は行単位でstreamingし、計測時間の外に置く。
- FR005: 各寸法で部分write、close不確実、pack取消の成功偽装なし・実owner保持/解放・原本不変・私有途中出力保全を確認する。OOM/環境障害は失敗として記録し、条件変更して自動再試行しない。
- FR006: ホスト空き資源を確認しビルド/AVD/ケースを直列実行する。資源不足・他の重い作業・端末lowMemoryで止める。独立レビューとローカル検証後に結果を保全し、Draft PRのCI結果とAVD実行/実機未検証を区別する。公開対象はコード/生成器/手順/匿名化した測定集計とし、画像・APK・生ログ・秘密はGitへ入れない。
