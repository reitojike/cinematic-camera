# 旧512px試用経路の試験専用配置

Issue #290。製品・診断コードから呼ばれない `StageClearTrialRenderer` と
`StageClearTrialPipeline` を main から除き、安全 assert を消さず試験側に保持する。
renderer は通常 androidTest、pipeline は `src/testSupport/java` に置き、
Gradle の `test` / `androidTest` だけから参照する。main や診断アプリの source set には含めない。
移動した本文・式・定数は変更しない。旧 CPU 実装全体の削除完了ではない。

## 維持する保証

`StageClearTrialPipelineTest` の4件は、入力不変/有限値/ルック適用、
INPUTS/BASE/LOOK取消と次要求成功、事前取消/512px上限拒否、最大寸法完了を維持する。
512px制限は旧fixture専用で、製品GPUの制限へ転用しない。

通常 `DngTrialRendererTest` の次の4件は、実nativeデコードを含めて維持する。

- `selectedContentUriRunsRealR7AndLookWithExplicitTrialMode`: 原本不変・ルック・表示形式。
- `stageCancellationStopsLookAndAllowsNextNativeRequest`: BASE取消後の次要求成功。
- `stageClearRejectsUnknownResourcesAndUnsupportedControlsBeforeDecode`: リソース/入力条件のdecode前拒否。
- `maximumPortraitTrialCancelsBeforeBitmapAndNextRequestWorks`: portrait最大寸法・表示直前取消・次要求・原本不変。

実GPUの安全保証・基準画像回帰・1024px計測とは別の、旧fixtureの安全保証である。
GPU共有型、LibRaw/JNI、DNG入力解析、保存intent/codec、取消/retry/所有権の製品契約は変更しない。

## 再実行と配置確認

[共有レーン手順](MANAGED_DEVICES.md)で予約したエミュレーターだけを使用する。実機操作なし。
通常 debug / androidTest APK を `adb install -r` で導入し、上記4methodを個別指定して実走する。
fixtureは自分で作った合成DNG pending行だけを使用・解放する。既存写真は使わない。

```text
./gradlew :app:testDebugUnitTest --tests dev.cinematic.p0.photoroll.StageClearTrialPipelineTest
adb -s <emulator-serial> shell am instrument -w -r -e isolated true -e class dev.cinematic.p0.photoroll.DngTrialRendererTest#<method> dev.cinematic.p0.test/androidx.test.runner.AndroidJUnitRunner
```

JVM4件・Android4件について、最終head、コマンド、件数、成功/失敗/skip、時間をPRに記録する。
0件/skip/コンパイルだけを合格にしない。GPU診断androidTestのコンパイルも確認する。
APKのDEX定義を調べ、app APKに2classがなくtest APKにはあることを確認する。
削除・リファクタの各PRでは従来どおりAndroid全工程90条件＋帯28比較を実走し、基準を変更しない。
