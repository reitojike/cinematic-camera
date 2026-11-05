# 原寸DNGの隔離診断（#143）

- FR-001: 12MP/24MPの原寸decodeと端末メモリを測定する。通常appの1024/16MP上限・顔/露出/公開ゲートを変更しない。
- FR-002: 診断APKは別applicationIdのdebug限定とし、既存カメラappへ上書きinstallしない。撮影/通信/外部ストレージの権限・起動UIを追加しない。合成DNGだけを内部に生成する。
- FR-003: RAM指定・実際のsystem totalMem・memoryClass/largeMemoryClass・Runtime.maxMemory・managed/native/PSS・thermalを区別して報告する。診断processの値を本体processのpeakと呼ばない。
- FR-004: native full-sizeは固定上限の明示probeとし、全RGB FloatArrayをJavaへ渡さず、寸法/標本/checksum/段階時間を返す。WB/色変換、bounded snapshot、原本照合、FD/RAII解放を保つ。
- FR-005: 生画像/原本/個人path/serialをreportへ含めない。失敗/停止/未測定を成功にせず、実機とAVD、設定と観測を分ける。製品appへのlargeHeap採用、強制GC、自動上限拡大をしない。
- FR-006: 追加の実機比較依頼に従い、診断の通常/largeHeapを別applicationIdで作る。largeHeapは起動時の設定であり処理中の切替ではない。現行R7/Stage Clearソースで固定1024/1536/2048の合成演算を同条件3回測る。OOM/OS停止を成功にせず、native原寸decode・現像演算・実app全体を混同しない。既存署名appは利用者が画面を操作し、メモリをread-onlyで観察する。

診断はStage Clear/JPEG/画面/自動公開の完成やPixel互換・画質・性能の合格ではない。RAM比較は他のAVDを操作せず、ホストの空きcommit/physical/diskを確認して1台ずつ行う。
