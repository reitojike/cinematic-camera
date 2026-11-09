# GPU 合成診断（製品未採用）

この診断は #237 の実機判断材料を用意する独立アプリです。1つの入口から GPU 合成診断、既存の保存4候補比較、CPU プレビュー確認を選べます。GPU は合成データだけを使い、製品の描画・保存処理には接続しません。CPU の golden は変更しません。

## ビルドと通常試験からの隔離

通常の debug / release は GPU 診断 source set を含みません。明示指定でのみ独立アプリを作ります。CIでは専用APKをコンパイルするだけで、GPU試験・EGL初期化は実行しません。

```powershell
.\gradlew.bat :app:assembleGpuDiagnostic -PcinematicGpuDiagnostic=true --no-daemon
```

applicationId は `dev.cinematic.p0.gpudiagnostic` です。既存の一時デバッグ署名を使い、署名鍵の新規作成や配布先の変更はしません。`profileable` な非 debuggable 版で、撮影Activityと写真一覧・カメラ・音声の権限を除外します。CPUプレビューには既存PhotoRollActivityと保存サービスを含めますが、入力はFilesで明示的に選択する方式です。GPU側からそれらを呼び出しません。

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
- `uploadNs`: CPUのdirect bufferへの詰め替え、texture割当/転送、`glFinish`まで。blurのsource生成・補間係数作成はこの内訳ではなく `sumNs` の残差に入る。`drawNs`: draw発行から直後の `glFinish` まで。`readbackNs`: `glReadPixels`とCPU配列への読戻しまで。Ortonは有限値検査も含む。残りの割当、準備、FBO設定、CPU変換/検査、一時texture/FBO削除は全体時間に入り、3内訳の和とは一致しない。
- これらは同期区切りを置いたCPU wall clockで、GPU timestamp queryでも純粋なDMA/演算時間でもない。同期によって本来の転送/計算の重なりを止め、driver/CPU待ちや帯域の影響を含む。非同期pipelineや入力をGPUに保持した場合の時間をこの結果から確定しない。
- 再利用対象はEGL/context/shaderだけ。入力/出力・係数textureとFBOは各pass/呼出しで作り直し、すべての帯をupload/readbackする。GPUに入力画像を保持する設計は未実装であり、この内訳を後の設計判断材料にする。

各ボタン実行は新しいsessionで始まる。`iteration=0` が初回で、1〜12は強度ごとの再利用4回。ボタンを押し直した最初の回をwarmへ付け替えない。全26回の品質確認と完了/FGS終了を照合し、欠測・失敗・context再生成を成功反復へ補完しない。端末の冷間/温間と初期化済み/未初期化は別条件である。

専用合成試験はHDR/暗部×強度0.25/0.5/1.0/0.25で、毎回新しいEGL/context/shaderを作る版と同じsessionを保持する版のfloat出力完全一致を要求する。CPUとの差2/255以内、取消/消費側例外後の解放、次session、閉じたsession/別thread拒否も別に確認する。実機GPU性能や実写一致の証拠ではない。

