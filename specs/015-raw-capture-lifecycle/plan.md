# Implementation Plan: RAW撮影後の保全と自動現像

**Branch**: `docs/030-raw-save-auto-develop` | **Date**: 2026-11-04 | **Spec**: [spec.md](spec.md)

## Summary

撮影保存、原本保全、現像、共有公開を分離する。最初のPRはこの設計と整合する方針更新のみ。
撮影へつながない純粋policy、永続化、公開、撮影接続、#78統合を順に別の小さいDraft PRとする。

## Technical Context

- 単一Android app、既存Kotlin/CameraX 1.6.2/標準MediaStoreを維持。新依存は今回追加しない。
- Pixel 9a/API 29以上を共有保存の先行対象とし、API 28の現行非対応表示を広げない。
- 撮影記録は`noBackupFilesDir/raw-captures/<captureId>/`、1撮影1台帳を`AtomicFile`で更新する候補。
- 台帳・ファイル・MediaStoreを横断する原子transactionはない。単一writerと照合による復旧が必要。
- テストは既存JUnit、fault injection、最終headのAndroid CI。実画像の非公開検証は合成テストと分ける。
- 実測前はcamera sessionを使用中のdecodeを許可しない。撮影待ち時間の数値や即時cancelを保証しない。

## Constitution Check

- I/III: 合意と観測可能な仕様、未実装/未検証を区別。実機結果を生成しない。
- II/V: 標準API、単一app、依存なし。CameraDevice/sessionへ独自介入しない。
- IV/VI: DNG共有保存・原本不変・private仮JPEG。今回のJPEG公開方針へ原則IVと002計画を明示改訂する。
- 初回/設計後のgate: 仕様上の原本削除や新権限はなし。公開/CI/レビュー未取得は別の作業gateとして残す。

## 現行との差と所有境界

`CameraProbe.captureFiles`はcache stagingでRAW/JPEG callbackを待ち、`PhotoPublisher.publish(sources)`で
両方を公開する。callback失敗、terminalな遅延callback、公開終了finallyにcleanupがある。
`PhotoPublisher`は複数行のうち公開済みの行もcatchのrollback対象にする。これは新しい復旧契約に流用しない。

| 担当 | 変更場所 | 今回触れない場所 |
|---|---|---|
| 保存設計/基盤 | `specs/015-raw-capture-lifecycle/`、将来`app/src/{main,test}/java/dev/cinematic/p0/rawcapture/` | `tools/raw_baseline/`、#78の比較fixture/画素処理 |
| #78移植 | 画素処理とPC比較の専用ファイル。実配置は移植側が決める | 保存台帳・MediaStore・CameraProbeへの接続 |
| 後続の統合PR | CameraProbeから新保存経路へ1か所ずつ接続、必要なpolicy呼出し | 既存JPEG単独/動画/設定制御 |

これは提案境界。#78側の合意前に共有DTOや画素処理namespaceを作らない。
受け渡す意味は[内部契約](contracts.md)に固定し、共有型の所有者は統合時に1つへ決める。
調査判断とデータモデルは本planへ統合する（repoのSpec Kit運用と同じ）。外部APIは増やさず、内部契約だけを分離する。

## 保存・復旧の設計

1. 撮影IDと期待形式を台帳へ記録し、CameraX出力を専用永続領域へ渡す。cacheを唯一の保全場所にしない。
2. 形式別callbackを世代/IDで照合し、close済み成功ファイルを検査して受領記録する。
   大きさが正というだけでDNG decode有効とは扱わない。片方の成功は他方の失敗でも保持する。
3. 台帳の整合を確認して撮影busyを解除する。DNG共有保存や現像完了をbusy解除の前提にしない。
   DNG保全とpreview復帰の表示を、DNG共有保存成功・JPEG公開成功と区別する。
4. 共有DNGは元バイト列のコピーとする。共有commitが失敗しても内部原本と台帳を保つ。
   初期段階では共有成功後も内部DNG/仮JPEGを自動削除しない。容量コストは明示し、保持期限は別判断。
5. readyな原本参照を台帳から列挙し、1件ずつ処理する。画像バッファをqueueへ積まず、未処理件数が増えても
   既存原本を削除しない。容量不足時は新撮影を安全に失敗表示し、成功済み素材を保つ。
