# 空ROIの R7 foundation 有界行並列候補

## 結論

**製品には採用しない。既定値は1workerのまま。** 合成1024×768・同一APK・process-coldのABBAを共有GMDで4回実行したところ、foundation工程は2workerの両回とも1workerより長かった。今回はこの候補のMVP検証をここで区切り、追加方式やGMD試験へ広げない。PRはDraftであり、merge承認ではない。

測定コードは `2764412f3eff26d9fc827bd71c8de55f60607321`、treeは `385fb513c754479280a5663c684e26da23ffb1f1`。元候補 `b011fdd654984ab37b9413f382463bc565fa0572` に main `858b99ec7c0a0dc2806a09ec0ebcaad2eb479249`（#191）を同期した。同期はAGENTS、共通GMDのGradle設定と説明の3ファイルで、画素処理は変更していない。その後の変更は既存AndroidTestの公式additional test outputへのJSON出力と、終了処理で一次例外を保持する小helper・3試験だけ。計測後の本書更新でAPKは再生成していない。

## 候補の範囲

foundationを既存の `OriginalRasterWorkspace.mapParallelRows` に渡すのは、ROIが組み込みの `OriginalMask.NONE` singletonそのものの場合だけ。別のcallbackは全ゼロを返してもcaller threadで直列実行する。ROI不在を「検出器が顔なしと判定した」とは扱わない。顔検出、後段face guard、NEAR_WHITE → Mist → Orton → Characterの再試行、画素式とRGB演算順序は変更しない。

最大2worker・4行in-flight、入力行コピーと幅分のprivate ROI配列を使い、行順序と未終了workerの所有権を既存queueで保持する。追加領域は行幅に比例し、新規の全面bufferはない。取消確認はcallback前・mask検証後・行計算後であり、`R7Base.foundation` の計算中は現在行を完了する可能性がある。固定の取消応答時間は保証しない。

## 実行条件と証拠の確認

- Gradle 9.8.2 / AGP 9.5.1 / JDK 25.0.4.1 / NDK 29.0.14206865 / CMake 3.22.1。
- `:app` の `original24Probe`、`-PcinematicOriginal24Probe=true`。versionCode 10 / versionName `0.0.10-p05`。debug継承、debuggable、R8/minifyなし。nativeはCMake Debug、実compile flagsは `-g` で明示的最適化flagなし。以前の非debuggable/profileable/native O2診断とは別条件。
- 公式 `:app:pixel9aapi37Original24ProbeAndroidTest` を逐次実行。`--offline --no-daemon --max-workers=1`、SDK自動download無効。既存共有GMDを使い、独自AVDや手動常駐driverを追加していない。
- API 37.0 Google APIs image revision 6 / emulator 37.1.11 / x86_64、1080×2424 / 420dpi。config RAMは2G、実hardwareは4096MiB・2vCPU、guest heap上限192MiB。hostはRyzen 5 3600（6core/12logical）。指定GPUは `swiftshader_indirect`、実hardware modeは `swiftshader`。PixelのCPU/GPU性能ではない。
- 1024×768、orientation 1、strength 0.5、Orton 4worker / Character 2workerを固定。foundationだけ1/2/2/1。各回は別のGMD/instrumentation起動でprocess-cold。プロセス内warmupはしていない。
- `android.enableAdditionalTestOutput=true` によりUTPが渡す `additionalTestOutputDir` へ、計測窓を閉じた後に合成JSONだけをCREATE_NEWで出力し、公式host回収を確認。独自回収daemonは使わない。private JSON保存・export失敗は一次例外があればsuppressed、成功本体ならtest failureとなる。
- 各回でrunner exit 0、JUnit 1件・failure/error/skip 0、runId、complete/success、条件、APK hash、host JSONを照合。JSONだけのsuccessを合格根拠にしていない。小さい64×48の別実行はhelper 3件＋pipeline 1件成功、4ケースの回収確認であり、本ABBAやwarmupには数えない。

SHA-256:

- app APK: `2581df4c27b620b5d5c9b48650a88f287ec8890f63964e51d172a6ef71a92972`
- test APK: `b6f0ed79da044d0b5dc146ec9dbed7821568633b4e59303669e0f519d86ec4dc`
- GMD config: `cf1dd9cd56f40dc6fac0930b101522632dbbcc9fc7784ba414f1676729eaf4fd`
- 合成DNG（全4回一致）: `796555f835a38a94162383dc9f037023a1fd6400037d7b5f3f0e287ccd64bf3a`
- 出力JPEG（全4回一致）: `34113526c6404066837be39249414939390fead6815997c51a013471a1848873`

## ABBA全結果

2026-11-07 UTC。開始/完了はGradle呼出しと終了記録であり、下記のprobe時間とは別。

| 順序 | foundation workers | 呼出し開始 → 完了 UTC | runId |
| --- | ---: | --- | --- |
| A1 | 1 | 04:18:24 → 04:22:30 | `60a8215fc5e64d098ac35526ff7d7927` |
| B1 | 2 | 04:23:02 → 04:27:00 | `27e16b789a8c4c7fadb6061146998816` |
| B2 | 2 | 04:27:51 → 04:31:56 | `0dc98fb2a91d409dafbc7943f43567ac` |
| A2 | 1 | 04:32:32 → 04:36:58 | `d31f3b5e87414398943ae77f77cc008f` |

