# 実装計画

基点: main `ac0ddace`。#122のローカルprototypeを読み取りで照合し、SDKと独立した既存部分を切り出す。
標準Kotlinと既存JUnit/PC NumPyを使い、新しいlibrary・runtime・workflow・推論モデルを追加しない。

## 変更するファイル

- `app/src/main/java/dev/cinematic/p0/raw/DetectedFaceMasks.kt`: immutable bboxとmax合成kernel。既存`FaceMask`を再利用。
- `app/src/test/java/dev/cinematic/p0/raw/DetectedFaceMasksTest.kt`: 全画素PC照合・上限/停止/入力保持。
- `app/src/test/resources/detected_face_masks_v1.csv.gz`: #122から同一bytesの13case合成fixture。
- `tools/raw_baseline/export_android_face_masks.py` / `test_android_face_masks.py`: PC face_areaの検出呼出しをstubに置換し合成枠を渡す。
  prototypeのBGR変換生成部分は検出器の責務なので含めない。
- `.gitattributes`: 合成gzip fixtureのbinary/generated指定だけを加える。

## 所有権と状態

枠リストのstable sortはコピーで行い、出力配列は新規に一つ生成する。既存`FaceMask`のcopy constructorを変えない。
1024正方形ならmask1面4MiB、生成配列と防御コピーの同時保持は8MiB。画像/他中間配列/JVMを含まない計算値で、peak保証ではない。
mutable scratchやpool・所有権factoryへ拡大せず、保存/高解像度担当の変更と分離する。
detector statusやVERIFIED_FACES/VERIFIED_NONEをこの関数で採番せず、production providerへ登録しない。

## 検証

1. 既存fixtureのhashをprototypeと照合し、PC関数で再生成して全画素を検査する。実写真やOpenCVは使わない。
2. Kotlin全画素照合、上下限/候補数・空枠/境界外・入力不変・協調停止を検証する。
3. リソースの使用状況を確認し、重いGradle/native buildやAVDを他作業と重ねない。
   必要なら低メモリのfocused JVM compile/testを先行し、Android lint/unit/buildは空いた枠かCIへ分離する。
   focused検証をAndroid全体CIの代用とは記載しない。
4. 差分/秘密除外・Spec Kit整合を確認し、独立レビュー、Draft PR、最終headの必須CI/canonical/threadを確認する。
   外部書込が権限で止まる場合は具体的なlocal diffと未実行項目を渡す。mergeは別承認。

## Constitution check

標準API/固定依存を維持し、実写や原本を読まない。新しい通信・権限・署名・native・保存操作なし。
テスト成功は幾何契約だけを意味し、実顔/実機画質/Pixel速度の合格ではない。#79/#122の残件を閉じない。
