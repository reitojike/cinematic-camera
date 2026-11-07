# 実装計画
共通の新規OrtonZeroKeepCandidateが1画素の保護を実行する。before/after/out/scratchを呼び出し側が所有し、同期呼出しだけとする。候補はOriginalStageClear24の末尾既定false引数からのみ選択する。

keepが+0/-0ならafter Labの往復を残し、before Lab/hypot/scaleを省く。非zeroでは従来の式と順序を使う。顔maskではなく色skin fieldを判定する。

既存Orton並列化はそのままでworker専有配列を渡す。checkpoint・owner・stage順・ファイルは変更しない。光学帯処理の新規ファイルとは独立する。

標準JVM浮動小数演算を使用し新依存なし。製品導線なし。full raster比較とboundary helper比較、取り消し・例外と回収をテストする。JPEG/Android ARTと実機性能は別途未検証と明記する。
