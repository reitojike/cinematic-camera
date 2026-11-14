# GPU保存の帯準備と比較手順

`GpuExportBands` は原本を読む1本のworkerと、GLを操作する呼出元を分ける。
direct RGBA帯バッファは2本だけを確保し、呼出元が使う帯の次の1帯まで準備できる。
借用RGB行はcallback内でRGBA行へコピーして一括転送する。次のbufferを取得できるまで
帯末尾callbackから戻らないため、さらに次の行を読まない。bufferの返却は既存fence待ちを含む
GL callback全体の終了後に行う。shader・fence・画像処理の順序は変更しない。

正常終了は全行の消費とworkerの終端検査・close完了を条件とする。取消や例外でもworkerを
放棄せずjoinする。呼出側の割込みは停止要求として保持し、読込みの終了確認後に割込みを戻す。
読込み/closeの実エラーは消費側のprimaryを上書きせずsuppressedとして残す。
元のファイルI/Oと同様、停止要求だけでは資源解放済みと判定しない。

帯バッファは1本最大32MiB（幅8192、帯256、RGBA float32）。追加は最大1本分で、
全画面float配列は作らない。行変換用配列は幅×4要素だけ。RGB16の整数正規化は有限値を
保証するため、その入力だけ有限値走査を省く。一般float入力の検査は維持する。
FINALのRGBA8は明示little endianのIntBufferから行単位で一括読出しし、
既存Bitmap向け0xffRRGGBBへ並べ替える。末尾帯のlimitとalpha=255を維持する。

## AVDでの前後比較

共有AVDの予約を確認してから、同じAVD/image/host driverと合成入力で測る。
通常工程記録の意味と欠測範囲は [SAVE_TIMING](SAVE_TIMING.md) に従う。
専用worktree/buildを使い、同領域のGradleはgolden wrapperも含めて直列に実行する。

`SaveTimingAndroidTest#captureSaveStageBenchmark` は `isolated=true`、`saveBenchmark=true`、
英小文字・数字・ハイフンからなる未使用の `saveBenchmarkRun` を必要とする。
固定 `writeSyntheticTrialDng`（1600×1200、orientation=1）、strength=.45、製品の帯256で
通常保存を4回行う。save-0はwarm-up、save-1〜3が測定。各回の新規attemptだけを
外部app領域 `save-benchmark/<run>/save-N.tsv` へ抽出する。
試験用写真は成功後にその試験が作った正確なURIだけ削除する。失敗時は証拠を残し停止する。

```text
adb -s <reserved-emulator> shell am instrument -w -r -e isolated true -e saveBenchmark true -e saveBenchmarkRun <unused-run> -e class dev.cinematic.p0.metrics.SaveTimingAndroidTest#captureSaveStageBenchmark dev.cinematic.p0.test/androidx.test.runner.AndroidJUnitRunner
adb -s <reserved-emulator> pull /sdcard/Android/data/dev.cinematic.p0/files/save-benchmark/<unused-run> <local-output>
```

source commit・APK hash/種別/versionCode・入力条件・帯高・AVD/driver版と各snapshotを外側の
実験メタデータで対応付ける。同じversionCodeでもbefore/afterを混同しない。
各attemptでSAVE/PUBLISHEDと工程COMPLETEDを確認し、NAや省略・失敗は0へ補間せず除外数を示す。
累積snapshotを単純結合せず、RESOURCE_CLOSEの正当な反復イベントも重複削除しない。
各工程の生値・有効n・中央値を保持する。GPU_RENDERとB1〜FINALは合算しない。
計測範囲外の処理があるため、工程の和を保存ボタンから完了までの時間と呼ばない。

速度とは別にJVMの転送・取消・close試験、Androidの帯64/256対全画面一致、
JPEG前の画素の変更前後完全一致とGPU90条件の既存基準比較を確認する。
AVDの値はPixel等の実機測定の代用ではない。実機操作には都度本人の承認が必要。
