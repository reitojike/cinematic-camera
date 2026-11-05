# 実装方針

MediaStoreの画像/動画collectionをVOLUME_EXTERNALで読み、OSが許可した項目だけを対象にする。
SQLにpending/trash除外・種類・volume/relative folder条件と共通keyset境界を入れる。
2collectionを同じdate DESC/id DESC/volume ASC/画像→動画tie順でmergeする。
各collectionをpageSize+1件へ制限し、共通nextCursorで続きを取得する。片方だけoffsetを進めて項目を欠落させない。
初期60件、追加ページも60件。動的ライブラリの固定snapshotとは扱わず、更新・復帰時は最初から読み直す。
フォルダは別のmetadata queryで全アクセス範囲から列挙し、表示済みページの項目だけから推定しない。
API30以降は標準group columns、API29は取消可能な2列metadata cursorを使う。画像byteの全読込はしない。

CameraRollGridは可視セルだけを構成し、256pxの標準loadThumbnail要求をIO上・process全体最大2並列で行う。
セル離脱/背景化でCancellationSignalを取り消す。画像/動画/RAWのIDは完全URIで区別する。
制御部もgridのheaderとしてスクロールし、小画面・大きい文字で一覧の高さを奪わない。

アクセス要求は明示ボタンからActivityResultContracts.RequestMultiplePermissionsで行う。
OSの最新grantを復帰/結果callbackで再取得し、部分許可・画像/動画別・拒否を説明する。
一覧/フォルダ/cursor/thumbnailは背景化で破棄し、許可変更後に古い項目を開かない。
拒否や全面許可後の変更にはAndroidの当該アプリ設定を開く入口も用意する。

外部表示前に同じURIの公開状態/MIME/read-only FDを再確認する。Mainへ戻った最新foregroundと同期exit権威を確認し、
ACTION_VIEWへread grantと同じURIのClipDataだけを渡す。DNGはprimary volumeの公開原本を全バイト照合する。
ownerが自appと一致した場合だけAPP_OWNED、他app/Filesの手動選択はUSER_SELECTEDとし、URI自体を恒久grantとは扱わない。
RAW分類は候補分類でありDNG以外の現像機能を追加しない。SDの直接現像・インポートは後続範囲とする。

MediaStore insert/deleteや原本writeは製品コードへ追加しない。合成試験のcleanupは作成したURIだけに限定する。
比較はspec025の同一coordinator枠と単一claimを利用し、私有JPEGを公開一覧へ混入させない。
根拠: [共有メディアへのアクセス](https://developer.android.com/training/data-storage/shared/media)、[選択的な写真・動画アクセス](https://developer.android.com/about/versions/14/changes/partial-photo-video-access)。