| 順序 | R7_FOUNDATION 秒 | render観測範囲 秒 | probe total 秒 | managed観測最大 MiB | native観測最大 MiB | PSS観測最大 MiB |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| A1 | 3.912365 | 145.646 | 146.177 | 64.308 | 21.407 | 137.394 |
| B1 | 8.131626 | 137.224 | 137.493 | 50.447 | 21.349 | 112.382 |
| B2 | 6.115349 | 135.845 | 136.157 | 65.592 | 21.388 | 127.931 |
| A2 | 3.908124 | 150.330 | 150.752 | 64.483 | 21.442 | 128.668 |

- `R7_FOUNDATION` は行mapを含む工程の経過時間。CPU時間ではない。参考平均はserial 3.910244秒、parallel 7.123487秒（約82%長い）。この4点、このGMD/debug条件での観測であり、統計的有意性や原因を断定しない。
- render観測範囲は `before` → `returned` のsnapshot時刻差。samplingと境界snapshotの負荷を含む。probe totalはmetrics開始からstopまでで、JPEG再読込・hash照合・closeを含む。FGS、MediaStore publication、製品の `SAVE_REQUEST` 全体は含まない。
- memory samplerは同じprobe窓。managed/nativeは公称10ms、PSSは公称100ms間隔の観測最大で、完全なpeakではない。指標を合算しない。before/returned/afterのthermal statusは全回0、lowMemoryは全回false。これを実機の温度統制と同一視しない。
- probe totalは2workerの2回が短いが、対象foundation工程は逆に長い。他工程・JIT/GC・host負荷等への寄与は分離していないため、全体差を改善とも単なるノイズとも断定しない。4回からPixelや12/24MPへ外挿しない。

## 正しさ・終了処理・資源

測定headで限定JVM10件を実行し成功（UP-TO-DATEではない）。内訳は `OriginalParallelRowsTest` 8件と `OriginalStageClear24Test.emptyFoundation*` 2件。旧b011fddの10件成功とは別の実行である。foundation専用試験は48×32/174×116、strength 0/0.5/1でfoundationと最終出力のraw float bits一致、外部全ゼロROIのcaller thread維持、注入取消/例外後のowner回収を確認する。一般queue試験の範囲をfoundation固有境界の証明へ拡張しない。

ABBA全回で原本再検証、同じJPEGのfingerprint再読込、寸法、fullCoverageを確認し、`SCRATCH_CLEANUP` 完了とclose後の `hasUnreleasedResources == false` assertionを通過した。入力/JPEG fixtureの個別削除は追加していない。private guest内fixtureとGMDのread-only実行/通常終了を区別し、既存共有AVD/cacheを削除していない。終了後java/qemu実体0、AGPの遅延kill helperのみ残存を確認した。lock/helperの手動削除・停止はせず、共有実行枠を返却した。

source/build/.cxx/TEMP/Gradle cache/AVD書込先は開発用Dドライブ。既存CドライブのSDK/JDK/system imageは参照し、既存Android認証状態は維持した。旧C側Codex workspace、実機、写真は使用しない。SMALL/ABBA中のhost観測最小はC空き43.155GiB、D空き1390.570GiB、RAM空き10.051GiB。GMD計画開始からD空きの最大減少は2.470GiB、各実行のC空き最大減少は19.227～53.750MiB。これらはvolume全体の変動で、本試験だけへの帰属ではない。20GiB予算、C最低4GiB/1実行256MiB減少、host空きRAM最低6GiBの停止条件に達していない。既存資源の移動/削除や永続設定変更はしていない。

未検証: foundation並列の縦長、幅1/最大幅、非零external ROI、同時foundation session、画素計算中取消と停止時間。SMALLにはorientation 6があるがfoundationは1workerであり、並列portraitの証明ではない。12/24MP、Pixel、実写の速度・画質・全工程peak、FGS背景復帰は今回の試験対象外。製品2worker採用の根拠は不足しており、追加試験を自動で広げない。

## 実機測定の準備だけを行う場合

現行tracked設定に、以前の `3bbd9548364b36d092ec064f39b66bbdeceac673` の非debuggable/profileable・native O2・R8 off診断を再現する専用variant/propertyはない。`original24Probe` はdebug継承、`:rawprobe` は別packageのdebug限定診断。標準releaseはnon-debuggableだがprofileable指定がなく、以前と同条件とは言えない。既存の非写真計測資料で当時のrecipeを照合し、最小の標準ビルド設定とAPK/merged manifest/native flagsを先に確定する必要がある。今回はその実装・新APK配布・端末操作をしていない。

後日の候補は完了時点のmainをSHAで固定する。今回同期したmainは858b99e（#181 FGS、#183 Character、#184 tone、#191 GMDを含む）であり、#185の2workerは未採用。将来のversionCode/name、variant/property、署名/既存appとの区別、含むmerge、APK hashを作成時に記録し、今回のversion10/0.0.10-p05と混同しない。

本人が端末接続と対象を確認した後、まず12MPの同一DNG・同一設定・同一ビルド条件で反復A/B（初回と後続を区別、順序を反転）を行う案。低負荷の時間観測とmemory観測を分け、原本/JPEG hashと温度/充電/他アプリ負荷を記録する。24MPは空き容量・heap/native/PSS・応答性を確認する別段階とし、12MP成功から自動で進めない。FGSの背景移行/復帰・取消・保存公開は別の機能試験で、製品SAVE_REQUESTとprobe totalを同じ時間として比較しない。

本人に必要なのは、接続端末の確認、使用する同一DNGと設定の選択、発熱/充電/空き容量の確認、実写を使う場合の本人操作、背景移行/復帰と異常時の中止判断。写真そのものの取得や自動撮影は予定しない。
