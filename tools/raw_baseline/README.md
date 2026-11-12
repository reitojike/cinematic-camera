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

## 第10回のアイコニック層（r10*.py）

[Issue #75](https://github.com/reitojike/cinematic-camera/issues/75)の検討用CLI。第7回の確定ルックの上に、写真の個性を出す層と、汎用性の確認で見つかった顔の赤み・白飛びへの対策を重ねる。確定形は`r10j_final.py`の`final_render`にまとめた。

| スクリプト | 内容 | 環境 |
|---|---|---|
| `r10_iconic.py` | にじみ（ブルーム＋ハレーション、全体のディフュージョン）と粒子の比較 | 通常 |
| `r10b_extreme.py` | 振り切った個性のルックの比較（肌の色相は保護） | 通常 |
| `r10c_glow.py` | にじみの強さの段階の比較。標準は「ブルーム＋ハレーション＋弱いディフュージョン」 | 通常 |
| `r10d_character.py` | 標準のにじみの上に控えめな個性ルックを重ねる比較。比較ページの共通部分もここに置く | 通常 |
| `r10e_portrait.py` | 人物向けの効果（ブラック・ミスト、オートン、周辺減光など）の比較 | 通常 |
| `r10f_combo.py` | 好みの効果の組み合わせと、白飛びが多い写真でにじみを弱める自動調整 | 通常 |
| `r10g_generalize.py` | 選んだ組み合わせを第8回の汎用性確認の写真へかける | 通常 |
| `r10h_refine.py` | 顔の赤みの抑え（検出した顔の範囲だけで肌の彩度に上限）とオートンの調整 | 診断専用（顔検出） |
| `r10i_highlights.py` | 白飛びの抑え（明るい部分をなだらかに白へ寄せ、顔が飛ぶ写真だけ全体を暗く） | 診断専用（顔検出） |
| `r10j_final.py` | 確定形`final_render`と、元画像→シンプルな補正→ルック適用→最終形の通し確認 | 診断専用（顔検出） |

確定形の処理順は、第7回の土台 → 標準のにじみ → 顔が白飛びする写真だけ露出を下げる（最大0.7EV） → ブラック・ミスト＋オートン＋周辺減光＋彩度（明るい写真ではにじみを自動で弱める） → 検出した顔の範囲だけ肌の彩度に上限 → 明るい部分をなだらかに白へ寄せる。

- 顔検出はPCでの評価用の代わりで、[OpenCV Zoo](https://github.com/opencv/opencv_zoo/tree/main/models/face_detection_yunet)のYuNet（`face_detection_yunet_2023mar.onnx`、SHA-256 `8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4`、MIT license）を使う。モデルはリポジトリに含めず、各自で取得して`--face-model`で渡す。Androidでは Camera2/CameraX の顔情報や ML Kit を想定し、検出できない顔（マイクや髪で隠れた顔など）は自動では補正されない。
- 白飛びが気になった写真でも、RAWのセンサー飽和はほぼなかった。白飛びは現像（露出、光を足す効果、出力時の切り捨て）で起きていたため、現像側で抑えている。
- 露出は第8回と同じく本人の編集の明るさに合わせたもので、アプリの測光（顔で測り、なければ中央）との組み合わせは未検証。しきい値はこれまでの写真で決めた仮の値。
- 比較ページは非公開のArtifactとして公開する。写真、撮影情報、写真ごとの数値はリポジトリに入れない。

```powershell
& $diagPython tools/raw_baseline/r10j_final.py --livepicture $livepictureFolder --r5 $round5Folder --r8 $round8Folder --face-model $yunetModel --output $newPrivateOutput
```

## Stage Clear v1（stage_clear.py、r11_stage_clear.py）

[Issue #77](https://github.com/reitojike/cinematic-camera/issues/77)で、第10回の確定形を強度つきの`stage_clear.render(base, faces, strength)`にまとめた。定義・処理順・限界は[Stage Clear v1のspec/plan](../../specs/014-stage-clear-v1/plan.md)、P2/P3への引き継ぎは[handoff](../../specs/014-stage-clear-v1/handoff.md)。

| ファイル | 内容 | 環境 |
|---|---|---|
| `stage_clear.py` | Stage Clear v1。入力は第7回の土台と顔マスク。強度0〜1（既定0.5、#86） | 通常（顔検出なし） |
| `test_stage_clear.py`、`golden/stage_clear_v1.json` | 合成ステージ画像の強度別の期待値と、強度1が第10回と一致することの確認 | 通常（CI） |
| `r11_stage_clear.py` | 照明条件別に基準処理と強度0/0.5/0.75/1を同じ原本・同じ露出で並べる非公開ページ。本アプリのPixel 9a DNGも含む | 診断専用（顔検出） |

- goldenは処理を意図して変えるときだけ`python test_stage_clear.py --write-golden`で更新し、`stage_clear.VERSION`も上げる。
- 照明条件の振り分けは顔以外の照明の色と、顔と背景の明るさの差による目安。暗所・高感度は手元の素材の最高ISOで代用している。

```powershell
& $diagPython tools/raw_baseline/r11_stage_clear.py --livepicture $livepictureFolder --r5 $round5Folder --r8 $round8Folder --app $appDngFolder --face-model $yunetModel --output $newPrivateOutput
```

## Android顔情報との比較と採用前判断（#79、設計のみ）

[#79](https://github.com/reitojike/cinematic-camera/issues/79) の既存調査を継承する。24MP/SIGMA fp優先の [#356](https://github.com/reitojike/cinematic-camera/pull/356) に従い、依存導入・推論・製品接続は行わない。顔のGPU連携はリファクタリング後。以下の候補を現行Androidの顔保護実装と読み替えない。

### 現行コードの入口

PCの `r10h_refine.detect_faces/face_area` は外部指定YuNetモデルを使い、表示用8bit BGRへ変換した画像を0/±25/±45度で検出する。scoreは正立.75/傾斜.85、重複を抑え、面積上位6枠から顎・首へ広げたsoft maskを作る。単に同じモデル名を選ぶだけではこの前処理・多方向検出と同等にならない。
Androidの `DetectedFaceMasks.fromBoxes` は既存の固定bbox→mask部品であり、検出器でも測光でもない。`R7PreviewInputs` は中央p97の診断露出と明示楕円ROIのみで `NOT_RUN` を返し、`Original24PreviewSession` は空の保護maskを作る。
`RenderedFaceState`の `VERIFIED_FACES/VERIFIED_NONE/NOT_RUN/FAILED` を区別し、ゼロmaskから「検出成功・顔なし」を推定しない。PC `stage_clear.py` の「ML Kit in the app」という説明は将来候補であり、現行依存・Manifestには顔SDKを導入していない。

### 候補比較

| 候補 | 顔情報・適用入力 | オフライン・サイズ・採用制約 |
|---|---|---|
| Camera2の顔統計 | 撮影フレームのbbox/score。SIMPLE/FULL等の端末能力に依存。外部fp DNGを解析するAPIではない | アプリに検出モデル追加不要。available modes/max face count、要求と実result、撮影原本への対応を確認してから判断 |
| CameraX + Camera2Interop | CameraX管理のcapture callbackで統計を読む接点。CameraX自体を外部画像の顔検出器とは扱わない | 固定1.6.2を維持。session/CameraDeviceを直接操作しない。最新Web APIの後続版deprecationを理由に依存更新しない |
| CameraX ImageAnalysis + ML Kit | ImageAnalysisは画像供給、ML Kitが検出。外部DNGは正確なdecodeから別途検出用画像を作る必要がある | 新use caseの撮影競合、backpressure、ImageProxy.closeの所有を評価。初回案内用の埋込JPEGを測光・保護の正本に流用しない |
| bundled ML Kit 16.1.7 | bbox取得可能。公式目安は画像480×360以上・顔100×100以上。輪郭は今回不要 | モデルは同梱、公式APK増分目安約6.9MB。初回ダウンロード不要だが無通信保証ではない。実APK/依存閉包は未測定 |
| unbundled ML Kit 17.1.0 | Google Play servicesからモデル取得。未取得中の無結果を顔なしにしない | 公式増分目安約800KB、初回取得待ち。初回完全offline要件には不利 |
| 標準 `android.media.FaceDetector` | RGB_565、偶数幅。目の中点/間隔等から顔枠を直接得られない | 追加モデル不要だが、目から枠を推定すると未検証recipeになるため既存maskへの代替採用は保留 |
| YuNet + OpenCV | PC比較用bboxを再利用しやすいが、同じ前処理/モデル/閾値でもAndroid上の精度・速度は未確認 | モデルはMIT。runtime・ABI・同梱binary/第三者noticeは別確認。モデル単体容量をAPK増分とせず、stock OpenCV導入保留を維持 |

公式根拠（2026-11-12参照）：[Camera2顔能力](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)、[顔統計](https://developer.android.com/reference/android/hardware/camera2/CaptureResult#STATISTICS_FACES)、[Camera2Interop](https://developer.android.com/reference/androidx/camera/camera2/interop/Camera2Interop.Extender)、[ImageAnalysis](https://developer.android.com/media/camera/camerax/analyze)、[ML Kit入力・配布方式](https://developers.google.com/ml-kit/vision/face-detection/android)、[標準FaceDetector](https://developer.android.com/reference/android/media/FaceDetector)、[YuNetモデルlicense](https://github.com/opencv/opencv_zoo/blob/main/models/face_detection_yunet/LICENSE)。容量は公式の概算で、このアプリの実測ではない。

### 通信・ライセンスと座標の採用ゲート

[#122の既存比較](https://github.com/reitojike/cinematic-camera/issues/122#issuecomment-6640999544)ではYuNetモデル232,589 bytesに対し、APK内OpenCV本体は両ABI合計85,182,352 bytesと報告されている。異なる祖先APK間の差を厳密A/Bにせず、今回再測定した値とも扱わない。
#122は容量・依存負担による「不採用・保留」で閉鎖済み。試作を再開・複製せず、未決の検出/顔測光/ガードだけを#79で扱う。

ML Kitは入力画像/結果を端末内で処理する一方、利用・性能metrics送信や更新通信を説明している（[Terms & Privacy](https://developers.google.com/ml-kit/terms)、[データ開示](https://developers.google.com/ml-kit/android-data-disclosure)）。WebサンプルのlicenseをSDK/model全体のlicenseとしない。
[#79の既存AAR/POM静的調査](https://github.com/reitojike/cinematic-camera/issues/79#issuecomment-6637683822)ではDataTransport由来の通信権限・job/receiver・初期化providerが報告済み。今回その閉包を再取得・実行したわけではなく、権限だけ除去すれば無通信になるとも判断しない。
採用前に実際の解決版とmerged manifest、推移依存・noticeのAPK収載、初回offline、通信/IPC/更新挙動、APK増分・常駐/推論メモリを確認し、本人の採用判断を得る。

座標契約案ではsource fingerprint、decoder/profile版、有効領域/DefaultCrop、向き・mirror、検出入力寸法と変換、detector/model/policy版を結果へ結び付ける。Camera2のセンサー領域・歪み補正/zoom/cropと表示枠を同一視せず、外部画像にはCamera2統計を転用しない。
正規化したbboxを既存mask部品へ渡す前に変換・整数丸め・範囲を検証する。保護mask、foundation ROI、測光ROIは用途が異なるため同一視しない。失敗/取消/未実行/成功0件を保持し、古い写真・recipeの結果を捨てる。非協調推論の完了前に資源枠を再利用しない。
既存mask部品は長辺1024・候補64/採用6の診断上限であり、24MP full maskの対応を証明しない。原寸への座標対応と帯処理への渡し方は後続設計事項。

### 露出と白飛びガードの推奨順序（未採用）

正確なAS_SHOT入力を固定し、別の検出用表示変換からbboxを得る。測光は線形入力上で一度だけ行い、検証済み顔があれば顔、成功0件なら既存中央p97方針を候補とする。未実行/失敗を成功0件へ補完しない。
顔の集約方法・percentile・目標輝度は未決で、PCの暫定fitを完成仕様として移植しない。自動baseEV、R7 E2、手動EVの責務を分けて二重加算を避け、現行の暫定capは[編集仕様](../../specs/develop/spec.md)を参照する。
R7→glow→iconic/顔の赤み保護→顔ガード判定→必要ならglowed像を減光してiconic/赤み保護を1回再計算→shoulder、というPC `stage_clear.render` の順を比較基準にする。
既存ガードはmask>.5が50画素以上のpoolで表示輝度p90を評価し、最大0.7EV。顔測光値やR7へこの減光を戻して再測光・反復しない。顔ガードは顔を測定に使うが、減光はglowed像全体に掛かる点も比較する。
撮影時AEは別系統。[FACE_PRIORITY](https://developer.android.com/reference/android/hardware/camera2/CameraMetadata#CONTROL_SCENE_MODE_FACE_PRIORITY)は能力があれば3Aに顔情報を使い、統計OFFでも働き得る。従ってbbox取得成功をAE有効の証拠にせず、AUTO/手動露出・要求/実result・撮影画像の確認を分離する。

本人方針としてML Kitを第1候補にし、bundled方式を出発点にYuNetとの精度比較と撮影時Camera2の補助利用を検討する。性能と精度を両立する方式の確立を目指すが、SDK最終採用・通信許可・依存追加・実装は別判断とする。Camera2の撮影補助は外部DNGの静止画検出と分離し、検出枠を統合する方法も未決のまま保持する。
YuNetは比較対象であり正解ラベルではない。一致率のみを採用指標にせず、人が確認した顔領域を基準に、条件別の検出漏れ・誤検出・位置/大きさの誤差・mask被覆を比較する。対象の顔の定義、対応付け/重なりの閾値、許容誤検出・漏れ率は評価前に決め、結果に合わせて緩和しない。
まず合成bboxでcrop/向き/丸め/状態/取消とmaskを検証し、検出精度とは分ける。後の本人承認済み比較では同じ原本・同じ検出入力と線形測光入力を使い、正面/横顔/傾き/小顔/マイク遮蔽/複数顔/低照度/赤青照明/顔なし（看板・照明等）の条件ごとに評価する。推論だけと入力変換込みの時間、cold/warm、メモリ、撮影応答・熱の負荷を精度と併記し、測定条件・未取得を明示する。検出結果、baseEVとguard EVも別記録する。
guardなし/ありの画作り確認も必要であり、現時点で実写精度・性能・顔保護を合格としない。

## 公式根拠とlicense

- [rawpy 0.28.0 PyPI](https://pypi.org/project/rawpy/0.28.0/)、[Params API](https://letmaik.github.io/rawpy/api/rawpy.Params.html)、[LibRaw output parameters](https://www.libraw.org/docs/API-datastruct.html)。処理条件はCLI実装で固定しmanifestに記録する。
- rawpy本体は[MIT](https://github.com/letmaik/rawpy/blob/v0.28.0/LICENSE)。rawpy wheel同梱LibRawの根拠は[LICENSE.LibRaw（LGPL2.1）](https://github.com/letmaik/rawpy/blob/v0.28.0/LICENSE.LibRaw)。LibRaw upstreamは[LGPL2.1またはCDDL1.0の選択肢](https://www.libraw.org/docs/)を示すが、取得したwheelを単にMIT/CDDLだけと扱わない。
- [NumPy license](https://numpy.org/doc/stable/license.html)はBSD3条項、[Pillow](https://pillow.readthedocs.io/en/stable/about.html#license)はMIT-CMU。wheelに含まれるcodec/LCMS/OpenMP等の第三者noticeは別途存在し得る。
- 今回は本人のPC環境へ公式wheelを導入するだけで、binaryや依存sourceをrepo/APKへ同梱しない。Android移植・配布前には、実際のLibRaw版とビルドflags、第三者codec、リンク方式、必要notice/source提供・再リンク条件を再確認する。LGPLとCDDLを混在させて免除扱いにせず、配布可否は別判断する。

## CIの範囲

`PC RAW checks`はWindows 2025 x64 / Python 3.15.2の専用venvに`requirements-win-py315.txt`のhash lockを入れ、合成・CLI・再現性のunitだけを実行する。実写真を取得・生成物をuploadしない。既存Android checksとは別で、成功を実DNG decodeやPixel/fp画質の証拠にしない。
[公式setup-python](https://github.com/actions/setup-python)と[公式runner images](https://github.com/actions/runner-images)の手順に従い、checkout/setup-python v7とcontents:readのみを使う。新しいSecrets・常駐環境は追加しない。


## Android用の合成fixture

`export_android_protections.py` / `export_android_blur.py` / `export_android_glow.py` /
`export_android_mist.py`は`--check`でPC正本との再照合、`--write`でKotlin定数（該当時）とfixtureを生成する。
fixtureはUTF-8/LFのCSVを標準gzipで可逆圧縮し、`app/src/test/resources/stage_clear_*_v1.csv.gz`へ保存する。
Pythonはgzip.decompress、JVMはGZIPInputStreamで展開する。圧縮bytesが違っても解凍後の全画素比較で判断し、
ケース/寸法/誤差境界を保存形式変更のために変えない。mtimeは0、filenameは格納しない。

`test_android_fixture_storage.py`が一時出力への`--write`→解凍→生成text一致→PC再照合を検証する。
通常のPC test discoveryとAndroid unit testに含まれ、生成データはGitHubの既定差分で折りたたむ属性を付ける。
元CSVとのbyte一致は変換PRの証拠として記録し、過去のGit blobからも照合できる。実行時の画像処理や性能の変更ではない。