6. 完全な現像JPEGを内部出力へ作り、decode/寸法/向き/色定義/byte数を検査してから公開へ渡す。
   中断・失敗は派生一時ファイルだけを破棄可能とし、原本/仮JPEG/完成JPEGをcleanup対象に含めない。

### 台帳とprocess death

1撮影内の受領・現像・公開は別状態。schemaVersion、captureId、期待形式、形式別完了、相対参照、
renderGeneration、固定recipe、attemptId、出力検査結果、公開操作ID/行URI/状態を持つ。
台帳の更新は単一writerで行う。AtomicFileは複数writerの排他やMediaStoreまでのtransactionを提供しない。
pathは専用root内に解決できる相対参照だけを受理し、壊れた/未知schemaは保全して要確認へ移す。

再起動時の`CAPTURING`は`INTERRUPTED`へ。成功callbackと台帳commit間のファイルは未確認として残し、
サイズだけで完了へ昇格させない。`RENDERING`はattemptを無効化して保留へ戻し、完成出力は検査記録と照合する。
`PUBLISHING`は[契約の照合手順](contracts.md)へ進む。背景化・process deathでも新しい自動撮影はしない。

## 撮影優先と自動現像

- 開始条件をすべてANDにする: foreground、camera session未使用、capture/record/finalize/rebindなし、
  thermal許容、空き容量余裕、renderer使用可能かつ当該解像度のメモリ予算確認済み、実行中attemptなし。
- 不明な能力/熱/メモリは抑止。撮影予約と開始判定は同じ直列coordinatorで扱い、古いsnapshotで開始しない。
- 撮影操作で新規開始を即座に禁止し、実行中attemptへcancelを通知する。完了後も世代/IDを再確認する。
  CameraXのexecutor、UI thread、撮影ファイルwriterを現像に共有せず、原本保存IOを優先する。
- cancelは協調的。非協調decodeをFuture.cancelだけで停止済みとみなさない。cameraを開く操作から残る
  CPU/IO競合は実測対象。許容できなければ自動開始条件を狭めるか、分離方式を別判断し、実機有効化を止める。
- 背景化では開始禁止/協調停止し、次のforegroundで台帳を再走査。OS kill中の完了や即時の背景現像は保証しない。
- 初期PR群はカメラへ未接続、rendererも未登録とし、製品の自動現像は有効化しない。

## 公式資料と採用判断

- [MediaStore](https://developer.android.com/training/data-storage/shared/media): 自分の新規画像とpending公開を使う。
  アプリの台帳と共有保存は別commitであり、これを一括rollbackで解決した扱いにしない。
- [アプリ専用領域](https://developer.android.com/training/data-storage/app-specific): 仮JPEGを永続内部領域へ。
  cacheの消去リスクを避ける。アンインストール/データ消去を越える原本は共有DNGで保持する。
- [AtomicFile](https://developer.android.com/reference/android/util/AtomicFile): 小さい単位の台帳commit候補。
  Roomを先行導入せず1台帳1writerから始め、検索/transaction要件が増えた時点で再判断する。

WorkManagerは今回採用しない。永続要求と画素処理の停止能力は別問題であり、撮影優先は起動schedulerだけでは保証できない。

## PR分割と検証

1. **設計PR**: 本spec/plan/contracts/tasks/検証案と原則IV・002の方針更新。アプリ変更なし。
2. **policy PR**: `rawcapture/AutoDevelopPolicy.kt`と対応JUnitだけ。開始条件と未知値、撮影予約race、再開を合成検証。撮影へ未接続。
3. **保全PR**: `rawcapture/CaptureJournal.kt`/`RawCaptureStore.kt`と故障テスト。CameraProbeへ未接続。
4. **公開PR**: `rawcapture/RecoverablePhotoPublisher.kt`と故障テスト。新規の1行ずつの照合のみ。既存publisherを置換しない。
5. **撮影接続PR**: 新RAW撮影だけを切替え、形式別callback/timeout/epoch/preview復帰/原本保全を検証。
6. **#78統合PR**: 成立したrendererをadapterで接続し、非公開実DNGと性能測定後に自動現像を有効化する。

全段階で最終headの必須CI、canonical `@codex review`、独立明示OK、thread0を確認し、mergeは別承認。
詳細な故障点は[quickstart.md](quickstart.md)。最初の設計に画素処理や試験用の大規模基盤を入れない。
