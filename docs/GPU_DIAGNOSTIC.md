# GPU 合成診断（製品未採用）

> #290: この独立診断variantは退役対象です。以下は過去版の再現・既存記録の解釈用に保持する手順で、現行ビルド入口ではありません。通常GPU性能試験とメモリ再実行手順は別に保持します。

この診断は #237 の実機判断材料を用意する独立アプリです。1つの入口から GPU 合成診断、既存の保存4候補比較、CPU/GPU 編集を選べます。本書の旧GPU合成診断メニューは合成データだけを使います。#339/#341の診断Hub限定GPU編集・保存は [GPUエンジン契約](GPU_ENGINE.md) を参照してください。通常debug/releaseのCPU既定とCPU goldenは変更しません。

## ビルドと通常試験からの隔離

通常の debug / release は GPU 診断 source set を含みません。明示指定でのみ独立アプリを作ります。CIでは専用APKをコンパイルするだけで、GPU試験・EGL初期化は実行しません。

```powershell
.\gradlew.bat :app:assembleGpuDiagnostic -PcinematicGpuDiagnostic=true --no-daemon
```

applicationId は `dev.cinematic.p0.gpudiagnostic` です。既存の一時デバッグ署名を使い、署名鍵の新規作成や配布先の変更はしません。`profileable` な非 debuggable 版で、撮影Activityと写真一覧・カメラ・音声の権限を除外します。CPU/GPU編集には既存PhotoRollActivityと保存サービスを含め、入力はFilesで明示的に選択します。旧GPU合成診断の負荷メニューから写真を読み出すことはありません。

合成 instrumentation はさらに `cinematicGpuDiagnosticTests=true` を指定してコンパイルします。専用テストでも `gpuPixelProbe=true` がなければ EGL 初期化前にスキップします。`isolated=true` も必要です。1024px・12MP入口の完走試験は別クラス `GpuDiagnosticLoadTest` で、さらに `gpuPixelLoadProbe=true` を明示します。これは時間の採否ではなく、各入口の完走だけを確認します。実機を使用するときは、その回のユーザー許可と接続を確認してから行います。

## 入口と結果

独立アプリの起動だけでは処理しません。入口の「GPU合成診断」から、各実行ボタンを押したときだけ Foreground Service で次を実行します。保存比較とCPUプレビューは別の画面で、同時に負荷試験を動かさないでください。HubはGPU・通常保存・RAW診断の取消処理中/HOLDを含むbusy状態では別の入口を開きません。RAW診断がHOLDとなった場合は、既存手順に従い明示的にアプリを再起動してから再開します。

- 小型の正しさ確認: 257px の HDR・暗部合成入力、強度 0.25 / 0.5 / 1.0。各入力で初回0.25を1回、その後各強度4回を同じGPU準備で処理する。
- 1024px 全Look比較: CPU 経路と、Glow の最小半径 bloom だけの GPU ぼかし、および Orton GPU 合成を含む経路を比較。小型と同じ反復で、HDR/暗部ごとに1 session、初回1＋再利用12の計13回（両入力で26回）。
- 12MP ぼかし単体: 4000×3000 の合成入力を64行ずつ生成し、BOX縮小とBILINEAR復元を行う。原寸の全画像配列は保持しない。この入口は時間・メモリのみを測り、12MPの画素差は未測定。checksumのfinite判定は品質一致ではない。
- 12MP Orton単体: 一定色の合成入力を256行ずつ生成し、64行ずつGPU合成する。全帯・全反復で同じEGL文脈とshaderを使う。CPU比較時間と合成入力/soft field生成はGPU処理時間に含めない。

`GpuDiagnostic` ログに時間、最大8bit差、差のある画素数と全画素数、暗部の新しい平坦な隣接点、GPU rendererを出します。1024px/小型は、各強度の再利用4回を通常→逆→逆→通常の順で自動実行します。「比較順を逆にする」を選ぶと逆→通常→通常→逆になります。12MPの各入口は初回1＋同じ準備の再利用3回で、CPU/GPUの順は選んだ設定に固定します。速度比較時はメモリ観測をオフにし、別の回で「メモリ観測」をオンにすると100ms周期の managed/native 観測最大を出します。PSS はさらに別測定とします。写真・素材名・撮影情報・端末識別子は出しません。

## 初回と反復の計測境界（#257）

