# 設計
既存未commit試作はOrton合成まで全面GPUで扱った。過去の元ソースと生ログは現在の保存場所で見つからず、記録だけ確認できている。重複する全面ぼかし実験を行わない。
新規の範囲は既存CPU全画像ぼかしを保ったままOrtonの合成を64行帯へ制限するGLES3テスト専用経路。新規ファイルのみ、Android標準EGL/GLESを使用。負値/HDRのためRGBA32Fを選び、renderable float拡張とframebuffer completenessを確認し、非対応は明示失敗する。
GL contextと全objectは呼出しthread所有。帯upload/draw/readback前後で取消確認。途中出力は公開せず、例外でもfinallyでtexture/framebuffer/program/contextを解放する。CPU原寸の行workerとGL threadを混ぜない。
field準備はCPUの既存全画像演算を使用する。R7/Glow/Mist/Character/顔保護/shoulderは既存CPUを使い、Ortonだけ差し替えた全Look比較で差の積み重なりを確認する。原寸保存への接続は対象外で、当試作だけで12/24MP保存合格にはしない。#210 の全工程帯処理は見送り済みであり、その再開を前提にしない。
固定GPU入力はsource RGBA32F(α=skin)とsoft RGBA32F、出力RGBA32F。幅4000×64行で各3.90625MiB、3面11.71875MiB。CPU transfer用3面を含め23.4375MiBが名目容量。6000幅は35.15625MiB。driver/field/CPU画像・GCは別で観測する。
初期試作はR7準備を除いたLook全体の時間と処理後のmanaged/nativeスナップショットを記録する。CPU準備・upload/draw/readback別の内訳、一定間隔のメモリ観測は未実装であり、ピークメモリを保証しない。ATD/lavapipeは成立性のみ、Pixel性能へ外挿しない。
公式: https://developer.android.com/guide/topics/graphics/opengl

製品には未採用、検証基盤のみ。専用引数 gpuBandProbe=true がない場合は JUnit assumption により CPU/GPU 処理より前にスキップする。実機 GPU と実写での再判定条件と明示実行方法は [spec](spec.md) に従う。
