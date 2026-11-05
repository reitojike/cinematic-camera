# 設計

- `photoedit/PhotoEditScale`は有限のmin/max/tapStepを受け取り、連続座標変換と現在値からの1段変更を担当する。値域外/非有限入力は拒否、明示の端点操作はclampする。stepを表示丸めやドラッグ量へ流用しない。
- `PhotoEditBarGesture`は1pointerのdown値をanchorにした相対水平drag。バーのどこで開始しても初期値へjumpせず、track全幅の移動を全値域の変化に対応させる。標準touchSlopでtap/drag/縦scrollを分ける。
- 標準Material Sliderのtrack tapは押下位置へのseekとなりFR-002に合わないため、この入力範囲だけCompose標準pointer gesture APIとCanvasを使う。48dpのバー、Material色/文字、progress semantics・明示の上下1段custom action・左右キーを備える。新依存/独自native inputは導入しない。
- Composeは値の所有者にならず、表示値と操作時の値を分ける。必須の`valueNow`は親が所有する最新draftを直接読み、callbackはそのownerを同期更新する。表示引数を捕捉したgetterを渡さない。drag開始・step・同値抑止はこのgetterを使い、再compose前の往復/連打でも最後の値を落とさない。写真/session/reset/退出を含むinteraction keyでgestureを失効させ、親は`PhotoEditInputToken`でも検査する。処理待ちを理由に有効な次の入力を破棄する実装はここに入れない。
- `PhotoEditSession`はimmutableな確定値・draft・defaults、process内owner/epoch/revisionを持つ。`SharedOriginalReference`は識別用の値として保持し、read権限や原本不変性を証明したとは扱わない。IO/ジョブ投入/保存は持たない。
- `previewTicket`はowner/epoch/revision/値が一致したときだけ採用できる。これは将来のUI表示整合用であり、artifact検証やqueue世代の代わりではない。renderer・永続編集定義のschema/処理順/recipe意味は担当側の契約成立後に接続する。
- `rawcapture`の台帳・公開owner、`jpegrender`と`raw`、既存`photoroll`/撮影画面を編集しない。#144のencoded snapshot、#143の原寸probeとはファイルを分離する。

局所unitとコンパイル/lintを優先し、重いnative buildや新しいエミュレータ実行を追加しない。PR時の必須CIがローカル省略分を実行する。未実行のAndroid gesture/a11y配信・物理端末・画質は未検証として残す。
