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
GPU工程の結果はGPUスレッド内で収集し、既存Future完了後にqueue IOからまとめて書く。
帯・画素ごとのディスク書込みは行わない。

開始イベントの永続化後に時計を開始し、終了時刻は終了イベントの永続化前に採る。計測対象に自身の記録I/Oを含めない。
開始イベントは処理前、通常工程の終了イベントは処理後に保存する。ただし耐障害journalではなく、
fsyncによる電源断耐性は保証しない。OS killで開始しかない場合や、GPU区間の集約前に終了した場合、
その後の工程結果は欠測。開始/終了の欠落は記録I/O失敗やローテーションでも起こるため、
killと断定せず、保存成功/失敗/取消へ推定しない。計測外の処理を含む全保存時間とは扱わない。

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
