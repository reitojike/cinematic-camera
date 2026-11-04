# 検証案: RAW保存と自動現像

保存基盤の機械テストと、未接続部分の受入手順を区別する。アプリが自動現像対応済みとは扱わない。
素材は非公開の一時領域で扱い、画像/URI/原metadata/端末識別情報をGit/Issue/PR/CIへ入れない。

## 機械的な検証

後続基盤はfake storage/rendererと人工バイト列で検証し、既存JUnit suiteへ追加する。
Android CIは既存のlint/unit/debug buildに`:app:assembleDebugAndroidTest`を加え、試験APKもcompileする。CIでemulator試験は実行しない。
設計PRはリンク/要件対応/差分検査とCIで確認し、故障テストが実行されたとは報告しない。

未接続の開始制御（T006〜T008）は以下で限定実行できる（Windowsでは`./gradlew`を`.\gradlew.bat`へ置換）。

```text
./gradlew :app:testDebugUnitTest --tests "dev.cinematic.p0.rawcapture.*" --no-daemon
```

8観測値の全6561組合せ、同時start、撮影予約とのrace、旧/別ownerの完了、停止要求後の未解放、
worker例外とcoordinator再生成を状態として検証する。実CameraX・native中断・台帳/ファイルIOの故障注入とは別である。

受領台帳（T009・T011の台帳側）の限定テストは以下。backendのcommit前/後の失敗、readback失敗/不一致、
破損/未知版/不正field/サイズ超過、revision競合、再生成、root/sidecar pathを人工metadataで検証する。

```text
./gradlew :app:testDebugUnitTest --tests "dev.cinematic.p0.rawcapture.CaptureJournal*" --no-daemon
```

symlinkを作れないWindowsではredirectテストだけをskipと記録し、Linux CIで確認する。
JVMテストは標準AndroidAtomicFileの動作をmockで合格にせず、注入backendと純pathだけを検証する。
実端末のAtomicFile、実DNG/仮JPEGの端末上の保全、撮影との接続、共有原本の引渡しは別の未実施項目として残す。

撮影接続の`RawCaptureSessionTest`/`RawCaptureQueueTest`/`RawCapturePresentationTest`/`RawHandoffAdmissionTest`は、
形式別逆順/部分成功/重複/late、受領前のhash・共有IOなし、未確認fileの非昇格、process所有の非重複、foregroundだけの再開を検証する。
期限後のAUTO late全受領による復帰、manualの既存復帰経路、新manual設定準備中の旧共有通知無効化、
撮影予約とadmissionのrace、停止latch、初回hashのpersistent close失敗と追加IO禁止を含む。
失敗通知を先に配送しcloseを保留したまま別撮影のprepareを実行し、UI待ちとwriter予約解放が別であることを確認する。
queueは実CameraXやセンサーをfakeで合格にせず、既存JPEG単独/動画の回帰suiteも全実行する。

実ファイル保全部品（T010・T011のファイル部分）は次の限定テストで検証する。

```text
./gradlew :app:testDebugUnitTest --tests "dev.cinematic.p0.rawcapture.RawCaptureStoreTest" --no-daemon
```

人工byte列の実一時ファイルにopen/部分write/flush/close失敗と台帳commit前後の失敗を与え、
成功済み形式と途中ファイルのバイト列が保たれることを比較する。JPEG先行、RAWのみ、重複通知、旧revision、
異なるstoreのhandle、未close/close失敗、未記録ファイルを伴う再生成、記録済みファイルの欠落/サイズ変更、
既存directory/fileの非再利用・symlink拒否を含む。symlink権限のないWindowsは当該検証をskipと記録しLinux CIで確認する。
この再生成は実process killではなく、台帳backendは注入したもの。CameraX接続、端末IO/fsync/電源断、DNG decode、
共有引渡し/一時DNG解放、カメラロールの公開件数はこのテストの合格範囲に含めない。

内容識別・照合の前段は`DngContentVerifierTest`、`CaptureJournalTest`、`RawCaptureStoreTest`を含む
`dev.cinematic.p0.rawcapture.*`の限定実行で検証する。既知SHA-256 vector、短い/長い/同サイズ異内容、short read、
read/open/close失敗、協調停止、64KiB上限、版1から版2、fingerprint上書き拒否とcommit応答喪失を含む。
hashを停止callbackで待機させ、その間に別threadのJPEG受領を完了させて、hash結果の古いrevisionがcommitされないことを確認する。
この前段suite単独では初回hashより前の同サイズ変更、URI所有/pending/公開済み、reader lease・共有原本確立/一時DNG解放、実process kill/電源断は未検証。

