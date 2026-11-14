# プレビュー受付・時間観測の GPU 安全試験

Issue #290。`GpuPreviewObserverSafetyAndroidTest` は通常 `androidTest` で、
受付制御・時間観測が GPU 出力を変えないことを検証する。製品コード・数式は変更しない。

| 旧 CPU 試験の保証 | 通常 GPU 試験 |
| --- | --- |
| `PreviewRequestGateTest.enabledGatePreservesSyntheticPreviewBitsAndSource` | `enabledGatePreservesOutputBitsAndSource` |
| `PreviewTimingTest.observingRealSyntheticPipelineDoesNotChangeAnyOutputBits` | `timingPreservesOutputBitsAndSource` |

固定 seed の MIXED 128×96 入力を FULL_RECOMPUTE で2回処理し、受付ゲートあり/なし、
時間観測あり/なしで DISPLAY8 読み戻しの全 float bits を比較する。
各回の全13工程到達、有限値、寸法、入力 bits 不変、写真解析1回、終了時 ledger の
texture/bytes が0であることを assert する。時間観測は全工程の項目と完了 outcome も検証する。
これは同一 GPU 内の非干渉保証であり、旧 CPU との画素一致や UI 応答時間の保証ではない。

待ち行列・取消・失敗・時計例外・終了通知などの JVM 試験は維持する。
CPU pipeline 本体の寸法上限・段階取消・数値試験はこの2件と別であり、削除対象ではない。
Android 全工程90条件の基準画像回帰と1024px性能試験も独立して維持する。

## 再実行

[共有端末手順](MANAGED_DEVICES.md)に従い、レーンを予約したエミュレーターのみを使う。
通常 debug APK と androidTest APK をビルドし、既存データを消さず `adb install -r` で導入する。
撮影・保存・MediaStore・設定変更は行わない。

```text
adb -s <emulator-serial> shell am instrument -w -r -e class dev.cinematic.p0.gpu.GpuPreviewObserverSafetyAndroidTest -e isolated true -e gpuPreviewObservers true dev.cinematic.p0.test/androidx.test.runner.AndroidJUnitRunner
```

引数不足・非エミュレーター・GL能力不足は失敗とし、skip にしない。
実機実行は認めない。最終 head、コマンド、実行2件、成功/失敗/skip、時間を PR に記録する。
0件・skip・コンパイルだけでは合格にしない。
