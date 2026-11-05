# 小型合成DNGの私有基準JPEG検証（#143）

- FR-001: 診断だけの小型経路とする。classic little-endian TIFF/Bayer、入力128KiB以下・各辺128以下・格納4096画素以下に固定する。ActiveArea/標準cropは整数・範囲内の明示値のみ、未知のgeometryを成功値で補わない。
- FR-002: 同じLibRaw出力API/AS_SHOTの処理を使い、half-size/resizeなしで全画素をnativeでsRGB転送1回・RGBA8へ変換する。API29で使えるsoftware Bitmap/Bitmap.compressを使い、原寸用の巨大Java配列を追加しない。小型上限内のRGB16 oracleを試験へ返すことは許容する。
- FR-003: 専用私有領域へCREATE_NEW/NOFOLLOWでJPEGを生成する。codec成功・write/flush/closeの成功・書込成功byteとfresh readbackの全byte hash一致・寸法/向き/sRGBと原本不変を確認後だけ診断成功にする。画素は正立済みで、EXIF readerの方向0（未定義）/1だけを認める。画素方向1とreader観測値を別々に記録し、EXIF 1 tagを書いたとは主張しない。
- FR-004: writeの部分成功/無書込/例外、flush/close失敗、decode/pack/write境界の取消を成功にしない。実return/closeまで資源ownerを維持し、close再確認は再renderを意味しない。原本と途中出力を保全する。
- FR-005: cropは独立にpre-cropして生成した別DNG、回転はKotlinの座標変換、RGBAは別の転送式で小型全画素比較する。JPEGはlossyなので圧縮前の画素照合と圧縮後のcodec検査を分離する。
- FR-006: R7/Stage Clear/profile/face/EV成功や製品FULLへ変換しない。保存・UI・他PRへ接続せず、公開投稿/push/PR作成は保留する。Pixel実測と12/24MP JPEG成立は未検証のままにする。
