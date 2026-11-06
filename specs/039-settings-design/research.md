# Research: Aの設計判断

- Decision: 共有トークンを抽出し、既存Compose構成を継続する。
  Rationale: CaptureScreenとCapturePanelsで色が重複。PhotoRollActivityは既にCaptureTheme適用済み。
  Alternatives: 画面ごとの色複製は統一維持に不利。外部UI依存は指示範囲外。
- Decision: 504dpを可用高で制限するoverlay。
  Rationale: CaptureLayoutはpreviewHeightとsheetHeightを分離しており画角不変を保てる。
  Alternatives: 344dp維持は要確認1の代案。プレビュー縮小はAC違反。
- Decision: 標準Dialogの内容を角なしSurfaceにする。
  Rationale: AlertDialogの外観を変えつつ標準のBack/フォーカス管理を利用する。
  Alternatives: 独自window/入力処理は不要。
- Decision: 再接続の変更前値は画面の一時表示snapshotとし、CameraProbeの制御状態を二重所有しない。
  Rationale: selectFormatはformatを更新してからbindする。要求値を受付値に見せない。
  Alternatives: busyだけで判定すると他処理と混同する。原本や永続設定へ表示履歴を追加しない。

根拠: main57bc2cfのCaptureScreen/CapturePanels/CaptureLayout/CameraProbe、引継ぎINSTRUCTIONS/README/AppScreensと代表PNG。外部APIや新技術の採用はなく、調査は既存コード内で完了。

PNGの被写体・数値・ログは例であり実機証拠ではない。IBM Plexのブラウザ描画とAndroidでは字幅/行高が違い得る。PNGから測寸せずINSTRUCTIONSを正とする。横持ちPNGは縦配置固定＋文字回転を再現して枠ごと90度傾いたものであり、Androidの横持ち検証を代替しない。
