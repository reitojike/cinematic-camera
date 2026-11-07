# Orton 保護重み0の診断候補
Status: Implemented (既定オフ、実機採否未確認)
関連: #209。#204の運用と#78の既存probeを引き継ぐ。

## User Scenarios & Testing
診断担当が合成入力について候補off/onを比較し、全工程のFloat raw-bit一致と取消・回収を確認できる。

## Requirements
- 重みが厳密に0の画素だけbefore RGB→OKLabとchroma計算を省く。after RGB→OKLab→RGBは保持する。
- 元の演算順と非zero保護を維持する。既定falseで、通常保存factoryとプレビューへ有効接続しない。
- 原本・golden・依存・署名・Releaseを変更しない。実機と個人写真を使用しない。
- 不一致またはgolden更新の必要が出たら停止し条件と差をIssueへ記録する。

## Success Criteria
合成の全工程raw-bit一致、取消/失敗/回収の確認、CIとレビューまで。製品での採否は実機確認後。mergeはユーザーが承認した範囲と最終headのCI・レビューに従う。
