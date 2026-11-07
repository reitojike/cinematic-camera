# Implementation Plan: 写真別の調整保持

## Technical Context / Constitution Check
Kotlin、既存AtomicJournalStorageとAndroid AtomicFileを再利用。新規依存・権限・外部送信なし。原本・JPEGの所有者は不変。#81の既存Issueを使う。実機合格にはしない。

## Research and Data Model
AtomicFileは原子的置換を提供するがwriter排他を提供しないため、全store instance共通の単一IOキューを使い、close受理時に同期的に受付する。Activity再生成を跨いだ古い保存の後勝ちを防ぐ。既存AndroidAtomicJournalStorageの境界・symlink検査とreadbackを再利用する。DataStore導入や独自ファイル置換より小さい変更になる。
復帰する画面値にも原本identityと処理contractを保存し、再生成後も再照合する。原本URI・bytes・SHA256・grant、3値、ManualOriginalTrialRender.recipeのschema/payloadを保持する。既存identityをキーに、noBackupFilesDirの別rootとEDIT_RECIPEファイルを使いcapture/公開台帳へ混入させない。4KiB上限。未知版・不正値・余剰bytesは拒否。

## Integration
初回選択の原本検証後に一度だけrestoreする。rememberSaveableの復帰済み3値を優先し、選び直しでrestore可能状態へ戻す。復元値が異なる場合はticketを更新し、古い要求を中断して新しい要求として描画する。
調整のclose要求は受理可能な表示値を固定し、NonCancellable IOで小さいmetadataのみ保存・照合する。保存中は操作を抑止し、現在のticketが一致する場合だけcloseの結果を採用する。背景化によるticket失効時は古いUIを更新しない。

## Validation / Quickstart
codec/store JVM試験、editor復元/close契約、lintDebug/testDebugUnitTest/assembleDebug、compileDebugAndroidTestKotlin、diff check。合成Androidのstore再生成試験を用意。実機では12MP撮影→調整→閉じる→ロール/再起動→再選択→同値表示→JPEG保存を確認する。画質・時間・メモリ・熱は#78の実機項目。

## References
- https://developer.android.com/reference/android/util/AtomicFile
- https://developer.android.com/reference/android/content/Context#getNoBackupFilesDir()
- https://developer.android.com/develop/ui/compose/state-saving
