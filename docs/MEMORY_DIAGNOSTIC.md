# #260 計測専用版（未実測）

#376 の表示・入力 OOME 捕捉に依存する別単位。通常 debug / internal署名 / release の受付条件は変更しない。
**配布版のメモリ判定を緩和する場合、#377 を同じ配布版か先行版に含める。** この診断版は製品方針の採用ではない。

## ビルドと隔離

`./gradlew :app:assembleMemoryDiagnostic :app:testMemoryDiagnosticUnitTest --tests '*MemoryAdmissionDiagnosticTest' -PcinematicMemoryDiagnostic=true --no-daemon`

別 ID `dev.cinematic.p0.memorydiagnostic`、画面名 `Cinematic MEMORY DIAGNOSTIC`、一時 debug 証明書、debuggable。
既存アプリの更新用ではない。stable_internal との組合せは構成時に拒否する。他の診断 variant と同時指定不可。
BuildConfig の flag / build type / 完全一致 applicationId の三条件を満たした場合だけ record-only。
通常版から設定、Intent、環境変数で有効化できない。既存 GPU 合成専用入口の実機制限は変更しない。

記録対象は ActivityManager の実際の availMem / lowMemory / threshold、受付要求値、拒否予定理由、stage、
elapsedRealtime、本人アプリの pid、既知なら outputPixels と RGB16 部分量。総必要量見積りは unknown。
768MiB は測定済み必要量でなく現行受付要求値。/proc MemAvailable とは同一視しない。
通常版は拒否時だけ、計測版は Original24 の各境界と画面 resource 観測時に許可予定も記録する。
計測版では画面 preview の lowMemory / 正の空き量、保存512MiB、Original24の768MiBとlowMemoryを
記録して通過する。温度、容量、camera、foreground、所有、native入力上限、GPU ledger、取消、HOLD は維持する。
旧CPU試験表示専用384MiB blockedReasonは対象外。ログ不能が発生しても allocation の正常系/エラー処理を置換しない。

## 開始確認前の境界と手順

APK組立・静的/JVM検証まで。インストール、配布、端末操作、素材読取、計測は開始確認後。
基本30–40分、再接続・記録照合の予備を含め45–60分を確保する案。既存アプリ/データ/原本を削除せず、他アプリは本人の日常状態のままにする。
新IDなので既存journalを移植しない。既存権限の通常要求と本人による素材選択を使い、追加の広域権限を付けない。
1. 約5分: APK hash/commit/ID確認、本人が12MP/24MP素材を選択。記録開始と操作時刻を対応付ける。
2. 約10分: 12MP開く→調整/比較→保存→再開。新規起動と同一セッションの再表示を含め3回。正常終了を確認してから次へ。
3. 約10–15分: 24MPの同経路を3回。原寸JPEG寸法/読戻しは本人が確認。写真/URI/素材名/写真別値は公開しない。
4. 約5–10分: 単発成功後に12MP/24MPのA保存+Bオープンを各1回。未知所有・公開不明があれば実施しない。

採取は本人アプリ限定の PSS/RSS、Java/native heap、利用可能なら graphics/memtrack、
ActivityManager判定値を時刻付きで照合する。0.5–1秒採取の最大は採取最大であり真のpeakではない。
MaliはCPUと共有RAM。PSS/graphics/texture台帳は重複し得るため加算せず、別時刻最大も加算しない。
既存GPU ledgerのreserve/release後に診断版だけが時刻/予算/現在予約bytes/台帳内最大/texture数を記録する。予算も所有も変更しない。台帳はdriver-resident RAMでなく論理予約で、複数台帳の全体同時所有量は未測定。driverのgraphics情報が得られない場合、GPU実使用量は未測定であり、固定判定撤廃の根拠にしない。
診断ログの割当・I/O・採取はタイミングとメモリへ影響する。数値は計測版の観測であり配布版の無観測性能とは同一視しない。ログ呼出し全体の失敗を保護し、元処理のエラーや所有状態を置換しない。
OS/driverが公開しないGPU量とnative段階内短時間peakは未測定と記す。境界ログだけで全段階peakを保証しない。

## 実行前の固定GPU確認と合成予行

計測版は `DevelopEngineSetting` の実際の既定値を GPU に固定する。通常 debug / release の選択は変更しない。
専用 build type が既定CPUへ落ちる不備があったため、明示GPU指定のテストだけでは合格としない。
通常・計測版それぞれの生成 BuildConfig を使う JVM 回帰と、計測 APK の実設定から開く合成回帰を行う。
修正前のCPU経路の観測はGPUの測定結果として採用しない。

```sh
python3 -B -m unittest discover -s tools/memory-diagnostic -v
./gradlew :app:testMemoryDiagnosticUnitTest --tests '*MemoryAdmissionDiagnosticTest' --tests '*DevelopEngineTest' :app:assembleMemoryDiagnostic :app:assembleMemoryDiagnosticAndroidTest :app:generateDebugBuildConfig :app:generateReleaseBuildConfig :app:processDebugMainManifest :app:processReleaseMainManifest -PcinematicMemoryDiagnostic=true --no-daemon
python3 -B tools/memory-diagnostic/verify_isolation.py app/build
```