pending行の確保・URI記録は`DngInsertRecoveryTest`で限定実行できる。fault backend/fake providerを用い、
intent/URI commit前後・readback失敗、insert応答前の中断を`Error`と再生成で模擬し、insert回数と同じURIを確認する。
0/複数/foreign/published/trashed/metadata不一致・query失敗ではUNKNOWNとなり、次のadvanceでprovider APIを呼ばないことを確認する。
未知版/破損/読取不能な台帳は例外で原状停止し、UNKNOWNを上書き記録せずprovider APIも呼ばないことを確認する。
native MediaStoreのinsert/queryはJVMで実行せず、API 29/30以降のpending検索・列/owner可視性・volumeの実動作は端末検証として残す。
このsuiteのROW_RECORDEDをcopy完了・公開完了・共有原本確立の合格としない。既存写真を削除・移動する端末試験は行わない。

`DngHandoffTest`は実一時ファイルとfake providerを使用し、人工DNG byte列/仮JPEGのままcopyから解放まで実行する。
7つの進行phaseそれぞれで台帳commit前/後/readback失敗を注入し、再生成後も同URI・insert最大1・原本少なくとも一方を確認する。
部分write、内容改変、pending解除の失敗/無応答/応答喪失、reader lease、所有予約、削除前後の応答喪失、公開原本の外部不在/変更を含む。
read/出力close失敗では未解放handleを保持し、内容不一致とclose失敗の複合故障でも追加IOへ進まないことを確認する。
成功時は共有1・内部DNG0・仮JPEG不変、公開済みの外部削除後も自動再作成0を確認する。
人工byte列は有効DNGではなく、この結果をdecode・実MediaStore・OS kill・電源断・撮影中性能の合格に数えない。

標準`Instrumentation`の`DngHandoffInstrumentation`は依存追加なしでnative AtomicFile/MediaStoreのbyte手順を確認する。
空の使い捨てemulatorだけで実行し、`isolated=true`と標準emulator hardware（goldfish/ranchu）を要求する。本人の端末や既存AVDへ実行しない。
debug app/test APKをそのemulatorだけにinstall後、serialを明示して次を実行する（`<isolated-emulator>`を対象serialへ置換）。

```text
adb -s <isolated-emulator> shell am instrument -w -e isolated true dev.cinematic.p0.test/dev.cinematic.p0.rawcapture.DngHandoffInstrumentation
```

合成byteを新規の固有撮影IDへ保存し、native公開の後で応答喪失を注入する。再生成後に同URI採用・原本一致・内部DNG0・
仮JPEG不変・共有1を確認し、今回作成した合成rowだけを再照合後に削除して非再作成を確認する。
結果は`handoff_smoke=PASS`または失敗stage/例外型だけとし、URI/端末識別/写真を出力しない。
同じdriverでCameraProbeの生成/closeとアプリ用singleton queueも確認する。人工RAW+JPEGを逆順で受領し、
撮影予約の間は初回hashなし、受領通知後に予約を解除して共有1/内部DNG0・仮JPEG不変となることをnative adapterで確認する。
これはCameraX実撮影を行う試験ではなく、実センサーcallbackの形式・順序・期限と撮影応答はPixel検証に残す。
エミュレータAPIの確認はPixel実機/有効DNG/全API互換/実process kill/電源断の合格と分ける。

