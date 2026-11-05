# 色温度の数値表の出典と通知

Issue #137 の AsShotWhiteXY 対応で使う Robertson (1968) の 31 行の数値表は、
Colour v0.4.6 の固定 commit
`0c6f868c4b5c5ae861f868ba8015be3abf0f92f0` にある
[`DATA_ISOTEMPERATURE_LINES_ROBERTSON1968`](https://github.com/colour-science/colour/blob/0c6f868c4b5c5ae861f868ba8015be3abf0f92f0/colour/temperature/robertson1968.py#L67-L99)
を出典とする。元ファイルは Copyright 2013 Colour Developers、BSD-3-Clause と明示する。
数値は変更せず、325 mired の `u = 0.24792` も保持する。
アプリ側の制御・範囲検査は本アプリの実装で、Colour の関数・import・NumPy や
Adobe DNG SDK のソース・関数・バイナリはこの取り込みに含めない。

- [LICENSE](../../app/src/main/assets/third_party/dng_temperature/LICENSE) は upstream の全文・原本 byte を保持する。
  ソース配布と APK 配布の両方で著作権・条件・免責を残し、名前を無断で推奨・宣伝に使わない。
- [SOURCE.txt](../../app/src/main/assets/third_party/dng_temperature/SOURCE.txt) に固定 tag/commit、
  ソース・ライセンス・表ブロックの SHA-256、行範囲、科学的参照と訂正の帰属を記録する。
  元モジュールにある Adobe SDK の関数側の参照も、表の出典とは区別して記す。
- この 2 ファイルは `app/src/main/assets/third_party/dng_temperature/` に置き、APK に同梱する。
  実装側の表には Colour の copyright と本通知への参照を残す。
- LibRaw の CDDL 選択、ソース提供、既存の 6 通知 asset は変更しない。
  この追加は Adobe DNG SDK の採用でも、法的な適合性の認定でもない。

再確認時は固定 commit の元ファイルの hash と 31 行の数値を照合し、staged Git blob と
実 APK の LICENSE の SHA-256 が SOURCE.txt 記載の値に一致すること、SOURCE.txt が
ソースと APK の両方に含まれることを確認する。個人画像やローカル検証ログは同梱しない。
