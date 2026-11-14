# 通常保存の工程時間記録

通常の保存受付と明示的な公開再照合について、アプリ私有の `noBackupFilesDir/save-timing` に記録する。
記録は診断情報であり、保存・公開・取消・復旧の判定には使わない。記録の作成・書込み失敗は保存側へ伝播させない。
原本、JPEG、署名、DNG展開の方式とメモリ条件は変更しない。

## 形式と保持範囲

UTF-8互換のASCII TSV、1行1イベント、ヘッダなし。列の順は次のとおり。

| 列 | 意味 |
|---|---|
| schema | 現在は `1` |
| attempt | 保存受付ごとのランダム128bit識別子。写真・世代・端末から導出しない |
| version | APKのversionCode。正式/開発の固定署名版は同じ単調増加採番 |
| kind | `SAVE` または明示再照合の `RECHECK` |
| stage | 固定工程名 |
| outcome | 固定結果名 |
| pixels | 検証済み出力幅×高さ。未取得は `NA` |
| nanos | CPU単調時計の経過ナノ秒。未計測は `NA`（0と区別） |

名前、URI、captureId、世代要求、写真fingerprint、画像、例外本文/stack、実時計、端末識別子は記録しない。
同じattemptの寸法確認後のイベントに画素数が入り、展開前や展開直後の寸法確認前は `NA` になる。
公開のみ再試行するときは既存JPEGの出力寸法を使う。生成を行わなかった工程に0msを付けない。

最大1,024イベントかつ256KiB。古いイベントからローテーションし、読み出し中も上限を守る。
固定名の一時ファイルを置換するため、記録領域は通常ファイルと一時ファイルを合わせ最大512KiB。
終了しなかった一時ファイルは抽出しない。再起動・同一IDの通常アップデート後も私有記録を保持するが、
アンインストール、アプリデータ削除、端末喪失、ローテーション後の過去記録は回復を保証しない。
不正schema・不正列・切れた末尾は抽出せず、上限超過ファイルは読取り対象にしない。
複数store/試行の書込みとsnapshotはプロセス内の同じロックで直列化する（アプリは単一プロセス）。

## 計測区間

| stage | 含む処理・限界 |
|---|---|
| NATIVE_DECODE | 既存JNI呼出し `NativeOriginalRaster.decode24`。入力の事前照合・snapshotコピー・事後descriptor close・縮小解析は含まない |
| B1 / B1_QUANTILES / B2 / B3 / B4 / B5 / FINAL | `GpuExportRenderer` の同名boundary。CPU側の帯入力読込み・変換・GL発行・読戻し等と既存のGPU fence完了待ちを含むwall。純GPU timer値ではない。新たなfenceは追加しない |
| GPU_RENDER | GL thread/contextの準備からrenderer実行、既存の完了待ち、thread/context cleanupまで |
| JPEG_ENCODE | `openOutput`、Bitmap.compress、stream write・digest更新・flush・closeを含む既存区間。JPEGコーデック単体の時間ではない。事後JPEG検査・原本再照合・公開を含まない |
| RESOURCE_CLOSE | 既存資源解放の各呼出し。複数回を別イベントとして保持し、最後の値で上書きしない |
| PUBLICATION | 既存publisherのadvance全体。pending行処理・copy・検証・公開やjournal処理のうち実際にadvanceが実行した範囲。再試行では再利用/再照合のみの場合もある |
| RECONCILIATION | 明示的な既存公開記録の読取りとreconcile。生成とは別イベント |

`COMPLETED` はその区間の正常returnだけを示す。公開成功の証拠は既存journalであり、
記録上の全体結果は `ATTEMPT/PUBLISHED` 等で区別する。`INCOMPLETE` は区間途中の例外・取消等であり、
完了待ちが成功した計測とは扱わない。失敗区間の経過値を成功値の集計へ入れない。
既存のBITMAP_PACKはGPU全体と記録I/Oを内包するため本記録では採用しない。
GPU_RENDERとB1〜FINALも内包関係にあり合算しない。

実際の分岐で省略したB2/B3/B5は `SKIPPED`。失敗前に未到達の工程は `NOT_REACHED`。
収集失敗や成功した描画の記録欠落は `UNAVAILABLE`。未取得を0で補間しない。
GPU工程の結果はGPUスレッド内で収集し、既存Future完了後にqueue IOでメモリ記録へ加える。
帯・画素・工程ごとのディスク書込みは行わない。

### 終了時の一括記録（dev.6 向け）