通常GPU対応AVDを予約し、active instrumentation / 関連PIDなしを確認してから、自作合成だけで次の2件を実行する。
専用runnerは `dev.cinematic.p0.memorydiagnostic.test/androidx.test.runner.AndroidJUnitRunner`。
`isolated=true` を指定し、後者には `importUiFgsDocuments=true` も指定する。ATDはGPU確認先にしない。

- `GpuProductInputAndroidTest#memoryDiagnosticDefaultGpuEmitsTextureLedgerAndReleasesOwners`: 実設定GPU、編集・比較・再開、原本・owner、正のtexture予約と終了後0。
- `ForegroundExportOwnerTest#memoryDiagnosticDocumentsUiEditsPublishesAndReleasesWithRealGpuLogs`: 実DocumentsUI→編集→実FGS保存、公開JPEG読戻し、原本不変、FGS/owner/自作fixture回収、decode/preview/saveと両ledgerの解放。

起動前に `supervisor.py --adb <adb> --serial <対象> --out <新規ローカル出力先>` をPCの独立プロセスで開始する。
Windowsでは既存Pythonを `Start-Process -WindowStyle Hidden` で起動し、対話execの長時間sessionだけに寿命を依存させない。
採取先は新規ディレクトリのみ。監視状態は直下の `status.json`、採取は `collector/observations.jsonl` に保存する。
停止は直下の `stop.request` を作り、採取器だけを終了する。端末のログ消去・アプリ終了・データ操作はしない。
次操作を案内する唯一の判定は `python3 tools/memory-diagnostic/check_session.py <出力先>` の `ready_for_operator_next_step=true` とする。
監視heartbeat 5秒以内、同じPIDのPSS 15秒以内、GPU等の前提、監視/採取の両方に停止要求なしを全て要求する。
`preflight_evidence` 単独や、過去に一度trueだった記録だけで次へ進めない。欠落・陳腐化・終了記録不明なら再開せず停止する。
監視は子の終了コードと最終 `COLLECTOR_STOP` を別に確認し、採取陳腐化後に正常回収できても失敗を維持する。
自動再起動はしない。監視自身が消失した場合もheartbeat切れで続行不可にする。PC子プロセスの終了操作をAndroidアプリkillと混同しない。
同じ新規起動PIDの GPU / recordOnly=true、decode、正のGPU予約、PSS採取が揃わない場合は待機する。
CPU設定、予期しない受付拒否、PID消失/変更、own crash/kill、thermal 2以上、採取失敗では停止する。
record-only の `wouldPermit=false` 自体は計測目的の観測であり停止理由としない。
捕捉済みOOME等の非クラッシュUIエラー、原本変化、公開/owner不明は採取器だけでは全検出できない。画面・保存結果を各段で確認し、該当時は操作者が停止する。
試験runner終了とOS killは区別し、PID消失だけからOS回収と断定しない。

AVD予行は小型の接続・採取・保存・回収確認であり、Pixel/Maliの共有RAM、12/24MP各3回と並行各1回の実測を代替しない。
同じ最終headから作ったAPKのID・署名・SHA256、通常版flag=false、専用test target、内部署名拒否を開始確認票に固定する。
実機更新・計測再開は別途開始確認後とする。

PC採取器が約12分で終了記録なしに消失した事例は、設定した60分の期限到達や正常終了として扱わない。
外部終了元が確認できなければ未確定と記録する。対話sessionから独立した起動と監視は影響を抑える対策であり、原因確定の代用ではない。
PCのみのfake sourceで20分以上・複数の対話区切りと段階を跨ぐ持続、正常終了、強制終了、監視/採取の陳腐化を確認する。
この予行はADB接続やPixel/Maliメモリの検証ではなく、実機再開を自動許可しない。

## OS回収・終了の観測（詳細）

開始確認後、対象アプリのpid/時間とOSのexit-info（利用可能なら ApplicationExitInfo のreason/timestamp/PSS/RSS）を
照合する。専用Applicationは起動時刻/本人pidと自packageの直近最大8終了記録のreason/status/時刻/PSS/RSSだけを記録する。description/traceは取得しない。履歴が今回より前なら分離し、再起動して記録を得られない場合は未確認とする。pid消失だけでOOMと断定しない。LOW_MEMORY、CRASH、USER_REQUESTED、未取得を区別する。
他アプリ終了は、OSが提供するlmkd等の終了理由を含む証拠が取得できる場合のみ判定する。
他アプリの名前/内容/URI/コマンドラインを収集・公開せず、必要な場合は端末上で時刻/理由/件数へ限定する。
安全に限定できないログは採取しない。未取得は「他アプリ回収未確認」。空き量増加だけで他アプリkillと断定しない。
rootや権限拡張、他アプリ停止、設定変更を行わない。記録欠落/OSのログ制限は限界として残す。

捕捉可能な OOME とOS killは別。OS killではfinally/取消完了を保証できず、#365/#377 の復旧契約に従う。
公開不明時のHOLDを解除したり自動再保存しない。アプリ終了、OOM、原本変化、公開/owner不明、端末過熱で停止し、
記録を保持して相談する。測定失敗を閾値やtimeoutの追加緩和で回避しない。
