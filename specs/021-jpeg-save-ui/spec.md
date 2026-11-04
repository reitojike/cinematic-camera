# 完成JPEGの保存可否と結果確認

既存Issue #89起点。#118の公開契約を弱めず、photoroll側の受け渡しと表示だけを準備する。

- 現行STAGE_CLEAR_TRIAL（512px・顔NOT_RUN・診断露出）とDNG_TRIALは保存不可。理由を表示し、保存ボタンを無効にする。試験JPEGを別経路で公開しない。
- 将来の完成rendererだけが、固定要求/世代・原本URI/hashに結び付いた保持済みのPhotoRollJpegSaveを返せる。試験mode、異なる原本、未登録renderer/schema、未確認の顔/露出はpublisher生成前に拒否する。
- UIはcaptureIdや世代を作らず、rendererが保持する同じpublisherを呼ぶ。再試行/結果を開く直前も同じ操作のadvanceでreadback/hash確認し、PUBLISHEDだけを結果として開く。UNKNOWNは停止、例外/中断は未確認とし成功表示しない。
- Back/背景化/再選択で中断し、process内単一permitを実IO終了まで保持する。旧ticketの結果は反映しない。JPEG表示のための外部アプリ起動は本人のボタン操作でのみ行い、read grantだけを渡す。
- 同じownerへの競合呼出は再照合の証拠を持たないためSAVING/URIなしを返し、実処理の状態は変更しない。状態通知を前景・同じticket/source/ownerに限って観測し、共有workerが終了した後に確認中表示を残さない。通知による公開済み状態は結果を開く承認ではなく、本人の新たなadvance照合が必要。

未成立の前提: 完成renderer/private JPEG、登録契約、render前の要求/世代の永続化と権威、同operation ownerのActivity再生成をまたぐ保持、撮影優先admission。これらは本変更で実装/有効化せず、現行appは保存不可のまま。mockを完成保存として見せない。

対象外: rawcapture/codec/公開台帳、顔/AE/profile、JPEG encoder、試験JPEG保存、新依存/権限、既存写真/原本変更、実機撮影・配布。
