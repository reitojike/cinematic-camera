# UI / lifecycle contract
1. 明示操作でSTILL受付済みmanual pairを固定して開始。captureとpreviewのISO/ns/boostを常に区別し近似一致未検証を表示。
2. 検証中は通常ISO/SS/EV/WB/MF/format/lens/highres操作をUIとbackendで禁止。終了後に通常設定を変更する。終了は再接続で保存済み設定へ戻り、一時ロックは解除される。
3. 退出は応答待ちでも到達可能。撮影中は要求だけ保留し、file結果後に通常rebind。backgroundは状態無効化を先行しresume後に通常rebind。
4. 保存完了だけでREADYに戻さず新PREVIEW frameを待つ。still frameはpreview sampleへ混ぜない。UNKNOWNは撮影を無効にして終了導線を残す。
5. CINEへは検証を解除して通常bind。RAW原本/MediaStore/回転固定を維持。
