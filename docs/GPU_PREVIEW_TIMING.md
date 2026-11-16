# GPU プレビューの工程記録（#233 / v0.1.x 提案）

保存の記録とは別に、固定 schema の匿名 TSV をアプリ私有 no-backup 領域へ上限付きで残す。
FULL AHD・画素処理・保存・原本照合・取消・所有権の判断は変更しない。

## 区間

開始は `GpuPhotoRollEditor` の新しい非 null session に対する `LaunchedEffect` 内。
一覧の物理タップ、Activity 遷移、Compose の session 作成前は含まない。
同じ区間で前後を比べるための値で、物理 touch-to-photon ではない。

| 工程 | 境界 |
|---|---|
| OPEN | 上記開始から最初の通常画像の swap 成功、または取消/失敗の観測まで |
| CHECK_ORIGINAL | repository.check |
| PREFLIGHT | preflight 用 snapshot・native inspect・再照合・解放を含む owner.inspect |
| SNAPSHOT | 本来プレビュー用の照合付き snapshot コピー |
| GUIDE | 仮画像の探索/作成の同期フック全体。表示の完了時刻ではない |
| NATIVE_DECODE | 本来画像の native 呼出し。unpack/AHD・RGB16 ファイルへの書込みを含む |
| BOX_REDUCE | RGB16 の行読取りと BOX 縮小、bounded RGB の取得 |
| VERIFY_AFTER | snapshot と原本の事後照合 |
| GPU_FIRST_FRAME | submit/openPhoto の直前から、通常画像の最初の swapBuffers 成功後の onPresented まで。GL キュー待ち・入力 upload/解析・描画・swap を含む |

これらは総時間の排他的な全分解ではない。admission/Surface 待ち、補助の照合・解放、UI dispatch などの隙間は OPEN に含む。
GPU_FIRST_FRAME は GPU 演算単独でも OS の物理提示完了でもない。長押しの基準表示は終端にしない。
仮 half guide の展開を本来画像の NATIVE_DECODE に混ぜず、GUIDE の内側として扱う。

取消・離脱時は取消が観測された時点までを記録する。native worker/資源の実解放完了時刻ではない。
処理中だった工程は INCOMPLETE、開始していない工程は NOT_REACHED。途中結果を PRESENTED としない。
OPEN が PRESENTED でないレコードは、初表示成功の時間比較に混ぜない。
保存中の再入場など、画像を出さず既存セッションを閉じた回も成功とはしない。

## 保存・読取り

- 固定フィールド: schema=1、ランダムな試行 ID、versionCode、kind=GPU_OPEN、工程、結果、原本画素数（未判明ならNA）、ns（欠測ならNA）。
- 写真名、URI、原本 fingerprint、顔、端末 ID、時刻、自由文エラーは含めない。試行 ID は写真の識別子ではない。
- collector は工程数の固定上限。終端で1回だけ batch を送る。通常の stage ではディスク書込みをしない。
- 専用 worker は1本、待機 batch は最大4。満杯なら診断だけを破棄する。UI/GL でファイル I/O を行わない。
- ストアは512行・256KiB上限、atomic replace。壊れた行・途中の行・他 schema は読取りから除外する。
- 読取り provider は android.permission.DUMP と calling UID=2000 の両方を要求。固定の records だけ read-only で公開し、一般アプリや書込みを拒否する。
- 実機での抽出は都度承認後。APK version、測定区間、入力/設定/端末状態を別の実施記録で揃える。
- 記録の確保・時計・保存・抽出失敗は、表示や保存の判定、既存の例外、取消、cleanup を変えない。

## 検証

unit: 区間計算、一度だけ終端、途中取消、時計/sink failure、元例外保持、schema/範囲、破損/容量上限、既存ファイル保護。
Android: shell限定読取り、保存ストアとの分離、原本/画素不変と input stage 記録。
製品GPU初描画・lifecycle/取消・既存GPU golden/帯/保存安全回帰は別に実行し、未実施を完了にしない。
## 許可後の実機 A/B 手順

1. 計測入り版の公開・インストールと adb 抽出は、それぞれ本人の承認後に行う。
2. 同じ写真、調整値、GPU owner の冷温条件、キャッシュ条件で比較する。現段階の製品入力は FULL のままで、HALF への採否はこの変更では行わない。
3. 画像が出た後に、対象パッケージの `content://<applicationId>.gpu-preview-timing/records` を adb の `content read --uri` で読む。保存の記録は従来の `.save-timing/records` のまま。抽出は読み取りのみ。
4. OPEN=PRESENTED の試行だけで展開・縮小・初描画の区間を比較する。NA、INCOMPLETE、NOT_REACHED、途中取消/失敗はゼロ扱いせず別集計。終了後の非同期記録なので直後の抽出では少し待って再読する。
5. 書き込みは診断のため失敗・過負荷時に捨てられる。アプリ終了・OS kill 時の確実な記録を保証せず、記録のない試行を成功にも失敗にも推定しない。ユーザーの写真・URI・未加工ログは公開しない。
