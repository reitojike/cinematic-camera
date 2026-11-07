# 設計
AndroidのLog.isLoggableで明示的に有効になった診断だけを生成する。Kotlinの小さな単調時計timelineは既存onStageを観測し、画素や制御フローを変更しない。clock/sinkを注入でき、単体テストで保存された各区間と総計を照合する。
処理の開始から原本確認、render、再照合、Mainへの復帰・状態採用までを別区間とする。通常経路の内部細分化は範囲外。時刻は写真操作開始ではなく、LaunchedEffectで要求を受け付けた時刻。物理操作から表示までの差は#208で別に測る。
既存のOriginal24PreviewSessionは展開入力/R7 base/Glow fieldを保持している。#207の前提をIssueで修正する。新依存、工程変更、保存経路変更なし。

## 診断の有効化と測定
通常はログを出さない。確認時のみ対象デバイスに `adb shell setprop log.tag.PreviewTiming DEBUG` を設定し、`adb logcat -s PreviewTiming:D` で匿名の1要求1行を取得する。終了後は `adb shell setprop log.tag.PreviewTiming INFO` に戻す。実機操作は別承認の機会に行う。
通常手動経路のTRIAL_RENDERは内部展開・ベース・ルック・変換を含む統合区間であり、個別値は得られない。CHECK_ORIGINALは照合と設定復元、VERIFY_RESULTは生成結果の照合とMain復帰待ちを含む。ADOPT_STATEはUI状態への採用であり、ディスプレイのフレーム完了ではない。
#208は4操作×（慣らし1＋3回）、計16操作でログと録画を同時取得する。保存速度の測定とはセッションを分けて冷却し、録画ありの条件を明記する。追加の録画なし16回は必須としない。
