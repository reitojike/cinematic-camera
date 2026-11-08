# 検証手順
`testDebugUnitTest`で`PreviewParallelStagesTest`を実行する。全チャンネルのビット一致、取消・失敗時の安全性、workerが残らないことを確認する。

PR作成前に`lintDebug`、`testDebugUnitTest`、`assembleDebug`、`git diff --check`を実行する。任意のPCベンチマークは参考用の中央値を報告するだけで、実機での採用判断には使わない。製品の呼出しが引き続き実行contextを渡していないことを確認する。
