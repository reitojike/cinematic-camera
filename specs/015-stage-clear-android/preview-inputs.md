# R7試験表示の診断入力（Issue #113）

[Issue #113](https://github.com/reitojike/cinematic-camera/issues/113)の純Kotlin境界。
`R7PreviewInputs.prepare(AsShotInput, ellipses, shouldStop)`は診断露出とfoundation ROIだけを返す。
撮影・画面・保存・native codec・Gradle設定・新依存・権限・送信へ接続しない。
#78/#79の完了や、顔優先AE・Pixel profile/W1の完成として扱わない。

## 契約

- **PI-FR-001**: 入力はW1を加える前のplain as-shot線形sRGB/D65。`AsShotInput`の呼出側宣言として
  `LIBRAW_PRELIMINARY_AS_SHOT`または`SYNTHETIC_AS_SHOT`を保持する。この型は実画像の来歴を自動検証しない。
  長辺1024以下、中央`[h//4:3h//4, w//4:3w//4]`が非空であることを検査する。
  `LinearSrgbImage`の有限値検査を維持し、負値/HDR成分はクリップしない。
- **PI-FR-002**: 中央のFloat32輝度`[.2126,.7152,.0722]`からNumPy既定のlinear補間p97を求める。
  外部EVは既存PC `r9_pixel.auto_base_ev`と同じく
  `clamp(-.13-.83*log2(max(p97,1e-5)),-3,6)-E2(.6)`。
  型付き`DiagnosticExposure`へp97・中央画素数・入力由来・`AS_SHOT_BEFORE_W1`・
  `PC_CENTRAL_P97_DIAGNOSTIC`を保持する。これは暗所で過増感し得る仮の中央測光で、完成した顔優先AEではない。
  `R7Base`へは返ったEVをそのまま渡し、E2/commonEVを呼出側で再加算しない。
- **PI-FR-003**: foundation ROIは必須の明示楕円リストからだけ生成する。初回の空リストも明示指定する。
  中心は各軸Float32の0〜1、半径は(0,1]、各値有限、最大6楕円。
  これは表示用処理量を制限するAPI上限であり、検出人数を報告するものではない。楕円の端が画像外へ出ることは許す。
  `r6_diagnose.skin_mask`と同じ画素中心座標、楕円半径、`.45`幅のsmoothstepと画素ごとの最大合成を使う。
  領域の由来は`EXPLICIT_NORMALIZED_ELLIPSES`。検出顔`FaceMask`やR7の色相肌重みとは別物。
- **PI-FR-004**: 結果の顔検出状態は`NOT_RUN`だけ。ゼロROIから「顔なし」「顔検出済み」「顔保護済み」と判断しない。
  明示ROIがあってもdetectorは動いていない。空中央・不正楕円・非有限の輝度/百分位は成功にしない。
- **PI-FR-005**: prepareは開始・各中央行・sort前後・各ROI行・返却前に`shouldStop`を確認し、
  停止時は`CancellationException`で終了する。R7 renderも省略可能な同callbackを開始と6段の各終了後に確認する。
  既存3引数の呼出し・既存goldenを維持する。sortやkernel内部の即時停止、撮影応答の実測保証はしない。

## 実装・検証計画

PCの参照境界は`tools/raw_baseline/preview_inputs.py`。式やROIを別算法へ置き換えず、既存PC関数を呼ぶ。
`export_android_preview_inputs.py`は写真を開かず、14種5354画素の入力・明示楕円・中央p97/EV・ROI全画素を
`r7_preview_inputs_v1.csv.gz`へ可逆保存する。mtime=0、filenameなし、新しい外部データなし。

- **PI-SC-001**: 比較前に決めた移植予算はEV絶対誤差2e-6、Float32 ROI全画素2e-6（rtol=0）。
  p97にも2e-6を使う。奇数寸法、補間、中央外の明部、黒/負/HDR、EV上下clamp、最小非空中央、
  楕円の重なり/空/6個と長辺1024を含める。超過時は原因を直し、閾値を黙って緩和しない。
- **PI-SC-002**: 不正な値/寸法/楕円数、開始/行/sort/返却とR7全段境界の停止、入力不変、由来とNOT_RUNを検証する。
  既存R7/Lookの全画素goldenと版は変更しない。
- **PI-SC-003**: PC全検証、Android lint/unit/両ABI build、公開前検査、canonical/独立レビューを行う。
  合成1024×768のホスト処理時間を観測しても、Pixelの時間・peak memoryや実写受入の証拠にしない。

PI-T001: 契約と型付き入力/結果、明示ROI/診断露出を追加する。
PI-T002: PC合成生成とJVM全画素・拒否・停止・入力保持テストを追加する。
PI-T003: ローカル全検証と公開検査を行い、最終head CIと独立レビューの結果はPRへ記録する。

実profile/W1、顔検出器、顔優先測光、実機画質・性能、撮影/保存/画面接続は別Issueの残件。