標準APIの参照: [EGL14](https://developer.android.com/reference/android/opengl/EGL14)、[GLES30](https://developer.android.com/reference/android/opengl/GLES30)、[glFinish](https://registry.khronos.org/OpenGL-Refpages/es3.0/html/glFinish.xhtml)、[glReadPixels](https://registry.khronos.org/OpenGL-Refpages/es3.0/html/glReadPixels.xhtml)。

## 判定と制約

- 1024pxの対象ぼかし＋Orton GPU合成は、同じsessionの2回目以降（`phase=reused`）の `sumNs` を30msの目安と比較します。転送・同期・readback・その処理内のCPU変換/割当/一時資源回収を含み、EGL/context/shader初期化とsession最終解放は除きます。初回準備込み・CPU側Orton field準備・全Look時間は別記します。異なる入力/強度/順序・メモリ観測ONを混ぜて集計しません。
- プレビューは全Lookで最大2段以内。そのうえで実機GPUと実写で目視の再判定が必要です。数値だけでは製品採用しません。
- 保存採用は現像全経路で最大1段以内が条件です。12MPの単体試験はこの条件を検証しません。DNGデコードからJPEGまでの合格へ読み替えません。
- 暗部の平坦化カウントは検出補助であり、目視のバンディング判定の代わりではありません。
- メモリは観測最大で、100msより短いピークを保証しません。GPUテクスチャ量とプロセス全体のPSSは別の量です。
- エミュレーターのGPU rendererと実機は異なります。時間は参考値であり、採否はPixel 9aで決めます。
- 全体の判定・縮小ぼかしの格子は既存CPU定義を参照します。Mistの判定を帯ごとに再計算しません。見送った全工程融合 #210 を再導入しません。

## 背景・取消

API 35以降は `mediaProcessing`、API 33–34は `dataSync` の Foreground Service を使います。通知を出したあと専用スレッドで EGL の所有権を保持します。ホームへ戻る・画面消灯と、前面に残す場合を区別して記録します。

「取り消す」は協調取消で、帯とGPU処理の区切りで観測します。EGL文脈と2つのshader programは合成写真1枚のsession内だけで保持し、全処理・取消・失敗の終了時に同じ所有スレッドで解放します。12MPは1ボタンの全帯/反復が1 sessionです。閉じたsessionの再使用と別スレッド使用/解放を拒否し、文脈が失われたらその回を失敗にします。自動再作成で反復へ混ぜません。メモリ採取スレッドも終了を待ちます。プロセス終了後に処理を自動再開しません。GPU合成診断では写真・一時JPEG・MediaStoreへの書き込みはありません。CPUプレビューからの保存操作は従来の製品保存経路であり、GPU診断と区別します。背景診断の成功は、製品保存の中断・復旧・撮影との両立の合格を意味しません。

## 次の判断

R7はこのGPU試作に含めません。実機のGPU時間とCPUのR7残留時間を併せて、明るさ変更時のR7前半の保持案との役割分担を決めます。保存4候補・プレビュー内訳/並列候補・準備が整えば50ms自動計測と同じ機会へまとめます。実機の回数・順序・所要時間は #208 の統合手順で確定します。

関連: #212（採用条件）、#218（Orton検証基盤）、#236（GPUぼかし設計）、#237（実装・検証記録）。
## CPUプレビュー候補3工程の合成比較

Hubの「CPU候補3工程（合成1/2比較）」は1024×768の合成入力のみを使い、Foundation・Orton・Characterをそれぞれ同じ入力で直列と2並列に通します。工程ごとのraw-bit完全一致とSHA-256、開始前後の発熱・電池温度・充電状態、工程時間を記録します。順序は1→2と2→1を選べます。入力作成・ハッシュは時間外、workerの開始・終了は時間内です。

これは3工程の候補比較であり、実写真を使った編集全体や操作から表示までの比較ではありません。通常製品の既定は変えません。画面を開いただけでは実行せず、明示ボタンで開始します。画面を離れると取消し、workerが終了するまでbusyを保持します。直列側は工程境界、2並列側は既存の協調取消の区切りで停止します。GPU・RAW診断/HOLD・通常保存の実行中は開始しません。HubとGPU開始側もこの診断の終了を待ちます。

専用instrumentationは `previewParallelProbe=true` と `isolated=true` を指定します。1024px比較は追加で `previewParallelLoad=true` が必要です。未指定では実行しません。小型試験は両順序の一致、取消後の再実行、入口での非自動開始、明示開始の取消とbusy解除を確認します。GPUの既存試験とは別のopt-inです。エミュレーター時間は参考値で、実機の採否は未確認です。

## 実編集画面のCPUプレビュー並列切替

Hubの「CPUプレビューの確認（Filesから選択）」では、実編集の256px DRAFT/1024px EXACTに既存3工程候補を接続する。画像上の診断スイッチは既定OFF。調整を閉じた確定表示でON/OFFを切り替えると同じ値で再計算する。通常版・保存の既定は変えず、GPU処理も使用しない。

`RawPreviewTiming`には各回のworkers、maxEdge、DRAFT/EXACT、全体/工程時間をINFO出力する。`PreviewTiming`は要求受付からUI状態採用を別に出す。ログ取得・反復条件・計測外の範囲は[プレビュー診断手順](PREVIEW_DIAGNOSTICS.md#統合診断版の実編集で12並列を比較する)に従う。50msのOS提示判定は未確認である。
