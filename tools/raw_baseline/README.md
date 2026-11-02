# PC RAW baseline PoC (P1-A)

[Issue #49](https://github.com/reitojike/cinematic-camera/issues/49) / [spec](../../specs/010-p1a-raw-baseline/spec.md)。PCで基準現像と比較の再現性を調べる小さいCLI。Stage Clear、camera操作、Android実装、upload機能は含まない。

## 専用環境

検証対象はWindows x86-64 / CPython 3.12。既存global Pythonを変更しない。repo rootから、専用venvを作る。

```powershell
python -m venv tools/raw_baseline/.venv
$rawPython = 'tools/raw_baseline/.venv/Scripts/python.exe'
& $rawPython -m pip --isolated install --index-url https://pypi.org/simple --only-binary=:all: --require-hashes -r tools/raw_baseline/requirements-win-py312.txt
& $rawPython -m pip check
& $rawPython -m unittest discover -s tools/raw_baseline -p 'test_*.py' -v
```

rawpy 0.28.0 / NumPy 2.5.4 / Pillow 13.0.0を公式PyPIのCPython3.12 Windows wheel SHA-256で固定。rawpyに同梱されたruntime LibRaw版と機能flagsはmanifestへ記録する。別OS/Pythonのwheelはこのlockの対象外。TLS検証は無効化しない。

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

## 公式根拠とlicense

- [rawpy 0.28.0 PyPI](https://pypi.org/project/rawpy/0.28.0/)、[Params API](https://letmaik.github.io/rawpy/api/rawpy.Params.html)、[LibRaw output parameters](https://www.libraw.org/docs/API-datastruct.html)。処理条件はCLI実装で固定しmanifestに記録する。
- rawpy本体は[MIT](https://github.com/letmaik/rawpy/blob/v0.28.0/LICENSE)。rawpy wheel同梱LibRawの根拠は[LICENSE.LibRaw（LGPL2.1）](https://github.com/letmaik/rawpy/blob/v0.28.0/LICENSE.LibRaw)。LibRaw upstreamは[LGPL2.1またはCDDL1.0の選択肢](https://www.libraw.org/docs/)を示すが、取得したwheelを単にMIT/CDDLだけと扱わない。
- [NumPy license](https://numpy.org/doc/stable/license.html)はBSD3条項、[Pillow](https://pillow.readthedocs.io/en/stable/about.html#license)はMIT-CMU。wheelに含まれるcodec/LCMS/OpenMP等の第三者noticeは別途存在し得る。
- 今回は本人のPC環境へ公式wheelを導入するだけで、binaryや依存sourceをrepo/APKへ同梱しない。Android移植・配布前には、実際のLibRaw版とビルドflags、第三者codec、リンク方式、必要notice/source提供・再リンク条件を再確認する。LGPLとCDDLを混在させて免除扱いにせず、配布可否は別判断する。

## CIの範囲

`PC RAW checks`はWindows 2025 x64 / Python 3.12.10の専用venvに同じhash lockを入れ、合成・CLI・再現性のunitだけを実行する。実写真を取得・生成物をuploadしない。既存Android checksとは別で、成功を実DNG decodeやPixel/fp画質の証拠にしない。
[公式setup-python](https://github.com/actions/setup-python)と[公式runner images](https://github.com/actions/runner-images)の手順に従い、checkout/setup-python v7とcontents:readのみを使う。新しいSecrets・常駐環境は追加しない。
