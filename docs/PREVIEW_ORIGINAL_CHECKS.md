# プレビュー原本照合の境界

Issue #265。Draft PR 内の提案で、マージまでは main の契約を変更しない。

## 保つ保証と検出限界

原本を変更しない保証は、共有 DNG を ContentResolver の読み取り専用 stream として開き、書込み先をアプリ私有 snapshot / raster / 出力 JPEG に限定する構造にある。SHA-256 は内容が一致するかを観測する仕組みであり、書込み防止ロックではない。

写真の選択・再開、snapshot コピー、展開後の原本照合、保存と公開の既存の強い照合は残す。プレビュー専用 callback は保存 backend へ渡さない。永続 recipe / request の SharedOriginalReference は URI・bytes・SHA-256・grant のままで、mtime を永続化しない。

同じ手動試験画面での再描画は、最初の確認時に観測した size/mtime と比較し、公開/所有状態・読み取り権限を再確認する。mtime が変わった時に新しい基準へ黙って更新しない。既存の epoch / cancellation / queue / native owner と解放確認を保つ。

- DocumentsProvider: [COLUMN_LAST_MODIFIED](https://developer.android.com/reference/android/provider/DocumentsContract.Document#COLUMN_LAST_MODIFIED) は millisecond。未知なら null を返し得る。
- MediaStore: [DATE_MODIFIED](https://developer.android.com/reference/android/provider/MediaStore.MediaColumns#DATE_MODIFIED) は second 単位の index 情報。File.setLastModified や時計変更、index 更新遅延を含め、内容一致の証明ではない。
- 同一サイズ・同一mtimeの差し替えを検出できない。保持済み入力から古いプレビューが出る可能性はあるが、その内容を現在の原本と同一だと認定しない。保存時の既存 SHA は元の fingerprint と比較し、異なる原本を拒否する。
- mtime が欠落・null・0以下なら従来の全体照合へ fallback する。size 不明、行消失、pending/trash、所有状態変化、読取拒否は停止する。
- 照合後に外部変更される race 自体は SHA でも完全に除去できない。プレビューは immutable snapshot 由来の縮小入力、保存は既存の verified snapshot / publication fences で扱う。

## 検証

JVM は metadata の一致/変更/未知、同一metadataでは検出できない改変を SHA が拒否すること、cursor close failure の所有権を確認する。隔離 GMD はローカル合成 provider と合成 DNG のみを使う。warm の原本全体読取り0件、cold SHA維持、画素同一、変更と取消の失効を確認する。

RawPreviewTiming の VERIFY_BEFORE / VERIFY_AFTER は今後この確認境界を指す。過去の SHA 全体読取り時間と、改修後の metadata 時間を同じ処理の速度差と混同しない。実機性能、個人写真、OS の提示時間はこの変更で再測定しない。