- `GPU準備`: `preparationNs` はEGL初期化、pbuffer/context生成、2つのshaderのcompile/link、初期GL状態と同期まで。入力生成/R7/顔fieldは含まない。遅延実装されるdriver処理が最初のdrawに残る可能性があるので、準備を除いても `phase=first` は反復判定から外す。
- `GPU初回`: `firstTwoStagesIncludingPreparationNs` は準備と最初のぼかし＋Orton合成の実測区間の和、`firstLookIncludingPreparationNs` は準備と最初の全Look区間の和。CPU基準を間に実行するため連続したストップウォッチ時間ではない。12MPは `firstIncludingPreparationNs`。session解放と画面表示は含めない。
- `対象GPU2工程`: `session`、`phase=first/reused`、`iteration`、`width`、`dark`、`strength`、実際の `reverse` を記録する。`blurNs` + `ortonNs` = `sumNs` が30ms用。`ortonCpuPreparationNs`（bright変換とCPUぼかし）を足していない。`全Look` の `gpuNs` にはこれと肌field、残りCPU工程も入り、GPUコマンドだけの時間ではない。
- 従来モード `timingMode=baseline-v1` の `uploadNs`: CPUのdirect bufferへの詰め替え、texture割当/転送、`glFinish`まで。blurのsource生成・補間係数作成はこの内訳ではなく `sumNs` の残差に入る。`drawNs`: draw発行から直後の `glFinish` まで。`readbackNs`: `glReadPixels`とCPU配列への読戻しまで。Ortonは有限値検査も含む。残りの割当、準備、FBO設定、CPU変換/検査、一時texture/FBO削除は全体時間に入り、3内訳の和とは一致しない。
- これらは同期区切りを置いたCPU wall clockで、GPU timestamp queryでも純粋なDMA/演算時間でもない。同期によって本来の転送/計算の重なりを止め、driver/CPU待ちや帯域の影響を含む。非同期pipelineや入力をGPUに保持した場合の時間をこの結果から確定しない。
- 再利用対象はEGL/context/shaderだけ。入力/出力・係数textureとFBOは各pass/呼出しで作り直し、すべての帯をupload/readbackする。GPUに入力画像を保持する設計は未実装であり、この内訳を後の設計判断材料にする。

各ボタン実行は新しいsessionで始まる。`iteration=0` が初回で、1〜12は強度ごとの再利用4回。ボタンを押し直した最初の回をwarmへ付け替えない。全26回の品質確認と完了/FGS終了を照合し、欠測・失敗・context再生成を成功反復へ補完しない。端末の冷間/温間と初期化済み/未初期化は別条件である。

専用合成試験はHDR/暗部×強度0.25/0.5/1.0/0.25で、毎回新しいEGL/context/shaderを作る版と同じsessionを保持する版のfloat出力完全一致を要求する。CPUとの差は `CPU_REFERENCE_ONLY` として記録し、旧2/255等の超過だけでは停止しません。有限値・寸法、取消/消費側例外後の解放、次session、閉じたsession/別thread拒否は引き続き検査します。実機GPU性能や実写一致の証拠ではない。

