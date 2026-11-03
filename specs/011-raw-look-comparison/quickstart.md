# Validation guide

専用Pythonと固定依存は[既存手順](../../tools/raw_baseline/README.md)。
python -m unittest discover -s tools/raw_baseline -p 'test_*.py' -v で合成/拒否/再生成検証。
実行契約は[比較手順](../../tools/raw_baseline/LOOK_COMPARISON.md)。
許可root/recipe/新outputを明示。画像hash再一致、原本前後一致、EXIF/GPSなしを確認。
index.htmlでscene/4案/全体/等倍/肌衣装照明。file拒否時は迂回せず未描画を記録。
本人好み/画質受入は別。新PRのmerge/Releaseなし。
