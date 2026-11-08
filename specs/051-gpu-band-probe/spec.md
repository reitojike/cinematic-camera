# GPU 帯合成の比較基盤
Status: 検証基盤のみ（製品には未採用）
Issue: [#212](https://github.com/reitojike/cinematic-camera/issues/212)

## 利用シナリオ・受入条件
合成 RAW 相当の線形 RGB を既存 R7・Look・表示変換へ通し、CPU と GPU 候補を同じ入力で比較する。
**製品には未採用、検証基盤のみ。** 製品の呼び出し側、既定値、CPU golden は変更しない。

プレビューは 8bit 最大 2 段以内、差のある画素割合と暗部の縞を記録し、比較ページで本人が目視する。
保存は現像全体で最大 1 段以内。満たせなければ CPU 保存を維持する。
合成画像での目視許容は製品採用ではない。[#212 の採用条件](https://github.com/reitojike/cinematic-camera/issues/212#issuecomment-6714404590)に従い、
**実機の GPU 出力と実写で再判定してから**製品への接続を判断する。本試作は実機起動を許可する入口ではない。

## 明示起動と通常試験からの隔離
- `GpuOrtonBandProbeTest` は `gpuBandProbe=true` を明示した場合だけ実行する。未指定・false・その他の値は JUnit の assumption でスキップする。
- 判定は画像生成・CPU 現像・EGL/GLES 初期化より前に行う。通常 CI は androidTest をコンパイルするだけで GPU 試験を実行しない。
- 合成 UI テストの `isolated=true` だけでは GPU 試験を起動しない。明示起動時も `isolated=true` とエミュレーター限定の確認を維持する。
- 共有 GMD の端末・枠・GPU 設定は [MANAGED_DEVICES.md](../../docs/MANAGED_DEVICES.md) に従う。ホスト GPU を既定にしない。

明示起動時は対象 class と専用引数を指定する。

```powershell
.\gradlew.bat :app:pixel9aapi37atdDebugAndroidTest --rerun --no-daemon --max-workers=1 `
  "-Pandroid.testInstrumentationRunnerArguments.class=dev.cinematic.p0.raw.GpuOrtonBandProbeTest" `
  "-Pandroid.testInstrumentationRunnerArguments.gpuBandProbe=true" `
  "-Pandroid.testInstrumentationRunnerArguments.isolated=true"
```

## 対象外・停止条件
個人写真、実機、Release、製品採用、既存工程の変更を含まない。マージ許可は検証基盤の取り込みに限る。
許容差超過で原因・数値を報告して停止する。保存全体を通していない結果を原寸合格と呼ばない。

## 要件
- FR-001: 製品未接続、専用引数による明示起動、既存工程ファイルを変更しない。
- FR-002: 全画像 CPU ぼかしの境界を維持し、GPU 合成入出力だけ有界帯とする。
- FR-003: GPU の 8bit 最大差・差画素率・暗部縞の指標と比較画像を記録する。
- FR-004: 取消・失敗では出力を公開せず GL 資源を回収する。

## 完了基準
- SC-001: 対応する合成入力の全 Look でプレビュー最大差 2 以内、帯高さによる差なし。合成比較の本人目視許容は #212 に記録する。
- SC-002: 原寸保存の全経路を通していなければ保存最大差 1 の合格を主張しない。
- SC-003: エミュレーターの時間は参考。GPU ドライバーを含む真の最大メモリは未測定と明記する。
- SC-004: 専用引数なしで試験がスキップされ、明示起動時だけ合成試験が実行される。
