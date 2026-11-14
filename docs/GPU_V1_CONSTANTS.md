# V1 定数の生成元と共有定義

GPU の画素式は製品 GLSL、定数の Kotlin 定義は
`app/src/main/java/dev/cinematic/p0/gpu/constants/` に置く。
CPU 参照・診断が残っている間も同じ定義を使い、値のコピーや旧 raw パッケージの別名定義を持たない。
`ShaderConstants` が名前・配列要素を define へ展開し、`ShaderSource` が shader に注入する。

| 共有定義 | 生成元（tools/raw_baseline/） | define 接頭辞 |
|---|---|---|
| StageClearV1 | export_android_protections.py | SC_STAGECLEAR |
| StageClearGlowV1 | export_android_glow.py | SC_GLOW |
| StageClearMistV1 | export_android_mist.py | SC_MIST |
| StageClearOrtonV1 | export_android_orton.py | SC_ORTON |
| StageClearCharacterV1 | export_android_character.py | SC_CHARACTER |
| R7BaseV1 | export_android_r7.py | SC_R7 |

Python の既存選択値・行列から生成する関係は維持する。生成元の数式や PC の画素 fixture を
GPU の正解判定へ変更するものではない。生成先の `.kt` を手編集して二重の正本にしない。
`--check`（既定）は生成定数と既存 PC fixture を照合し、`--write` は両方を書き出す。
`--write` は fixture 更新を伴うため、移動確認だけのために作業tree上で実行しない。

## 移動時と以後の検証

- `ShaderConstantsTest.definesMatchThePreMoveContract` は、移動前 commit `8d20dccf` の実コンパイル済み
  `ShaderConstants.defines()` から採取した109行を全文比較する。
  `app/src/test/resources/shader_v1_defines.txt` を差分解消のために自動更新しない。
- 既存の反射による全V1定数の名前・値・型・配列順・展開検査を維持する。
- 固定 Python 環境（[既存手順](../tools/raw_baseline/README.md)）で次を実行する。

```text
python -m unittest discover -s tools/raw_baseline -p test_v1_constant_exports.py -v
```

この3試験は各6生成器の出力先・宣言全文・`--write`/`--check` の経路と破損検知を確認する。
write/check経路試験は一時領域と小さな代用fixtureを使い、実画素fixtureの再生成一致を代替しない。
旧 `raw/*V1.kt` の再作成や値の複製を許さない。

GPU/CPU/診断それぞれのビルド・unit、通常Androidの1024px最小性能試験、
[90条件GPU回帰](../tools/gpu-golden/README.md)を別々に記録する。
生成定数の移動だけで既存のCPU安全assertを削らない。旧保存識別、共有型・入力解析、
LibRaw/JNI、AS_SHOT、原本・取消・retry/HOLD・owner契約は維持する。