保存試行中は最大1,024イベントのメモリ内バッファだけを更新し、既存の保存処理・公開・資源解放が
終了した時点で、成功・失敗・取消・保留のいずれでも1回だけファイルを読み取り、一時ファイルへ書いて置換する。
複数試行の一括書込みとsnapshotは同じロックで直列化する。二重finishや終了後のイベントは書き込まない。
既存dev.5のschema 1とSTARTED行はそのまま読み取り・保持し、新規のSTARTED行だけを出さない。
通常のGPU新規保存は15行（GPU境界7、展開・GPU全体・JPEG・公開各1、資源解放2、
未到達の照合1、最終結果1）。省略・未到達の値はNAのままで、0を捏造しない。
再照合・失敗・再試行では行数が異なる。

各工程の終了時刻はメモリへのイベント追加より先に採る。最終ATTEMPTのnanosは
beginTimingから既存保存処理が戻った直後までのwall timeで、最終的な記録のファイルI/Oは含まない。
中に実行される小さいメモリ記録処理や受付・後処理は含む。純GPU時間や各工程の単純合計とは扱わない。
dev.5のATTEMPTには全体経過値がないので、全体時間を直接比較できない。
dev.5のGPU_RENDERには内包する工程の記録I/Oが含まれるため、版間の差には記録方式の改善も混ざる。
旧版の時間を、記録方式だけを補正した値として推定しない。

終了前にOSに終了された試行はメモリ内の記録が失われる。開始行は永続化しない。
記録がないことを保存失敗・取消・OS終了の証拠にはしない。保存状態の復旧は既存journal（#377）が担う。
記録は耐障害journalではなく、fsyncによる電源断耐性は保証しない。書込み失敗も保存へ伝播しない。

### dev.5の保持回数と比較手順

通常のGPU新規保存が毎回15行の場合、dev.6で68回保存すると1,020行、69回では1,035行になる。
新規記録だけで上限を超える69回目には、残っていたdev.5記録は全てローテーションされる。
ただし「68回までdev.5全件が残る」という意味ではない。アップデート時点の有効行数をNとすると、
既存記録を1行も落とさず追加できる通常保存回数は floor((1,024 - N) / 15)。
既に満杯なら最初の保存から古い行が消える。再照合や失敗等も行数を消費するため、
保存回数だけでは厳密な残存量を判定できない。固定schemaの最大行長でも1,024行は256KiB未満なので、
正常な記録ではイベント数の上限が先に効く。Pixelの現在のNは抽出するまで不明。

1. dev.6導入前にdev.5の記録をUSBで回収する。抽出はアプリ側の記録を消さない。
2. 抽出ファイルを保管し、versionとattemptで版・試行を区別する。繰り返し抽出の重複を別試行として数えない。
3. 比較対象はNATIVE_DECODE、B1〜FINAL（B1_QUANTILESを含む）、JPEG_ENCODE、PUBLICATIONに限る。
   同じ画素数・工程・成功状態で比較し、INCOMPLETE、NA、SKIPPED等を成功の0msにしない。
4. GPU_RENDERとATTEMPTはdev.5/dev.6比較から除外する。旧版の記録I/O影響や計測値欠如があるため。
   GPUの各境界は別途収集してFuture完了後に記録し、展開・JPEG・公開は開始記録後から終了記録前まで
   時計を取る既存区間を維持する。この比較でも普段使いの入力・端末状態の違いは別要因として扱う。

## USBからの抽出

非debuggableの通常releaseでも、USBデバッグを本人が許可したPCのadb shellからだけ読み取れる。
`DUMP`権限とshell UIDを確認する固定URIのread-only ContentProviderを使用する。
URI grant、任意path、書込み、写真や他の私有ファイルへのアクセスは提供しない。
shellのDUMP権限は [AOSP Shell manifest](https://android.googlesource.com/platform/frameworks/base/+/master/packages/Shell/AndroidManifest.xml)、
pipeによるread-only読出しは [ContentProvider.openFile](https://developer.android.com/reference/android/content/ContentProvider#openFile(android.net.Uri,java.lang.String)) に従う。

本人の実機操作承認後、接続先を確認して次を実行する。placeholderを実際の許可対象へ置き換える。
取得した記録はローカルで保持し、写真や端末識別子を公開ログへ加えない。

```text
adb -s <authorized-device> exec-out content read --uri content://dev.cinematic.p0.save-timing/records
```

標準出力をホスト側でファイルへ保存する。`run-as`、root、アプリのdebuggable化は不要。
開いている保存を停止せずsnapshotを取得でき、取得失敗を保存失敗とみなさない。
抽出は記録を消さない。実機での取得可否は、合成AVDの受入結果と区別して記録する。
