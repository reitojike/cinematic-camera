# PC RAW baseline PoC (P1-A)

[Issue #49](https://github.com/reitojike/cinematic-camera/issues/49) / [spec](../../specs/010-p1a-raw-baseline/spec.md)。PCで基準現像と比較の再現性を調べる小さいCLI。Stage Clear、camera操作、Android実装、upload機能は含まない。

## 専用環境

新しい処理の検証対象はWindows x86-64 / CPython 3.15.2の通常GIL版。3.15.2を別途用意し、`py -3.15 --version`が3.15.2であることを確認する。既存global Pythonや旧venvを更新せず、repo rootから別の専用venvを作る。

```powershell
py -3.15 -m venv tools/raw_baseline/.venv-py315
$rawPython = 'tools/raw_baseline/.venv-py315/Scripts/python.exe'
& $rawPython -m pip --isolated install --index-url https://pypi.org/simple --only-binary=:all: --require-hashes -r tools/raw_baseline/requirements-win-py315.txt
& $rawPython -m pip check
& $rawPython -m unittest discover -s tools/raw_baseline -p 'test_*.py' -v
```

rawpy 0.28.0 / NumPy 2.5.4 / Pillow 13.0.0を公式PyPIの通常版CPython3.15 Windows wheel SHA-256で固定。依存の版は旧環境と同じでも、cp312とcp315のwheel/hashは異なる。free-threaded版（cp315t）は対象外で、rawpy 0.28.0には対応wheelがない。rawpyに同梱されたruntime LibRaw版と機能flagsはmanifestへ記録する。別OS/Pythonのwheelはこのlockの対象外。TLS検証は無効化しない。

3.15のvenvに同梱された対応pipを使い、旧環境のpipをコピーしない。pip 26.2.2 / 26.3で検証しており、旧pip 25.0.1はPython 3.15では起動できない。Windows用の公式Pythonは[Python.org](https://www.python.org/downloads/release/python-3152/)から取得する。

### 旧3.12 runの再現

`requirements-win-py312.txt`と旧3.12.10の専用環境は、既存manifestの再現用に保持する。旧venvや既存runを3.15で上書きしない。replayには元のPython版・依存版・LibRaw flags・platform・toolファイルhashを含む環境の完全一致が必要で、3.12のmanifestを3.15で実行すると`MANIFEST_ENVIRONMENT_MISMATCH`になる。同じ依存版や合成画像の一致だけでこの検査を緩和しない。旧runは元のcheckout/toolと旧環境でreplayし、新しい3.15の処理は別runとして作る。

3.12.10の保持は旧run再現用であり、新しい入力を処理する環境のsecurity更新方針ではない。Python 3.12系列のsecurity更新版はsource-onlyのため、旧環境を新しい処理の標準へ据え置く理由にはしない。

## CLI

`$authorizedRoot`には本人が明示許可したrootを手動設定する。他の写真folderを探したり、入力を外部へ送ったりしない。

```powershell
& $rawPython tools/raw_baseline/baseline.py inventory --root $authorizedRoot --save
& $rawPython tools/raw_baseline/baseline.py synthetic --run synthetic-01
& $rawPython tools/raw_baseline/baseline.py replay --manifest-run synthetic-01 --run synthetic-02
# 実DNGが提供され、private inventoryで選択したときだけ実行する:
& $rawPython tools/raw_baseline/baseline.py develop --root $authorizedRoot --input sample.dng --profile pixel9a --wb camera --run dng-01
& $rawPython tools/raw_baseline/baseline.py replay --root $authorizedRoot --manifest-run dng-01 --run dng-02
```

`--input`はroot内の相対DNG、`--run`は英数字で始まる英数字・`_`・`-`の1〜64文字。既存runは上書きしない。`inventory --save`も既存inventoryを上書きしない。追加棚卸しは旧private inventoryを本人が移すか、`--save`なしで件数を確認する。

全出力はこのディレクトリの`.private/`以下（Git ignore）だけ。inventoryの相対名、manifestの入力相対名・内容hashもprivate情報として扱う。生metadataやtracebackをstdoutへ出さず、終了0=生成/棚卸し成功、2=入力/I/O/再現不一致、3=予期しない内部失敗。CLI成功は画質合格を意味しない。

各runには`baseline-linear-srgb16.npy`、`baseline.jpg`、`tone-only.jpg`、小型`manifest.json`を生成する。NPYはuint16 RGB配列（`allow_pickle=False`で読む）。JPEGに原本EXIF/GPS/固有IDは複写せず、sRGB ICCだけを付ける。ICC生成日時は2000-01-01に固定し任意profile IDを空にして、変換内容を変えずbyte再現性を保つ。画像・manifestをGit/Issue/PRへ添付しない。失敗した中間runは公開しない。

## 基準条件と限界

- RAW処理はrawpy/LibRawのAHD、16bit RGB、sRGB primaries/D65の有界作業配列、`gamma=(1,1)`。JPEG化でIEC sRGBの区分伝達関数を適用する。NPYは汎用画像ファイル／HDR交換形式ではない。
- 基準露出は入力そのものに対する`bright=1`、`exp_shift=1`、自動明るさOFF。照明ごとに露光を上げて良く見せない。絶対露出・センサー校正はしない。
- WB既定はas-shot camera WB。必要なmultipliersが取得できなければ停止し、勝手にdaylightへfallbackしない。`--wb daylight`はLibRawのdaylight係数、`--wb user --user-wb R G B G2`は明示した正の有限4係数。auto WBは常にOFF。実際に選んだ係数をmanifestへ残す。
- manifestの`effective_wb`は選択した入力側WB係数であり、LibRaw内部で正規化された処理後`pre_mul`の実測ではない。RGB系の4番目が0でもRGB3係数が無効という意味にはしない。[rawpyのcamera_whitebalance／auto_whitebalance](https://letmaik.github.io/rawpy/api/rawpy.RawPy.html)は別で、後者はpostprocess後の実使用係数を返す。入力側係数の0をそのまま画素へのゼロ倍率と解釈しない。
- black/white levelはLibRawの入力metadataに従う。`no_auto_scale=False`でlevel scalingは有効、`adjust_maximum_thr=0`で最大値の自動調整は無効。user black/white overridesなし。入力のlevel・camera white levelは観測値として記録する。
- highlight modeはClip、wavelet/FBDD/median NRはOFF。入力matrix等のLibRaw変換は使うが、このPoC独自のcamera色校正・NR・lookは加えない。
- **16bit、gamma1、自動明るさOFFは、scene-linear／HDR保持／元channelの復元の保証ではない**。WB・色変換・level scaling・有界uint16出力の段階でclipし得る。decodedの0/65535占有率は処理後の指標であり、センサー飽和率ではない。暗所・色照明の実DNGで原因を確認するまで画作りへ進まない。
- トーン版は基準と同じRGBから輝度`L=.2126R+.7152G+.0722B`に`L/(s+(1-s)L)`を適用し、RGB比を保って拡大後に表示範囲へclampする。既定`s=.8`（`.25..4`、1で恒等）。前clamp超過率を別記録する。これは比較用の全画面トーンだけでStage Clearではない。
- Pixel9aとSIGMAfpは入力admissionと結果を分離する。TIFF IFD0のMake/Model/DNGVersionのみ最小読取し、Google/Pixel 9aと、SIGMAのモデル名fp／SIGMA fpを明示照合する。fp Lや未知機種は自動代用しない。profileは校正済みcamera lookを意味しない。WB・色matrix・画素構造・入力処理済み度合い・noiseが異なるため、fpの成功をPixel成立と数えない。
- 最大512MiB・80MPに限定。BigTIFF、特殊DNG構造、別名camera、欠けたWBなどはfail closedとし、未確認対応を宣言しない。リンク/junctionは追跡しない。CLIはネットワークを使わない。
- replayは入力hash・固定処理条件・同一版環境／toolファイルhash・出力3点のhashを検査し、不一致ならrunを公開しない。他OS/LibRaw/codec版をまたぐ同一byteは保証しない。

## 評価の次段階

[評価枠](EVALUATION.md)に従い、同じDNGをbaseline／tone-only／将来Stage Clearで比較する。Stage Clearはmanifest上`not_implemented`の予約枠。実DNGがない段階では合成テストだけを報告し、decode・色・ディテール・ノイズの品質を未確認に残す。

## 第6回の色差段階診断（r6_diagnose.py）

[Issue #63](https://github.com/reitojike/cinematic-camera/issues/63)の診断用CLI。色照明下の色差が、デコーダー／行列・DNG内蔵プロファイル・WB・スライダーのどこで生じるか（軸1）を測る。あわせて、第5回の土台に対してWB（軸2）、肌の明るさの付け方（軸3）、測光規則（軸4）を1つずつ変えた結果を、非公開の1ページにまとめる。

```powershell
& $rawPython tools/raw_baseline/r6_diagnose.py --livepicture $authorizedRoot --references $diagnosticExports --r5 $round5Folder --output $newPrivateOutput
```

- 本人が書き出した診断用参照JPEGは比較にだけ使う。第三者のプロファイルやプリセットのデータを処理へ取り込まず、自動fitもしない。
- DNG内蔵のColorMatrix1/2とProfileHueSatMapは、DNG仕様の計算で適用する（診断用。既定の処理にはしていない）。
- 参照JPEGのICCがDisplay P3なら線形sRGBへ変換し、sRGB外の値も測定に残す。
- 測定は長辺1200pxの縮小画像と、第5回recipeの手動矩形領域で行う。結果は好みの採否や画質合格を意味しない。
- 入力の前後hashを照合する。出力は入力外の未作成folderに限る。

## 第7回の層の掛け合わせ比較（r7_*.py）

[Issue #68](https://github.com/reitojike/cinematic-camera/issues/68)の比較用CLI。DNG内蔵プロファイルと第5回の土台の上に層を重ね、2つずつ掛け合わせた3×3で比べる。

| スクリプト | 比べるもの |
|---|---|
| `r7_look.py` | プロファイル層（青〜紫を青へ寄せる）× 浸る層（局所トーン・自然な彩度） |
| `r7b_skin.py` | 肌層（肌の色相だけ白く・黄み赤みを抑える）× 明るさ |
| `r7c_punch.py` | ぱきっと感（なだらかな黒の締め・コントラスト・肌以外の彩度）× WB |
| `r7_final.py` | 本人が選んだ組み合わせ（`RECIPE`）を、第5回と参照の編集に並べて書き出す（sRGB / Display P3） |
| `r7_exposure.py` | 本人の編集済みペアから、測光規則を交差検証で検討する（診断のみ） |

```powershell
& $rawPython tools/raw_baseline/r7_final.py --livepicture $authorizedRoot --r5 $round5Folder --output $newPrivateOutput
```

- 層の強さは各スクリプト冒頭の定数で決まる。`RECIPE` は本人の選択の記録で、Androidの既定値ではない。
- 参照画像やプロファイルのデータは取り込まず、自動fitもしない。
- 出力は入力外の未作成folderに限り、入力の前後hashを照合する。

## 第8回の汎用性と露出の検討（r8_*.py）

[Issue #71](https://github.com/reitojike/cinematic-camera/issues/71)の検討用CLI。

| スクリプト | 内容 | 環境 |
|---|---|---|
| `r8_generalize.py` | 撮影日に偏らないよう選んだ、調整に使っていない写真へ確定ルックを適用し、本人の編集とシリーズで比べる | 通常の`.venv-py315` |
| `r8_exposure.py` | 肌の色相を顔の代わりにした測光で、本人の編集の顔の明るさを予測できるかを交差検証する | 通常の`.venv-py315` |
| `r8_face.py` | 顔検出（OpenCV同梱のHaar）で顔を測り、同じ比較を行う | 診断専用の`.venv-py315-diag` |

診断専用の環境は、既存の固定環境とロックを変えずに別に作る。OpenCVはこの評価のためだけに使い、Androidアプリには組み込まない（Androidでは Camera2/CameraX の顔情報や ML Kit を想定）。

```powershell
py -3.15 -m venv tools/raw_baseline/.venv-py315-diag
& tools/raw_baseline/.venv-py315-diag/Scripts/python.exe -m pip --isolated install --index-url https://pypi.org/simple --only-binary=:all: --require-hashes -r tools/raw_baseline/requirements-win-py315-diag.txt
```

- 本人の編集で切り抜きのある写真は、画素の位置が合わないため露出の評価から除く。判定は編集JPEGのLightroom記録（XMPの`crs:HasCrop`）で行い、記録がない写真も除く。
- 結果は非公開の出力に置く。測光規則は検討結果で、Androidの既定値ではない。

## 第9回のPixel 9a対応（r9_*.py）

[Issue #73](https://github.com/reitojike/cinematic-camera/issues/73)の検討用CLI。Pixel 9a の DNG を DNG 仕様どおりに現像し、第7回の確定ルックをかける。

| スクリプト | 内容 |
|---|---|
| `r9_pixel.py` | Pixel の DNG の現像。撮影時の WB（AsShotWhiteXY、または AsShotNeutral）、ForwardMatrix の補間、ProfileLookTable（あれば）、生データへの GainMap（周辺減光・色むらの補正）を扱う |
| `r9_app.py` | 本アプリ（DngCreator）の DNG と Google カメラ（HDR+）の DNG を撮影順で組にし、同じ処理とルックで並べる |

- 本アプリの DNG は1枚撮り・10bitで、WB は AsShotNeutral、LookTable はない。Google カメラの HDR+ は合成 RAW で、WB は AsShotWhiteXY、Google の LookTable がある。どちらにも対応する。
- GainMap はベイヤーの4色ごとにあり、当てないと白が緑寄りになる。レンズの歪み補正（WarpRectilinear）は色の検証に不要なため適用しない。
- 露出はステージ写真から学んだ規則による仮のもので、Android の既定値ではない。

```powershell
& $rawPython tools/raw_baseline/r9_app.py --app $appDngFolder --google $googleRawFolder --r5 $round5Folder --output $newPrivateOutput
```

## 公式根拠とlicense

- [rawpy 0.28.0 PyPI](https://pypi.org/project/rawpy/0.28.0/)、[Params API](https://letmaik.github.io/rawpy/api/rawpy.Params.html)、[LibRaw output parameters](https://www.libraw.org/docs/API-datastruct.html)。処理条件はCLI実装で固定しmanifestに記録する。
- rawpy本体は[MIT](https://github.com/letmaik/rawpy/blob/v0.28.0/LICENSE)。rawpy wheel同梱LibRawの根拠は[LICENSE.LibRaw（LGPL2.1）](https://github.com/letmaik/rawpy/blob/v0.28.0/LICENSE.LibRaw)。LibRaw upstreamは[LGPL2.1またはCDDL1.0の選択肢](https://www.libraw.org/docs/)を示すが、取得したwheelを単にMIT/CDDLだけと扱わない。
- [NumPy license](https://numpy.org/doc/stable/license.html)はBSD3条項、[Pillow](https://pillow.readthedocs.io/en/stable/about.html#license)はMIT-CMU。wheelに含まれるcodec/LCMS/OpenMP等の第三者noticeは別途存在し得る。
- 今回は本人のPC環境へ公式wheelを導入するだけで、binaryや依存sourceをrepo/APKへ同梱しない。Android移植・配布前には、実際のLibRaw版とビルドflags、第三者codec、リンク方式、必要notice/source提供・再リンク条件を再確認する。LGPLとCDDLを混在させて免除扱いにせず、配布可否は別判断する。

## CIの範囲

`PC RAW checks`はWindows 2025 x64 / Python 3.15.2の専用venvに`requirements-win-py315.txt`のhash lockを入れ、合成・CLI・再現性のunitだけを実行する。実写真を取得・生成物をuploadしない。既存Android checksとは別で、成功を実DNG decodeやPixel/fp画質の証拠にしない。
[公式setup-python](https://github.com/actions/setup-python)と[公式runner images](https://github.com/actions/runner-images)の手順に従い、checkout/setup-python v7とcontents:readのみを使う。新しいSecrets・常駐環境は追加しない。
