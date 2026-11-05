# 固定bboxからの保護FaceMask（#79）

対象は[#79](https://github.com/reitojike/cinematic-camera/issues/79)のうち、呼出側が宣言した固定枠からのmask生成だけ。
[#122](https://github.com/reitojike/cinematic-camera/issues/122)のローカルprototypeから純Kotlinと合成fixtureを再利用する。
stock OpenCV/顔検出SDKの採用保留を維持し、既存の画質・保存・撮影経路へ接続しない。

## User story 1（P1）: 宣言した枠でPCと同じ保護領域を得る

検出器に依存せず、同じ固定bboxをPC/Androidへ渡して、保護処理の領域だけを比較できる。

- FR-001: `DetectedFaceMasks.fromBoxes(width,height,boxes,shouldStop)`は同寸法の`FaceMask`を返す純Kotlin関数とする。
  検出器・原本読込・座標変換・自動露出・foundation ROIの推定を行わない。
- FR-002: 対象rasterは各辺1〜1024、候補は0〜64。bboxのx/yは−4096〜4096、幅/高さは1〜4096の整数。
  不正値は拒否し、clampや途中打切りによる部分成功にしない。これは診断処理の明示上限であり、センサー座標の受入ではない。
- FR-003: PC `r10h_refine.face_area`と同じstable面積降順で最大6枠を採用する。
  画素中心(x+.5,y+.5)、中心(x+w/2,y+h*.62)、半径(max(w*.58,1),max(h*.72,1))、
  `clip((1.25-hypot(dx,dy))/.35,0,1)`と画素ごとの最大合成を使う。画像外枠を楕円化前に切らない。
- FR-004: 空枠はゼロmask。maskの0は検出未実行/検出完了0件/失敗を表す状態ではない。
  非空枠でも画面外のためゼロmaskになり得る。生成成功から顔の存在・顔保護済みを保証しない。
- FR-005: 呼出側の枠リストを並べ替えず、戻りmaskの防御コピーを維持する。
  開始、sort前後に相当する境界、各mask行、返却前に協調停止を確認し、停止時はCancellationExceptionでmaskを返さない。
  実行中sortの即時停止やPixel性能は保証しない。

## 受入基準

- SC-001: #122の既存PC合成13case全画素と絶対差2e-6（rtol=0）以内。予算・PC式・既存golden/版を変更しない。
- SC-002: 空/1画素/奇数寸法/境界外/重複/小枠/上位6/stable tie/64候補、不正境界、停止、入力保持を検証する。
- SC-003: SDK・model・native・Gradle依存・Manifest・production呼出側を変更しない。
  JVM/PC合成検証とAndroid lint/buildを適切な実行枠で確認し、未実行を合格へ読み替えない。

## 対象外

顔検出、Camera2能力照会/AE、撮影・保存・JPEG公開、原寸診断/高解像度、編集UI、手動枠UI、既定画質への適用、
実写真・新撮影・実機操作。既存v1の顔ガード（50画素・poolのp90・最大.7EV・1回再計算）は変更しない。
#79/#122の実写精度・性能・採用判断を完了としない。merge/公開は承認手順に従う。