標準APIの参照: [EGL14](https://developer.android.com/reference/android/opengl/EGL14)、[GLES30](https://developer.android.com/reference/android/opengl/GLES30)、[glFinish](https://registry.khronos.org/OpenGL-Refpages/es3.0/html/glFinish.xhtml)、[glReadPixels](https://registry.khronos.org/OpenGL-Refpages/es3.0/html/glReadPixels.xhtml)。

## 割当・転送を分ける詳細診断（#259）

「内訳を分離」は既定 OFF。OFF は `timingMode=baseline-v1` として従来の API 順序で測る。
ON は `timingMode=split-allocation-v1` となり、texture を `glTexImage2D(null)` で確保して同期した後、
`glTexSubImage2D` で入力を転送する。ぼかしは共有 CPU buffer を詰め替える転送ごとに同期する。
追加同期と driver の確保経路が変わるため、**詳細モードの時間を従来の時間や30ms判定に混ぜない**。
これは最適化候補ではなく、未分類の負担を切り分ける別の診断である。両モードの画素一致を合成試験する。

`GPU内訳` は `stage=blur/orton/blur12/orton12`、session、first/reused、iteration、比較順を持つ。
小型/1024pxは入力と強度も記録する。各行の `totalNs` は当該工程の既存 wall 区間で、以下の非重複区間と
`otherNs` の和になる。Orton の複数呼出しは加算し、CPU の基準処理や帯の合成入力作成を挟む経過時間とは区別する。
`GPU計測` 行の mode・timingMode・observeMemory と照合し、メモリ観測 ON の時間を速度比較へ混ぜない。

| ログ | 実測する範囲 |
|---|---|
| `cpuAllocateNs` | 診断コードで明示した FloatArray / IntArray / direct ByteBuffer の確保。ゼロ初期化や呼出し内の待ちを含む |
| `cpuPackNs` | 係数の書込み、CPU 配列から direct buffer への詰め替え。Orton は RGB/肌field から RGBA への変換 |
| `cpuUnpackNs` | direct buffer から配列への取出し、帯のコピー、呼出元の consume。Orton の要素ごとの有限検査も含む |
| `cpuInputPrepareNs` | ぼかしの source callback と補間係数の生成。callback/標準係数生成内部の確保も含み、純粋な演算時間ではない |
| `cpuValidateNs` | 出力全体の有限検査と image wrapper の検査。wrapper 内部の小さな object 確保は区別しない |
| `gpuAllocateNs` | GL object/texture の作成・初期設定、texture storage 要求と直後の `glFinish`。driver の遅延確保が以後に残る可能性がある |
| `uploadNs` | `glTexSubImage2D` の発行と `glFinish`。CPU buffer 詰め替えは除く |
| `drawNs` | draw の発行と直後の `glFinish`。GPU timestamp ではなく CPU/driver 待ちを含む |
| `readbackNs` | `glReadPixels`。CPU 配列へのコピーは除く。同期済みでも driver/転送の負担を含む |
| `releaseNs` | texture/FBO の削除 API。driver の遅延解放や GC 完了を保証する時間ではない |
| `otherNs` | FBO 接続/検査、uniform/viewport 設定、取消確認、集計・計時器の負担など、上記に分類していない wall 時間 |

`calls` は実際のぼかし/Orton 関数呼出し数、`passes` は実際の resize/Orton 帯数。
`uploadedBytes` と `readbackBytes` は API に渡す論理上の float32 RGBA bytes の累計で、物理 bus の traffic 量ではない。
`textureAllocations` / `textureAllocatedBytes` は storage 要求の回数/累計 bytes。
`explicitCpuAllocations` / `explicitCpuAllocatedBytes` は明示的に計時した配列と direct buffer の要求回数/累計 bytes で、
callback・係数生成内部・小 object・driver の確保、GC、RSS/PSS/peak は含めない。累計確保量を同時保持量と呼ばない。

1 回の API 呼出し、1 帯、1 画像を同じ単位にしない。現行固定入力では 1024×769 の Orton は通常 1 呼出し/13 帯、
12MP は 256 行ずつ 12 呼出し/計47帯で、ぼかしも28 passと96 passで異なる。実行時は必ずログの件数を正とする。
1024px と 12MP の2点だけから約150msの固定費、GPUの限界、GPU常駐入力の速度を確定しない。

次回の許可済み実機確認では同じ build・入力・強度・比較順・熱条件の baseline と詳細モードを別走行にする。
初回を除く条件別の全体時間/各内訳/件数/bytesを並べ、中央値は各 raw 行から求める。中央値同士の差を残差としない。
最初に両モードの画素一致と資源回収を確認し、失敗時は採取値を採否へ使わない。詳細診断だけが遅い場合は
計時・追加同期による変化も候補に残す。入力常駐、buffer/texture 再利用等の最適化をこの Issue で実装しない。
内訳がそろうまでは GPU 採否を保留し、実機に触れていない準備段階を速度確認済みとしない。

標準 API: [glTexImage2D](https://registry.khronos.org/OpenGL-Refpages/es3.0/html/glTexImage2D.xhtml)、
[glTexSubImage2D](https://registry.khronos.org/OpenGL-Refpages/es3.0/html/glTexSubImage2D.xhtml)。
これらの CPU wall 計時では純粋な GPU 演算・DMA・driver 内部の確保を完全には分離できない。

## 判定と制約

- 30msの比較は従来モード `timingMode=baseline-v1` に限る。1024pxの対象ぼかし＋Orton GPU合成は、同じsessionの2回目以降（`phase=reused`）の `sumNs` を30msの目安と比較します。転送・同期・readback・その処理内のCPU変換/割当/一時資源回収を含み、EGL/context/shader初期化とsession最終解放は除きます。初回準備込み・CPU側Orton field準備・全Look時間は別記します。異なる入力/強度/順序・メモリ観測ONを混ぜて集計しません。
- CPUとの差は参考値です。旧プレビュー最大2段・保存候補最大1段は現在の停止・採否条件に使いません。Gate 2の品質判断は本人の実写の見た目とし、GPU帯/full・shader・有限値/寸法・安全契約の検査を維持します。
- 12MPの単体負荷試験はDNGデコードからJPEG/publicationまでの全保存経路ではありません。診断完走を実写品質・製品復旧の合格へ読み替えません。
- 暗部の平坦化カウントは検出補助であり、目視のバンディング判定の代わりではありません。
- メモリは観測最大で、100msより短いピークを保証しません。GPUテクスチャ量とプロセス全体のPSSは別の量です。
- エミュレーターのGPU rendererと実機は異なります。時間は参考値であり、採否はPixel 9aで決めます。
- 全体の判定・縮小ぼかしの格子は既存CPU定義を参照します。Mistの判定を帯ごとに再計算しません。見送った全工程融合 #210 を再導入しません。

## 背景・取消

API 35以降は `mediaProcessing`、API 33–34は `dataSync` の Foreground Service を使います。通知を出したあと専用スレッドで EGL の所有権を保持します。ホームへ戻る・画面消灯と、前面に残す場合を区別して記録します。

「取り消す」は協調取消で、帯とGPU処理の区切りで観測します。EGL文脈と2つのshader programは合成写真1枚のsession内だけで保持し、全処理・取消・失敗の終了時に同じ所有スレッドで解放します。12MPは1ボタンの全帯/反復が1 sessionです。閉じたsessionの再使用と別スレッド使用/解放を拒否し、文脈が失われたらその回を失敗にします。自動再作成で反復へ混ぜません。メモリ採取スレッドも終了を待ちます。プロセス終了後に処理を自動再開しません。GPU合成診断では写真・一時JPEG・MediaStoreへの書き込みはありません。HubのCPU/GPU編集からの保存操作は別の原寸保存経路であり、この合成診断と区別します。背景診断の成功は、製品保存の中断・復旧・撮影との両立の合格を意味しません。

当日の熱停止・GPU失敗時の最大試行回数・CPUへ進める条件は[統合手順の停止条件](RAW4_DEVICE_CHECKLIST.md#途中で止める条件と次の進み方)に従う。GPUは初回を含む最大2回で、二度失敗したら残りGPUを止める。熱が続く場合や資源回収を確認できない場合はCPUにも進まず別日に回す。

## 次の判断

R7はこのGPU試作に含めません。実機のGPU時間とCPUのR7残留時間を併せて、明るさ変更時のR7前半の保持案との役割分担を決めます。保存4候補・プレビュー内訳/並列候補・準備が整えば50ms自動計測と同じ機会へまとめます。実機の回数・順序・ログ・冷却と停止条件は [統合診断の当日手順](RAW4_DEVICE_CHECKLIST.md) を入口とし、実施版と結果を #208 に記録します。古いAPKの準備コメントを現行版の能力と取り違えません。

関連: #212（採用条件）、#218（Orton検証基盤）、#236（GPUぼかし設計）、#237（実装・検証記録）。
## CPUプレビュー候補3工程の合成比較

Hubの「CPU候補3工程（合成1/2比較）」は1024×768の合成入力のみを使い、Foundation・Orton・Characterをそれぞれ同じ入力で直列と2並列に通します。工程ごとのraw-bit完全一致とSHA-256、開始前後の発熱・電池温度・充電状態、工程時間を記録します。順序は1→2と2→1を選べます。入力作成・ハッシュは時間外、workerの開始・終了は時間内です。

これは3工程の候補比較であり、実写真を使った編集全体や操作から表示までの比較ではありません。通常製品の既定は変えません。画面を開いただけでは実行せず、明示ボタンで開始します。画面を離れると取消し、workerが終了するまでbusyを保持します。直列側は工程境界、2並列側は既存の協調取消の区切りで停止します。GPU・RAW診断/HOLD・通常保存の実行中は開始しません。HubとGPU開始側もこの診断の終了を待ちます。

専用instrumentationは `previewParallelProbe=true` と `isolated=true` を指定します。1024px比較は追加で `previewParallelLoad=true` が必要です。未指定では実行しません。小型試験は両順序の一致、取消後の再実行、入口での非自動開始、明示開始の取消とbusy解除を確認します。GPUの既存試験とは別のopt-inです。エミュレーター時間は参考値で、実機の採否は未確認です。

## 実編集画面のCPUプレビュー並列切替

Hubの「CPUプレビューの確認（Filesから選択）」では、実編集の256px DRAFT/1024px EXACTに既存3工程候補を接続する。画像上の診断スイッチは既定OFF。調整を閉じた確定表示でON/OFFを切り替えると同じ値で再計算する。通常版・保存の既定は変えず、GPU処理も使用しない。

`RawPreviewTiming`には各回のworkers、maxEdge、DRAFT/EXACT、全体/工程時間をINFO出力する。`PreviewTiming`は要求受付からUI状態採用を別に出す。ログ取得・反復条件・計測外の範囲は[プレビュー診断手順](PREVIEW_DIAGNOSTICS.md#統合診断版の実編集で12並列を比較する)に従う。50msのOS提示判定は未確認である。
