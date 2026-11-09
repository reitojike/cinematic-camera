# カメラロールの設計と合成検証

現在の利用者向け契約は [Living Spec](../specs/library/spec.md)。ここでは、同じ契約を保つための実装境界と再実行する合成確認を扱う。過去の作業状態・試験結果は [Issue #251](https://github.com/reitojike/cinematic-camera/issues/251) と各PRへ記録する。

## 問い合わせとページング

[PhotoRollRepository](../app/src/main/java/dev/cinematic/p0/photoroll/PhotoRollRepository.kt) は MediaStore の画像・動画collectionを `VOLUME_EXTERNAL` で読み、OSが許可した項目だけを対象にする。
[CameraRollMedia](../app/src/main/java/dev/cinematic/p0/photoroll/CameraRollMedia.kt) が種類判定・SQL条件・共通順序を定義する。

- pending / trash除外、種類、volume / relative folder、共通keyset境界をSQLへ入れてから、各collectionを `pageSize + 1` 件に制限する。値はバインドし、フォルダ名をSQL文字列へ連結しない。
- 結果を完全URIで重複排除し、日付NULLを最後、追加日時降順、ID降順、volume昇順、同値なら画像→動画で統合する。共通cursorで次ページへ進み、collection別offsetは進めない。
- 既定ページサイズ60、許容範囲1〜200。追加読込では既存一覧と完全URIで統合し、初回のフォルダ一覧を引き継ぐ。動的なMediaStoreの固定snapshotではない。
- フォルダは全アクセス範囲から別のmetadata queryで列挙する。現在対応するAPIでは標準のgroup columnsを使い、画像bytesを読み込まない。フォルダキーはvolumeと相対パスの組で、同名でもvolumeが違えば別とする。
- 旧API29〜32分岐がコードに残っていても、現在の最低API33より前の製品対応を示すものではない。

## 状態・権限・表示の所有

[PhotoRollScreen](../app/src/main/java/dev/cinematic/p0/photoroll/PhotoRollScreen.kt) が一覧、フィルタ、フォルダ、cursor、アクセス世代と、permission / picker / viewer pending、foreground、取消ownerを持つ。
[CameraRollBrowser](../app/src/main/java/dev/cinematic/p0/photoroll/CameraRollBrowser.kt) は渡された状態を表示し、既存callbackへ委譲する。表示統一のために別のViewModelやrepositoryを増設しない。

- `ActivityResultContracts.RequestMultiplePermissions` の結果と復帰時にアクセス世代を更新し、[CameraRollAccess](../app/src/main/java/dev/cinematic/p0/photoroll/CameraRollAccess.kt) で最新grantを読み直す。永続grantの新規取得は行わない。
- 一覧の成功・失敗は [CameraRollListingWork](../app/src/main/java/dev/cinematic/p0/photoroll/CameraRollListingWork.kt) のticket、query、アクセス世代、画面有効性、取消状態がすべて現在と合う場合だけ採用する。古い失敗も新しい一覧を消してはならない。
- [CameraRollRefresh](../app/src/main/java/dev/cinematic/p0/photoroll/CameraRollRefresh.kt) は同じ検索条件・同じアクセス世代のタイルだけを更新中に保持する。背景化で一覧・フォルダ・cursorを破棄し、条件変更後に別集合のタイルを現在の結果として見せない。
- `CameraRollAccess.images && videos` なら帯を省略し、それ以外で `selected` があれば部分アクセスの意匠にする。画像/動画の混合状態は `access.description` を保持し、選択のみの場合だけ短い説明にする。UIから `supported` や各操作の有効条件を上書きしない。
- [CameraRollDateGroups](../app/src/main/java/dev/cinematic/p0/photoroll/CameraRollDateGroups.kt) は追加日時だけからローカル日付groupを導出し、原本URI・順序を変更しない。件数は取得済み範囲。日付不明や品質制限をモックの値で埋めない。
- 見出し・種類/フォルダ・フッターはBrowser、日付・セル・アクセス説明用headerは [CameraRollGrid](../app/src/main/java/dev/cinematic/p0/photoroll/CameraRollGrid.kt) が配置する。狭い高さ/拡大文字では既存の `captureNeedsFullScroll` を使い操作帯もスクロールできる。旧planの「制御部をすべてgrid headerへ」は現行配置ではない。

## サムネイルと選択先

- 可視セルに対して `ContentResolver.loadThumbnail(Size(256, 256), CancellationSignal)` をIO上で呼び、process共通の `Semaphore(2)` で画面再生成をまたいでも最大2件にする。セル離脱・背景化でsignalを取り消す。
- DNGだけ [CameraRollDngThumbnail](../app/src/main/java/dev/cinematic/p0/photoroll/CameraRollDngThumbnail.kt) の限定Orientation補正へ渡す。64MiB以下のmetadata読取り、90/270度と寸法/比率判定、取消・解放の境界はLiving Specに従う。RAW展開や原寸Bitmapは作らない。
- 写真/動画/他RAWの `viewIntent` は同じURIのcollection・volume・公開状態・MIME/種別を再確認し、read-only FDを開けることを確認する。`ACTION_VIEW` には同じURIのClipDataと `FLAG_GRANT_READ_URI_PERMISSION` だけを渡す。
- IOからMainへ戻っても、最新foreground・取消と同期的な退出状態を `CameraRollNavigation` で再確認してからビューアを開く。Backの受理からlifecycle通知までの間も、退出後の起動を許さない。
- DNGは別の `check` 経路へ渡し、公開済みprimary volumeの原本を全バイト識別する。owner packageが一致する場合だけAPP_OWNED、他アプリ/Filesの手動選択はUSER_SELECTED。URIを恒久的なgrantとは扱わない。
- 原本検証から同一coordinator枠・単一claimの既存現像へ渡す。ロールのrepositoryはwriteを持たず、一覧のためのMediaStore insert/deleteやDNGの取り込み・ミラー保存は行わない。

標準APIの根拠: [共有メディアへのアクセス](https://developer.android.com/training/data-storage/shared/media)、[選択的な写真・動画アクセス](https://developer.android.com/about/versions/14/changes/partial-photo-video-access)。

## 合成検証の入口

通常の `lintDebug` / `testDebugUnitTest` / `assembleDebug` / `assembleDebugAndroidTest` と `git diff --check` を変更に応じて実行する。必須CIは既存の [Android checks](../.github/workflows/android.yml) に従い、文書移行で免除しない。

- JVM: `CameraRollMediaTest`（分類・SQL・共通cursor・read-only navigation）、`CameraRollAccessTest`、`CameraRollDateGroupsTest`、`CameraRollRefreshTest`、`CameraRollListingWorkTest`、`CameraRollDngThumbnailTest`。
- 合成画面: [CameraRollBrowserAndroidTest](../app/src/androidTest/java/dev/cinematic/p0/photoroll/CameraRollBrowserAndroidTest.kt) は全面/部分/混合/未許可/非対応、空状態2操作と無効時0回、読込中の空表示抑止・既存タイル保持を合成fixtureで確認する。412×924dp、320×480dp、924×412dp、文字倍率1.3で操作とフッターへの到達を確認する。
- `CameraRollRepositoryAndroidTest` / `CameraRollPermissionsAndroidTest` はprovider・権限側の別試験。合成UIのcallback検証で代替せず、前提と対象端末を読んで個別に選ぶ。一覧/権限/ビューア試験をATD向けUI試験と一括実行しない。

合成画面だけを再実行する場合は、既存の [共通GMD](MANAGED_DEVICES.md) に従い枠の割当、SDK/JDK、共有cache、枠専用AVD保存先を先に確認する。新しい手動AVDやタスク専用AVDは作らない。PowerShellでリポジトリrootから実行する:

```powershell
.\gradlew.bat :app:pixel9aapi37atdDebugAndroidTest --rerun --no-daemon --max-workers=1 `
  "-Pandroid.testInstrumentationRunnerArguments.class=dev.cinematic.p0.photoroll.CameraRollBrowserAndroidTest" `
  "-Pandroid.testInstrumentationRunnerArguments.isolated=true"
```

`--max-workers=1` は別Gradleプロセス同士を排他しない。枠と同時実行の条件は共通GMD手順が正本であり、この文書で変更しない。

この試験はActivity再生成後にも合成fixtureを入れ直し、Viewツリーをソフトウェア描画する。権限/Files/設定callbackを実操作に接続せず、実写真・RAW現像・共有保存・外部ビューアを検証しない。実際の表示合成・GPU経路・実権限ダイアログ・実機速度/操作感の証拠にはならない。`isolated=true` を実機の安全確認を省くために使わない。

実機では、実際の全面/部分/混合/未許可、復帰後の失効、標準ビューアとFiles、撮影への復帰、縦横と文字拡大、同一集合の更新中表示を対象・条件ごとに確認する。既存の確認済み項目を無条件に再確認対象へ戻さず、必要な残件と結果はIssue/PRを参照する。
