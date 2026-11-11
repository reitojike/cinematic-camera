# Gate 1 目視比較の表示取得手順

本書は③の取得器候補の手順であり、[主手順](GPU_GATE1_PART2.md) 第7節と
[Issue #285](https://github.com/reitojike/cinematic-camera/issues/285) の登録・条件を変更しない。
実装、ビルド、AVD機能確認、搬出、ページ生成、ユーザーの目視判断は別の証拠である。
取得成功だけで③合格にはしない。

2026-11-11の準備締切は21:30 JST（12:30 UTC）。締切までのCI・canonical review・
独立レビューの通過と実施準備が揃わなければ、今夜の③は未実施とする。
この文書・試験APK・AVD検証はPixelへの配布・導入・操作の許可ではない。

## 変更しない対象

- GPU本体: source `ee682646034f08f5739c6dc20cfff268514bc780`、
  APK SHA-256 `cf79d84ea2e225d390516ff8f7bcfe3c2d6e93c775f5118a0b97008cfd4285ac`。
- CPU本体: source `1ce63154bd886d50b7669ccfdcfd01a8e192f83e`、
  APK SHA-256 `8cfc16cf5c60e29a3c67618402a9913be7a24a7f5367e5bd101c362e8b4bf745`。
- 主GPU試験 `03204b4117421ebb2561aab94cb13f8cdaec5d292d52b8b818461ee8884e8846`、
  主CPU参考試験 `a5ab18c18b38700ee92757cc72be6ca0c53de5539ed050503adacd1eb5f3bfe1`。
- 製品source、依存、Gradle、manifest、固定APKは変更しない。
  `src/gpuDiagnosticTest/java` は既存設定でandroidTestに組み込まれる試験source。
- 同じtest packageでも別artifactとして識別し、固定4本を上書きしない。
  固定アプリをdebuggableへ変えず、`run-as`を使わない。
  配布候補の本体・試験APKのmanifestと署名はそれぞれ実物を確認する。

## 入口と必須引数

runner: `dev.cinematic.p0.gpudiagnostic.test/androidx.test.runner.AndroidJUnitRunner`。
1回につき下表のclassを1つだけ指定する。固定CPU/GPU本体は同じpackageなので別runとする。

| 引数 | CPU | GPU |
| --- | --- | --- |
| `class` | `dev.cinematic.p0.gpu.CpuGate1VisualCaptureTest#captureSelectedCpuDisplay` | `dev.cinematic.p0.gpu.GpuGate1VisualCaptureTest#captureSelectedGpuDisplay` |
| `gate1VisualCapture` | `cpu` | `gpu` |
| `gate1TargetApkSha256` | 上記固定CPU SHA-256 | 上記固定GPU SHA-256 |
| `gate1Part2` | `visual-acquisition-v1` | 同左 |
| `gate1VisualSetup` | `external-files-selection` | 同左 |
| `isolated` | `true` | 同左 |
| `gate1AuthorizedTarget` | 実端末の `Build.MODEL` と完全一致 | 同左 |
| `gate1DngDocumentId` | `primary:Download/gate1-common-synthetic-1024x768-v1.dng` | 同左 |
| `gate1VisualRun` | 未使用run ID | 未使用run ID |
| `gate1VisualPair` | CPU/GPU組を表す共通ID | 同じ共通ID |

IDは先頭英数字、以後英数字・underscore・hyphenの計1〜40文字。
物理端末には追加で `gate1PixelProbe=true` とMODEL `Pixel 9a` を要求するがflagは許可ではない。
今の準備では物理端末へ実行しない。AVD引数・serialをPixelへ流用しない。

実行形式は `adb -s <許可されたAVD> shell am instrument -w -r` に
各引数を `-e <key> <value>` で渡し、末尾に上記runnerを指定する。
MODELに空白がある場合はhostとAndroid shellの両方で単一引数になるよう引用する。
全引数・APK SHA・run/pair・MODEL・外側UI操作を私有の実行記録へ固定する。
試験中に別instrumentationや `uiautomator dump` を起動しない。

## 外側のFiles選択

取得器はHubを起動し、status `waiting_for_external_cpu_Files_selection` または
`waiting_for_external_gpu_Files_selection` を返して最大180秒待つ。
外側操作は許可されたAVDの標準画面取得と標準UI操作で行い、画面を読まず座標を流用しない。
取得器自身はFilesのクリック、scroll、権限・設定変更をしない。

- CPU: Hub「CPUプレビューの確認（Filesから選択）」を押し、既存Files入口から登録DNGを選ぶ。
  Material Buttonで `Files` が `FILES` と表示される場合がある。
  STRENGTH 50、色温度+0、明るさ+0.0、診断2並列OFFを維持し、調整panel・Before・更新中・未反映状態では採取しない。
  保存や調整値の補正をしない。
- GPU: 固定HubにGPU表示診断ボタンはないため、取得器が既存Gate 1と同じ
  `Hub.startActivity` で既存GPU表示Activityを同一taskへ起動する。`CLEAR_TASK`は使わない。
  外側では「試験用DNGをFilesから選択」から登録DNGを選び、Surface全体が見える位置にする。
  読み込み後、取得器が既存rendererへstrength 0.5 / warmth 0 / EV 0 / Before false / rotation 0を1回submitする。
  UI Slider値は同期しない。性能計測を開始しない。
- 外側の選択証拠はrun/pairに対応付ける。試験側のFiles記録は登録行・Download toolbarの可視観測であり、
  選択result URIの直接観測ではない。CPUのFiles選択では名前が一覧へ追加されず「DNG（名前未取得）」になり得る。

共通DNGは1,573,250 bytes、orientation 1、SHA-256
`796555f835a38a94162383dc9f037023a1fd6400037d7b5f3f0e287ccd64bf3a`。
取得前後に期待URIのread grantとbytes/hashを確認し、原本を変更しない。

## 取得内容と限界

各sideで元寸法のARGB_8888 BitmapへPixelCopyを2回、250msを挟んで行い、SUCCESSとcallback完了を確認する。
2画像の画素一致は静止確認であり、正しい画像や③合格の証明ではない。
PixelCopyはformat・色空間変換を行い得るため色空間を別記する。
リサイズ、offscreen再render、Surfaceのbuffer/View寸法変更はしない。

CPUは親「現像画像の表示領域」ではなく、画像自身「現像結果（表示用）」のboundsをWindow bufferから取得する。
Image boundsと4:3 FITの連続座標推定を分離し、自動cropしない。常在する診断・説明overlayの候補矩形も記録し無遮蔽を仮定しない。
footerは丸められたUI文字列であり内部Double・frame recipeではない。表示半幅strength 0.005 / warmth 0.5 / EV 0.05を明記する。
固定CPUのdecoded float・内部frame recipe・全worker終了は未観測のままで追加hookを入れない。

GPUは実SurfaceViewの最新queued bufferを取得する。既存GL queue上で入力float hash、実描画済みrecipe、
mailbox settled、drawCount、Surface lease/dimensionsを照合する。実画面scanout計測ではない。
画像取得を①・PSS走行に重ねない。CPU/GPUの元表示領域・PNG寸法は異なり得る。
原本と明示的な同サイズ表示用変換を分離し、変換を第7節の同一解像度条件の自動達成にしない。
条件の解釈・変更が必要なら確認を得る。`criterion3=NOT_ASSESSED`、`formalVisualPairReady=false`を維持し、
同じルックかはユーザーが判断する。

## 私有出力と搬出

アプリ専用externalの `files/gate1-visual/<side>-<run>/` に保存する。

- CPU: `report.json`、`cpu-window-a.png`、`cpu-window-b.png`
- GPU: `report.json`、`gpu-surface-a.png`、`gpu-surface-b.png`

reportはrun/pair、固定アプリ・DNG、requestedRecipe、CPU UI/GPU実状態、元寸法・色空間、
PNG bytes/SHA-256、overlay/未観測、source前後、cleanupを保持する。画像・report・操作記録・ページをGit/Issue/PRへ公開しない。
許可されたAVDで実際の搬出経路を実証してから使う。標準external配置の例:

    adb -s <許可されたAVD> pull /sdcard/Android/data/dev.cinematic.p0.gpudiagnostic/files/gate1-visual/<side>-<run> <新しい私有保存先>

実際の生成pathと端末/搬出側の全file bytes/hashを照合する。`run-as`、debuggable化、
共有MediaStoreへの書出し、再取得で搬出不成立を隠さない。固定本体切替の前に前sideを回収する。
私有ページ生成器は別artifact。schema検証・生成成功とブラウザー実表示確認を分け、未検証を完成扱いにしない。

## 失敗と終了

setup180秒、GPU settle30秒、PixelCopy callback各10秒、通常close/Hub確認10秒、
GPU終了latch5秒・thread join5秒は各段階の待機上限であり、UI/Binder/file IOを強制中断する総hard deadlineではない。

- 失敗、APK/input不一致、熱・充電・foreground変化、画像変化、cleanup未確認では停止し、自動再取得しない。
- 対象Activity・同Hub task・picker観測が揃う場合だけ取消Backを最大1回試す。
  drawerだけ閉じた場合や所有証拠不足はcleanup未確認。追加Back・kill・再起動で成功へ補正しない。
- 対象Activityの正常finish→DESTROYED→同じHubのresumed/focus復帰を確認する。
  GPUは既存終了latchと実GL thread終了、失敗flagなし、台帳bytes/textures/windows 0も確認する。
  CPU native完全解放やActivity GCは推定しない。
- Hub→Files途中で対象所有が確立しなければpickerが残り得る。未確認として保存し次runへ進まない。
  所有証拠なしに操作を追加しない。
- 正常時も `outcome=CAPTURE_CANDIDATE_ONLY`。失敗画像の差し替えやrun ID再利用をしない。
  次試行は新しいrun IDと許可された範囲を要する。
- AVD確認後の固定本体/主試験復元や試験APK削除は親の管理下で識別と結果を別記する。
  物理端末の導入・回収・削除は新しい許可なしに行わない。

API根拠: [PixelCopy](https://developer.android.com/reference/android/view/PixelCopy)、
[Activity lifecycle](https://developer.android.com/guide/components/activities/activity-lifecycle)、
[app専用external](https://developer.android.com/training/data-storage/app-specific#external)。
