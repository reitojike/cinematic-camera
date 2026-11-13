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
目安45–60分。既存アプリ/データ/原本を削除せず、他アプリは本人の日常状態のままにする。
新IDなので既存journalを移植しない。既存権限の通常要求と本人による素材選択を使い、追加の広域権限を付けない。
1. 5–10分: APK hash/commit/ID確認、本人が12MP/24MP素材を選択。記録開始と操作時刻を対応付ける。
2. 10–15分: 12MP開く→調整/比較→保存→再開。正常終了を確認してから次へ。
3. 15–20分: 24MPの同経路。原寸JPEG寸法/読戻しは本人が確認。写真/URI/素材名/写真別値は公開しない。
4. 10–15分: 単発成功後に必要なら12MPのA保存+Bオープン。未知所有・公開不明があれば実施しない。

採取は本人アプリ限定の PSS/RSS、Java/native heap、利用可能なら graphics/memtrack、
ActivityManager判定値を時刻付きで照合する。0.5–1秒採取の最大は採取最大であり真のpeakではない。
MaliはCPUと共有RAM。PSS/graphics/texture台帳は重複し得るため加算せず、別時刻最大も加算しない。
既存GPU ledgerのreserve/release後に診断版だけが時刻/予算/現在予約bytes/台帳内最大/texture数を記録する。予算も所有も変更しない。台帳はdriver-resident RAMでなく論理予約で、複数台帳の全体同時所有量は未測定。driverのgraphics情報が得られない場合、GPU実使用量は未測定であり、固定判定撤廃の根拠にしない。
診断ログの割当・I/O・採取はタイミングとメモリへ影響する。数値は計測版の観測であり配布版の無観測性能とは同一視しない。ログ呼出し全体の失敗を保護し、元処理のエラーや所有状態を置換しない。
OS/driverが公開しないGPU量とnative段階内短時間peakは未測定と記す。境界ログだけで全段階peakを保証しない。

## OS回収・終了の観測

開始確認後、対象アプリのpid/時間とOSのexit-info（利用可能なら ApplicationExitInfo のreason/timestamp/PSS/RSS）を
照合する。専用Applicationは起動時刻/本人pidと自packageの直近最大8終了記録のreason/status/時刻/PSS/RSSだけを記録する。description/traceは取得しない。履歴が今回より前なら分離し、再起動して記録を得られない場合は未確認とする。pid消失だけでOOMと断定しない。LOW_MEMORY、CRASH、USER_REQUESTED、未取得を区別する。
他アプリ終了は、OSが提供するlmkd等の終了理由を含む証拠が取得できる場合のみ判定する。
他アプリの名前/内容/URI/コマンドラインを収集・公開せず、必要な場合は端末上で時刻/理由/件数へ限定する。
安全に限定できないログは採取しない。未取得は「他アプリ回収未確認」。空き量増加だけで他アプリkillと断定しない。
rootや権限拡張、他アプリ停止、設定変更を行わない。記録欠落/OSのログ制限は限界として残す。

捕捉可能な OOME とOS killは別。OS killではfinally/取消完了を保証できず、#365/#377 の復旧契約に従う。
公開不明時のHOLDを解除したり自動再保存しない。アプリ終了、OOM、原本変化、公開/owner不明、端末過熱で停止し、
記録を保持して相談する。測定失敗を閾値やtimeoutの追加緩和で回避しない。
