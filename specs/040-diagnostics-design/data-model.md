# Data Model

永続データは追加しない。ローカル選択タブのみrememberで保持。CameraProbeとCaptureSampleを読み取り、nowMsで経過を表示。epoch不一致・未取得・未来時刻は有効値にしない。preview開始/停止/exportは既存callbackへ委譲する。
