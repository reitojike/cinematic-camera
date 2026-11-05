# 設計

- `photoedit/PhotoEditScale`は有限のmin/max/tapStepを受け取り、連続座標変換と現在値からの1段変更を担当する。値域外/非有限入力は拒否、明示の端点操作はclampする。stepを表示丸めやドラッグ量へ流用しない。
- `PhotoEditBarGesture`はpointer座標・slop・down時thumbに対するtap方向だけを保持する。draft/絶対targetは保持しない。dragは前pointerからのfraction delta、tap/keyはStep、読み上げの明示位置指定はSetValueとして、単一dispatcherが最新owner値へ適用する。標準touchSlopを越えた最初のdragはdownからの全移動を通知し、以後は新しい差分だけを通知する。無移動upは入力なし。端点の外側でも前座標を更新して超過分を捨てる。pointer cancelは既反映値を戻さず、明示Session取消/resetだけが復帰を担う。
- 標準Material Sliderのtrack tapは押下位置へのseekとなりFR-002に合わないため、この入力範囲だけCompose標準pointer gesture APIとCanvasを使う。48dpのバー、Material色/文字、progress semantics・明示の上下1段custom action・左右キーを備える。新依存/独自native inputは導入しない。
- Composeは値の所有者にならず、表示値と操作時の値を分ける。必須の`valueNow`は親が所有する最新draftを直接読み、callbackはownerの現在Sessionへcaptured `PhotoEditInputToken`で同期適用する。表示値を捕捉したgetterや古いSession snapshotの書戻しをしない。drag開始・相対移動・step・同値抑止は最新owner値を使い、再compose前の往復/連打・別入力との交差でも受理済みの変更を戻さない。写真/session/reset/退出を含むinteraction keyでgestureを失効させる。処理待ちを理由に有効な次の入力を破棄する実装はここに入れない。
- `PhotoEditSession`はimmutableな確定値・draft・defaults、process内owner/epoch/revisionを持つ。`SharedOriginalReference`は識別用の値として保持し、read権限や原本不変性を証明したとは扱わない。IO/ジョブ投入/保存は持たない。
- `previewTicket`はowner/epoch/revision/値が一致したときだけ採用できる。これは将来のUI表示整合用であり、artifact検証やqueue世代の代わりではない。renderer・永続編集定義のschema/処理順/recipe意味は担当側の契約成立後に接続する。
- `rawcapture`の台帳・公開owner、`jpegrender`と`raw`、既存`photoroll`/撮影画面を編集しない。#144のencoded snapshot、#143の原寸probeとはファイルを分離する。

局所unitとコンパイル/lintを優先し、重いnative buildや新しいエミュレータ実行を追加しない。PR時の必須CIがローカル省略分を実行する。未実行のAndroid gesture/a11y配信・物理端末・画質は未検証として残す。
