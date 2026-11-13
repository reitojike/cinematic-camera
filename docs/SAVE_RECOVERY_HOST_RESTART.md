# #377 合成 Android process-death 試験の実行待ち手順

**準備のみ。資源枠の許可前は実行しない。** #260 の計測版予行を優先する。
この手順は実機を使用せず、#377 完了・配布 gate 達成を意味しない。

## 対象と証拠の区別

`SaveRecoveryRestartTest` は 256×192 合成 DNG、合成 JPEG、実 AndroidAtomicJournalStorage、
実 MediaStore と標準 publisher を使用する。publisher 境界の fixture は試験側 adapter で停止し、
ホストが生存 PID を force-stop する。例外を process death に読み替えない。
intent/private と b-consent は指定された永続状態を試験側で作る seam fixture。
GPU producer がそこまで到達したという証拠にはしない。b-cursor/b-intent/b-gpu-start は実 factory。
b-gpu-start は EGL context を持つ stop checkpoint での終了であり、任意 GPU 命令の中断ではない。
force-stop は合成 process death であり、LMK や OS メモリ圧の再現ではない。

`SaveRecoveryColdUiProbe` は通常 Compose 画面、通常 factory と FGS を使用する。
保存した要求の強度 .65 と edit-only recipe .35 をわざと異ならせる。
画面表示後の metadata 復元、1.5秒の操作なし監視、未開始 intent 不生成を確認する。
実際の強度 control の要求値65を確認し、READYだけは描画済みの「表示中」も要求する。
HOLDはpreviewを閉じるため表示済み値null/未反映が正当であり、描画完了を要求しない。
FGS ケースでは明示 UI 操作後の ForegroundExports owner を観測し、公開 byte hash を検証する。
通知表示／通知取消そのものの自動操作はこの probe の対象外。既存 FGS 試験を別途選ぶ。

## 実行前条件とコマンド

同じ head の通常 debug APK と AndroidTest APK、API 29+ の予約済み AVD 1台、adb、Python 3 が必要。
AVD の起動・SDK変更・APKインストール・アプリ停止を含む枠を別途確保する。script は boot/build/install をしない。
物理端末は拒否する。`<reserved-emulator-serial>` は調整後の emulator-NNNN に置き換える。

```powershell
# 資源許可後のみ。CI artifact がある場合は同じ head の APK を使い、ローカル build は省略可能。
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
$recoverySerial = '<reserved-emulator-serial>'
adb -s $recoverySerial install -r app/build/outputs/apk/debug/app-debug.apk
adb -s $recoverySerial install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# dry-run は adb を一切呼ばない（準備時に実施済み）。
python -B tools/save_recovery/run_restart_cases.py --suite boundaries
python -B tools/save_recovery/run_restart_cases.py --suite ui

# 予約枠の中で逐次実行。ローカルの結果先は Git 管理外に置く。
python -B tools/save_recovery/run_restart_cases.py --execute --serial $recoverySerial --suite boundaries --output ../recovery-private-results
python -B tools/save_recovery/run_restart_cases.py --execute --serial $recoverySerial --suite ui --output ../recovery-private-results

# 失敗ケース再試行も新しい case id / 新しい synthetic source を使い、旧 evidence を消さない。
python -B tools/save_recovery/run_restart_cases.py --execute --serial $recoverySerial --boundary b-cursor --resume ui --output ../recovery-private-results
```

script の各ケースは `am instrument -w -r -e isolated true -e saveRecoveryRestart true
-e recoveryCase <fresh-id> -e recoveryBoundary <boundary> -e recoveryResume <mode>
-e class dev.cinematic.p0.rawcapture.SaveRecoveryRestartTest#seedAwaitHostKill
dev.cinematic.p0.test/androidx.test.runner.AndroidJUnitRunner` を開始する。
`files/save-recovery-test-<id>/armed.txt` の boundary/PID と `pidof` を照合してから force-stop。
PID 不在を確認し、同じ引数の `#resumeAfterHostKill` を呼ぶ。
seed の instrumentation 切断は想定内だが PASS ではない。resume の `OK (1 test)`、
`resume-ok.txt`、旧新 PID の不一致を全て満たしたケースだけ PASS とする。skip / gate未到達 / PID再利用は失敗。

