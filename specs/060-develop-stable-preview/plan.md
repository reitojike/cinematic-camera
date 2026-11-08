# 実装計画

## 技術・憲章確認
Kotlin / Jetpack Compose既存APIのみ。Boxで重ね、AlertDialogで情報を表示する。依存追加・RAW工程・保存条件の変更なし。原本非破壊、写真を公開しない、実機未確認を維持。#225入りmainを基準とする。

## 設計
- PhotoRollScreen: 同じ写真の調整更新のときだけ表示Bitmapを継続する。保存ownerは従来どおり破棄。render入口で保持可能性を確認し、例外・Unavailableでは画像を消す。旧要求の失敗は現在のBitmapを消さない。
- PhotoRollDevelopView: 画像viewportを固定。情報はAlertDialog。状態・通知・ForegroundSaveNoticeは画像上へ重ねる。キーの表示中値には常に同じ高さを確保する。
- PhotoRollComparisonImage: 標準ScrollStateとImageで幅基準の縦横比を保ち、縦に収まらない画像はviewport内だけスクロール。比較時の寸法とscroll位置を共通化。既存呼出しへ影響しないオプションとする。
- 表示保持判定は小さなpure関数として単体テストする。保持は描画のためだけであり、編集値の確定・JPEG保存の準備完了ではない。

## 検証
単体テスト、合成UIの領域寸法・表示保持・失敗・縦横/大文字/比較、lint/unit/assemble、CI、canonical/独立レビュー。ATDのsoftware描画は実機GPU経路の証拠にしない。

## 範囲と依存
ロール担当のCameraRollGrid、timing/CPU並列化のRAWファイルは変更しない。実機や個人写真は使わない。大幅な保存契約変更が必要なら停止。マージは本人の承認待ち。
