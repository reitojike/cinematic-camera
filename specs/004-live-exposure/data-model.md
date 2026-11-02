# Data model
ShutterStop(label, exposureNs)、ISO候補はISO/ns範囲と最大frame期間に交差する。CINEは希望fps上限から導くframe期間以下。端の不正clampなし。
ControlToken(epoch, sequence)はOperationWatchdogの現在1件に対応。begin→complete/expire/invalidate。期限後のlistener/nextは一致しない。
LatestExposureQueueはactive Entryとqueued Entryの最大2件。offerはqueuedを置換、completeは同Entryだけ、cancelは両方破棄。latestPairはqueued→active。
長露光budgetは新pairだけでなく直前送信/受付/実測frame期間を使う。timeoutはUnknownでありセンサー停止の断定ではない。
- **復旧時の重複判定**: current epoch・controlReady・有効な受付時刻/受付snapshotがある場合だけ旧受付と同値を省略する。UNKNOWNでは再送する。
- **期限hint**: 同じcamera IDの既知の最長未確認要求時間をメモリ内に保持。stop/rebindで消さず、同cameraのAUTO clear/短露光が受付完了してから更新する。現在センサー設定/実測/永続設定の証拠ではない。
