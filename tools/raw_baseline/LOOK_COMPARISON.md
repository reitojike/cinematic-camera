# PC RAW 4案ルック比較（非公開・手動領域PoC）

[Issue #54](https://github.com/reitojike/cinematic-camera/issues/54) / [spec](../../specs/011-raw-look-comparison/spec.md)。
既存baselineを変えず、明るい肌・照明・衣装・暗部の好みを比較する。Stage Clear完成、汎用肌検出、
Pixel画質、Android/NDK/GPU/gallery成立の証拠ではない。新依存は追加しない。

## 実行と再生成

既存の[専用Python環境](README.md)を使い、許可された素材rootとprivate recipeを指定する。
入力root外かつ未作成のoutput folderへ生成する。出力parentは事前に存在する実directoryとする。

```powershell
& $rawPython tools/raw_baseline/look_compare.py --root $authorizedRoot --recipe $privateRecipe --output $newPrivateOutput
& $rawPython tools/raw_baseline/look_compare.py --root $authorizedRoot --recipe "$newPrivateOutput/recipe.json" --output $anotherNewPrivateOutput
```

同じ固定環境/コード/入力/recipeならmanifestのartifacts画像hashを比較できる。日時を正規化したsRGB ICCを使う。
別環境の同一byteは保証しない。元素材hash/size/mtimeの前後不一致は出力を公開せず失敗する。
stdoutは匿名statusのみ。実素材・recipe・manifest・画像はGit/Issue/PR/Library/外部サービスへ送らない。
出力には元素材名が入るprivate再生成記録もある。共有可能な素材とは扱わない。

recipe schema=1、scenes=1..3。例は実在素材を示さない。

```json
{"schema":1,"scenes":[{
  "id":"sample","source":"original/sample.dng","edited":null,
  "profile":"sigmafp","wb":"camera","base_ev":1.0,"skin_ev":0.5,
  "skin":[[0.50,0.40,0.08,0.06]],
  "focus":{"skin":[0.35,0.30,0.65,0.50],"costume":[0.2,0.5,0.8,0.9],"light":[0.0,0.0,0.3,0.3]},
  "note":"手動の比較設定。自動肌検出ではない。"
}]}
```

- sourceは許可root相対DNG。本比較PoCのprofileはsigmafp限定。Pixel/未知機種は拒否し、
  既存baseline CLIの対応とは分ける。WBはcamera/daylightの明示選択。
- editedは任意root相対JPEG。ICC必須、sRGBへrelative-colorimetric変換、EXIF回転のみ適用。
  自動fit・crop・位置合わせ・レタッチ推測をしない。PNG元参照とは別にquality95 JPEG比較版を生成する。
- base_evはscene共通の手動ゲイン（-2..3）。skin_evはsceneの局所ゲイン追加（0..2）。
  EVは暗部ゲインの上限を示し、全輝度へ厳密に同じEVを掛けるものではない。
- skinは画像回転後の正規化ellipse [cx,cy,rx,ry] を最大16個。外側45%をsmoothstepでfeather。
  maskは個別写真への人手指定で、顔/首/腕への境界漏れ・髪/衣装への影響を観察する。
- focusは正規化box [x0,y0,x1,y1]。noteはlocal比較に出る観察条件。原metadata/GPSを入れない。

## 基準と暗さの切り分け

旧固定基準はLibRaw AHD・camera WB・linear sRGB・16bit・gamma1・自動明度OFF・固定bright/exp_shift。
その後IEC sRGBを一回適用。従来の有界基準にはWB/色変換/SDR clipの限界がある。
DNGのBaselineExposureやProfileToneCurveなど表示意図を持つtagが、この固定処理では採用されない場合がある。
従って一般的コンバーターの標準表示の正解とは呼ばない。実素材ごとに独立監査し、説明不能の異常ならlookを止める。

比較は旧固定基準、共通明度土台、4案、任意本人JPEGを分ける。土台は手動base_evの有界カーブのみ。
DNG推奨露出を参考にできるが自動採用せず、ProfileToneCurveの完全再現とも主張しない。
実素材の監査値、選んだEVと理由は入力外のprivate記録へ置く。

## 実際の処理

linear sRGBで L=.2126R+.7152G+.0722B。手動maskを、黒い髪を過剰に上げない重み
min(L/.025,1)で弱める。g=2^(base_ev + look.ev + (skin_ev+look.local_ev)*mask)。
有界の T(L)=gL/(1+(g-1)L) をRGB比で適用する。黒と白を固定し、白へ滑らかに圧縮する。
lookのcontrastは黒/.18/白を固定するodds-powerで、局所領域では差を弱める。
具体的には p=1+(contrast-1)(1-.75mask)、a=T^p、b=(1-T)^p(.18/.82)^(p-1)、新輝度=a/(a+b)。
微小RGB gainは輝度を保つよう再正規化し、彩度は局所と背景で分ける。
範囲外RGBはクリップした輝度へのchroma圧縮で表示範囲へ収め、IEC sRGBを一回適用する。
処理後pre-gamut指標はセンサー飽和の指標ではない。clipした入力情報や色照明下の失われた色は復元しない。
NR・美肌平滑化・顔形状変更・AI・multi-frameは行わない。高ISOの増幅noiseは評価対象。

4案の実パラメータはlook_compare.pyのLOOKSとprivate manifestに保存する。look単独の差と
明度土台の差を混同せず、肌明度/肌色/照明背景/柔らかさを別に選ぶ。

## UI・検証

index.htmlをlocalで開く。2枚を任意に選択、4案thumbで右を切替、全体/等倍/肌/衣装/照明・pan/zoomは同期。
等倍はCSS 1:1、異なるcropの本人JPEGは正規化座標の同期で、pixel registrationではない。
メモは保存/送信しない。file表示が拒否された場合、別server等で迂回せず未描画と明示する。
ブラウザの色管理/補間/物理画面と本人の好みは機械検証と別。

```powershell
& $rawPython -m unittest discover -s tools/raw_baseline -p 'test_*.py' -v
node --check tools/raw_baseline/compare_web/app.js
node tools/raw_baseline/compare_web/test_math.cjs
git diff --check
```

合成テストのみCIへ送る。実素材の原本前後hash、画像metadata、再生成hash、全体/肌/衣装/照明の目視はprivate記録。
Draft PRの一般コード/文書だけを公開前検査し、最終headの独立/canonicalレビュー/必須CIを確認。
merge/Releaseは別承認。
