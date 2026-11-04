# 内部契約: 保存・現像・公開

## 形式別の保全

| 入力 | 保全する原本 | 内部JPEG | 共有公開 |
|---|---|---|---|
| RAW+JPEG | 受領したDNG | 受領した撮影JPEG | DNGを原本保存、成功した現像JPEGを後から別保存 |
| RAWのみ | 受領したDNG | なし。欠落を失敗としない | DNGと、現像に成功したJPEG |
| JPEG単独 | 既存経路のJPEG | この契約の仮JPEGにはしない | 現行のJPEG保存を維持 |
| RAW+JPEGの部分成功 | 完了した形式だけ | 完了していれば保全 | DNG成功時のみ原本保存可。JPEGだけなら自動RAW現像は不可 |

受領成功はCameraXの形式別callbackとファイルclose/検査で記録する。保存成功とRAW decode有効性は別。
古いcallbackは撮影UI状態を変更しないが、その撮影記録に所有されるファイルを削除する理由にはしない。

## #78との受け渡し（型はまだ作らない）

- 入力: `captureId`、`renderGeneration`、`attemptId`、読み取り専用DNG参照、固定recipe、独立した派生出力先、cancel確認口。
- recipe: schema/renderer/baselineの識別版、`stage-clear-v1`、明示strength（初期0.5）、外部決定の露出定義、
  顔入力の有無/定義、出力の寸法/向き/色空間/transfer/JPEG設定。#79未接続の顔/露出を推測で埋めない。
- 出力: 成功時だけ完全なJPEG参照、検査可能な寸法/向き/色定義と処理識別。失敗・未対応・中断は区別する。
- rendererは原本/仮JPEG/MediaStore/撮影状態/台帳を変更しない。保存側は画素処理順やgoldenを変更しない。
- 読み取り参照は処理中保持し、rendererが参照を解放するまで削除・上書きしない。cancel要求と解放完了は別。
- レシピ変更は新generation。旧attemptの結果は新generationへ採用せず、その未公開の派生出力だけを片付ける。
- 既定値の将来変更で既存要求を変えない。同一環境・同一版の再生成を検証し、異なるCPU/GPU間のbit一致を先に約束しない。

## 1公開操作の状態と照合

DNG原本保存と現像JPEG公開はそれぞれ独立した操作IDを持ち、1操作につき1行。
不変のDNGは`captureId + ORIGINAL`、現像JPEGは`captureId + RENDERED + renderGeneration`で識別する。
recipeの変更でDNGの公開操作を増やさない。操作ID、ランダムtokenを含む期待名/volume/path/MIME、
入力サイズ/検査情報を**insert前**に台帳へcommitする。通常の再試行では同じ操作IDを使う。

| 台帳状態 | 動作 | 復旧/失敗時 |
|---|---|---|
| `PREPARED` | まだinsert未発行。`INSERTING`を台帳commitしてから1回だけinsert | `PREPARED`への復旧では未発行が確定。commit失敗ならAPIを呼ばない |
| `INSERTING` | insert結果のURIを台帳へ記録 | 応答前のdeathは期待名等で照合。候補なし/複数/照合不能は`UNKNOWN`。自動再insertしない |
| `ROW_RECORDED` | URIをcommitしてから、そのpending行へコピー/close/検査 | 完成した内部sourceを保持。再開時は同じURIを照合 |
| `BYTES_COMPLETE` | 検査結果をcommit後、`PUBLISHING`を先にcommitしてからpendingを解除 | commit失敗なら公開APIを呼ばない |
| `PUBLISHING` | 当該URIの状態/所有/サイズ/内容識別を確認 | pending=0で一致なら`PUBLISHED`、pending=1で完成内容一致なら同じ行のpending解除を再試行、その他は`UNKNOWN` |
| `PUBLISHED` | 完了記録のみ | 行が後で消えても自動再作成しない。公開済み行をrollback削除しない |
| `UNKNOWN` | 自動操作を停止し、保全と要確認を示す | 元データを保持し、照合できるまで新規insert/削除/上書きをしない |

期待名だけで行の所有を証明しない。volume/path/MIME/アプリ所有/token/完成内容を照合し、
外部の行や他撮影を巻き込まない。URI記録前のinsert成功とdeathをまたぐ窓も故障注入で確認する。
復旧ではすべての非terminal状態で、書込み前にpending/所有を再照合する。未完成の自分のpending行だけが
同じsourceからの再コピー対象。完成内容の照合は公開採用の条件であり、途中の行を完成扱いにしない。
`INSERTING`で1件の自分のpending候補を確認できればURIを記録して`ROW_RECORDED`へ進める。
照合APIが必要な情報を得られない場合、広範囲権限を追加せず`UNKNOWN`へ止める。
公開済みかもしれない行が見つからない場合も、自動で新しい行を作って補わない。
ユーザーが再出力を選ぶ導線は後続編集/出力の責務で、内部の無限retryにしない。

DBとMediaStoreを跨ぐexactly-once保証は主張しない。不明時に自動停止することで、重複公開を避ける。
失敗した自分の未公開pending行をcleanupする場合も、所有とpendingを再確認し、台帳へ結果を残す。
原本・仮JPEGはこのcleanupへ渡さない。
