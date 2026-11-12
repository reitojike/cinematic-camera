# 通常GPU入力の24MP合成回帰（#101 partial）

## 範囲

`GpuProductInputAndroidTest`の既存製品owner経路を、小型256×192と明示opt-in 6000×4000で確認する。製品コード・768MiB等guard・画素式・依存は変更しない。全Activity/SAF画面操作やJPEG公開の試験ではなく、`ManualTrialPhotoRollRenderer.openGpu` → `GpuPhotoRollInputs` → `Original24Preflight/PreviewSession` → `GpuPhotoRollSession`と保存metadata準備の境界を対象とする。

現行の通常GPU入口は`PhotoRollScreen`のGPU分岐であり、原寸preflightはRAW格納32MP・指定crop24MP、手動編集の出力検査も24MP。12MP固定拒否はこの経路にない。別の縮小DNG診断経路の16MP上限や、GPU表示用縮小入力の1024長辺/1024×768を原本24MP拒否と混同しない。

## 検査

- orientation 1/6の合成DNG2枚を使い、1枚目→別写真→同じ写真の再開で毎回原本を強く照合する。
- RAW/crop寸法とorientation、回転後の出力geometryを元fixtureへ照合。GPU入力は既存の縮小上限を守る。
- 保存metadataは同じ原本・geometry・recipeに束縛し、異なるgeometryを拒否する既存回帰を維持する。metadata準備をJPEG保存成功とはしない。
- 強度・色温度・露出、比較表示を実GPU ownerへ渡す。frame/比較中に原本を再openしないこと、原本hash不変、source書込み0、FD/GL thread/texture/window解放を確認する。
- 一意の自作fixture directoryへ合成原本とmetadataを置き、試験成功かつ解放・原本照合後だけ回収。cleanupは各項目をattemptしprimary失敗へsuppressedで保持する。初期化も外側ownerで回収し、primary失敗またはclose/確認不明ならfixtureを残して停止する（入力側の失敗保持ownerをprovider FDだけで解放済みとみなさない）。既存データ・AVD設定の消去はしない。

## 実行手順

[共有端末運用](MANAGED_DEVICES.md)に従い、通常GPU対応API37 AVDを予約する。ATDはhardware rendering無効なので正規先にしない。既存環境のAPKを組み立て、空き確認後に`-r`で更新する。小型ケースを先に実行し、成功・回収確認後に24MPを別instrumentationとして実行する。

```powershell
adb -s $serial shell am instrument -w -r -e isolated true -e class dev.cinematic.p0.photoroll.GpuProductInputAndroidTest#originalIsReadOnlyAtOpenAndAllPreviewOwnersReleaseAcrossPhotosAndResume dev.cinematic.p0.test/androidx.test.runner.AndroidJUnitRunner
adb -s $serial shell am instrument -w -r -e isolated true -e gpuProduct24 true -e class dev.cinematic.p0.photoroll.GpuProductInputAndroidTest#optIn24MpOpenEditCompareAndResumeKeepGeometryAndOwners dev.cinematic.p0.test/androidx.test.runner.AndroidJUnitRunner
```

24MP引数なしでは大判ケースをskipする。小型/大判を別々に明示選択し、全androidTestを一括実行しない。OOM・原本変化・close不明・AVD不調では停止し、guardやtimeoutを緩和して成功させない。

## 証拠の限界

このfixtureの受付観測は既存の固定値（availableBytes=2GiB等）であり、実機RAMの観測ではない。native展開/原寸段階の`Original24Resources`による実OS圧力と768MiBの検査は維持する。通過は実Pixelの最低RAM予算や最悪同時所有を確定しない。Pixel RAM/lowMemory確認は本人が許可したSIGMA実写確認と同じ機会に別途行う。

合成sourceの色/画角や縮小GPU描画成功をfp実写真の画質合格へ読み替えない。JPEGの原寸公開・FGS・実SAF provider/SD抜去は後続の別検証。実行結果はPRのhead/条件/selectorとともに記録し、試験の存在を合格に数えない。
