# Data model

Recipe(schema1): 固定4案とscenes1..3。rootは実行時指定、recipeは相対pathだけ。
Scene: 大文字小文字を無視して一意な匿名id、root相対source、sigmafp限定profile、WB/任意JPEG、skin ellipses(cx,cy,rx,ry)、focus box(肌/衣装/照明)、base_ev/skin_ev、local note。
Private manifest: recipe、source hash/size/mtime前後、baseline監査、環境/コードhash、画像hash/寸法/clip。
Page data: 匿名scene、相対asset、focus/4案説明。source pathとmetadataは含めない。
不正値/リンク/脱出/重複を拒否し既存input/outputを変更しない。