## 境界と期待状態

| boundary | ホストが止める点 | 別 PID の検証 |
| --- | --- | --- |
| intent | 合成旧 intent の durable write 後 | factory HOLD、再確認で producer 不開始 |
| private | 合成完成 JPEG の close/fsync 後・journal前 | intent-only HOLD、private の存在で未公開と推定しない |
| journal | PREPARED durable write 後・readback前 | 標準 publisher の1行保存、原本 hash一致 |
| insert-before | INSERTING durable write 後・insert前 | 検索ゼロ→UNKNOWN、行を追加しない |
| insert | provider insert成功後・URI ack前 | 唯一の同一行を取得、追加insertなし |
| copy | 出力FDへ先頭chunkの半分を書きflush後 | 既存非UNKNOWN COPY再開契約で同一行完成 |
| close | 出力 close成功後・BYTES_COMPLETE前 | 同一行byte確認後に公開 |
| publish | PUBLISHINGでpending解除の直前 | 同一行公開 |
| ack | provider pending解除成功後・アプリack前 | 公開済み同一行を確認、二重保存なし |
| final-ack | PUBLISHED durable write後・readback前 | 公開済み同一行確認 |
| unknown-empty / unknown-partial | UNKNOWNと空／半分のpendingを保持した時点 | 保留、元journal/intent/row byte不変、recovery未生成 |
| unknown-complete / unknown-public | UNKNOWNと完全一致pending／公開済み行 | 同一行救済、旧UNKNOWN不変 |
| b-consent | fixtureで固定consent確定後・cursor前 | 実factory明示再取得で同じ要求、二度要求しても同handle |
| b-cursor | 実factoryでconsent/cursor確定後・intent前 | 固定READY復元、再確認無producer/intent、旧新排他 |
| b-intent | 新要求intent/STARTED確定後・producer前 | 新要求HOLD、旧履歴と原本保持 |
| b-gpu-start | 新要求GPU context内checkpoint | 新要求HOLD、自動再現像なし |

B は二重 request を同じ既存 serial queue に提出し、同じ handle が戻ることと旧 owner admission拒否を確認する。
UI suite は intent と b-cursor について各 `ui`（cold/recheckのみ）と `fgs`（明示保存）を別 fixture で実行する。
同じ fixture の resume を反復して別の開始状態と混同しない。

## 時間・資源・停止条件

- 境界18ケース: 初回見積り10〜20分。UI/FGS4ケース: 5〜10分。実行値未計測。
- ローカル build が必要なら追加3〜5分。AVD起動/インストール時間は別枠。
- AVD 1台と instrumentation は専有、他レーンの adb / Gradle / Pixel 計測と並行しない。
- 既存 `SaveRecoveryFactoryAndroidTest` 2件は約5分の別枠。新process/UIに重複しない単体境界確認用。
- #365 統合回帰は既存 seedRetryStartedAwaitHostKill→resumeHeld を1組選ぶ。
  CANCELLED_CLEAN の通常再試行は #365 検証を参照し、同じ14ケースをここで重複実行しない。
- gate timeout、権限/元hash不一致、余分な行、旧履歴変更、FGS owner未観測は停止・失敗報告。
  対象行削除・app clear・UNKNOWN解除・CPU fallbackで試験を通さない。

全 fixture は source/output/history を残す。私的ログには合成 URI/PID があり、公開せず
`summary.json` の boundary/mode/PASS/秒数だけを報告する。端末識別子・実写真を扱わない。
未検証はこの全手順の実行、通知からの取消、実機LMK。#377全体／配布gate未達を維持する。