| 故障/操作点 | 確認する結果 | 要件 |
|---|---|---|
| RAW/JPEG callbackの逆順・重複・片方失敗・timeout/late | 成功分保全、未完了を昇格しない、旧callbackで現在UIを変更しない | FR-001/002/006 |
| 入力close・受領台帳commit前後のdeath | 受領済み不変、未確認ファイル保全、cache依存なし | FR-001/006 |
| 原本共有保存のopen/copy/close/update失敗 | 一時DNG/仮JPEG不変、DNG共有と現像状態を分離 | FR-003/004 |
| readback短読/同サイズ改変/hash不一致/台帳commit失敗 | 一時DNGを削除しない、SHARED_READYへ昇格しない | FR-001/003 |
| SHARED_READY/解放予約/一時DNG削除/完了commit前後のdeath・削除失敗 | 共有先を再照合して後処理、原本再公開なし、通常完了後は共有1/内部0 | FR-003/006/007 |
| 入力hash遅延・同一process cancel中のreader・旧process leaseを伴う再起動 | hashを撮影busyへ含めない、実reader解放を待つ、旧lease countで永久停止しない | FR-003/005/006 |
| 現像中cancel/例外/容量不足・foreground復帰 | 1 attemptだけ、原本不変、派生途中を公開しない、busy待ちなし | FR-001/004/005 |
| 公開intent前後・insert応答前・URI台帳commit前後のdeath | 所有する同操作だけ照合、曖昧ならUNKNOWN、重複insertなし | FR-006/007 |
| コピー/close/検査前後・pending解除前後のdeath/応答喪失 | 完全JPEGのみ公開、公開済み行を再作成/rollback削除しない | FR-004/007 |
| 公開済み行のユーザー削除・volume不明・複数候補 | 原本保全、UNKNOWNまたは既存完了を維持、自動再公開なし | FR-007/008 |
| recipe変更/古い完了・重複start・撮影予約とのrace | 世代隔離、実行1件、撮影中の新規開始0 | FR-005/006/009 |
| 未知schema/壊れた台帳/root外path | その記録を保全・停止、他撮影や既存写真を変更しない | FR-006/008 |
| 処理開始/公開直前の検証へ原本の外部削除/移動/アクセス失効/同サイズ改変を注入 | 検知時は不在/アクセス不明と変更を区別、当該attemptの公開0、同名別原本へ自動付替えなし | FR-010 |
| 再選択の許可/取消/persist失敗/失効・再起動 | read-onlyで照合、一致時だけ既存recipeへ再関連付け | FR-010/011 |
| 編集台帳消失後に共有DNGを再選択 | 原本は新規編集で開ける、旧recipe/仮JPEG/出力対応の復元と誤表示しない | FR-011 |

アプリ内故障点では少なくとも一方の確認済み人工DNGと仮JPEGのSHA-256不変、仮JPEG公開0件、同一操作の完成JPEG最大1件を確認する。
引渡し/解放が完了すると共有DNGは1件、内部DNGは0件。外部削除/改変は原本を復元できる証明とはせず、公開停止と正しい状態表示を確認する。
最終照合後からJPEG公開までの外部操作は非排他的な限界として別記録し、検知前の公開0件をテストの合格条件にしない。
既存PhotoPublisher/JPEG単独/動画/設定の回帰suiteも実行する。

## Pixel 9aの後続検証（本人の操作）

1. 本人が許可したDNGで#78の比較基準/有効性を確認し、強度0/0.5/1、向き/寸法/色管理を分けて比べる。
2. RAWのみ/RAW+JPEGの対応形式を照会し、新DNGと現像JPEGが共有保存、仮JPEGが共有にないことを確認する。
   共有DNGのhashとread-only現像、正常後処理で内部DNGが残らないこと、コピー/readbackの時間と容量の一時増加を確認する。
3. 同条件で現像無効/有効の撮影受付時間、callback/IO待ち、preview周期、ピークメモリ、熱を測る。
   数値の許容差を有効化前に固定する。未測定の間はcamera使用中の重処理を無効に保つ。
4. 現像中にcameraへ戻る・録画・背景化/復帰を試し、非協調処理が残る時間と撮影影響を記録する。
5. 故障注入と端末上の再開/カメラロール照合を分け、実機未実施を合成テストの成功で置換しない。
6. 本人が破棄可能とした新しい試験データだけで、別アプリによる共有DNGの移動/削除・選択取消・権限失効からの再選択を確認する。
   開始/公開直前の検知で止まる条件と、最終照合後の外部操作を原子的に排除できない限界を分ける。
   再インストールは同じ条件の隔離試験環境か本人が内部データ消失を了承した試験だけで行い、既存ユーザー環境を消去しない。
   共有画像の残存、旧権限が通らないこと、再選択後の読取、旧recipe/仮JPEGが戻らないことを別々に記録する。
7. 端末/OS/使用アプリの版を区別し、標準Photo Picker・Files・利用ギャラリーでDNGの一覧/thumbnail/開く/選ぶ可否と
   DNG/JPEGが2件に見えるかを確認する。片方が表示されないことを未保存と断定せず、MediaStoreと原本readbackを別に確認する。

原本消失・仮JPEGの共有露出・重複公開・撮影の待ち増加やフリーズがあれば該当接続を停止する。
アンインストール/データ消去は内部ファイルを失うため、この検証で既存ユーザーデータを消去しない。
