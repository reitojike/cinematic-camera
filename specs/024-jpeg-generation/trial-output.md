# 顔保護なしのprivate試験JPEG（#129）

完成出力のFR-001〜007と公開ゲートは維持する。顔SDKを保留した初回試用は、別schema/entry/結果型を持つ手動private試験経路とする。

## 要件

- TR-001: recipeは固定as-shot暫定入力・強度0.5・quality95・長辺1024・既存中央p97診断EV・明示空foundation ROI・顔保護無効を記録する。schemaは完成用と異なる。R7PreviewInputsのAS_SHOT_BEFORE_W1だけを使い、W1後の画像でEVを推定しない。
- TR-002: 明示的なtrial entryのみがtrial recipeを受ける。完成entryはtrial recipeを、trial entryは完成recipeを、IO前に拒否する。単一attemptのsingle-use/single-flight、原本/世代/target検査、8MiB、write/close/codec/readback/hash、原本前後再照合、協調停止・失敗close保持は共通実装を使う。
- TR-003: 顔状態はNOT_RUN、露出はDIAGNOSTICのまま返す。空maskは保護無効の計算入力で、VERIFIED_NONEへ変換しない。診断をSUPPORTEDへ変換しない。最小trial policyの空ROIも独立して検査する。
- TR-004: 結果はTrialJpegで、固定request/実output descriptor/JPEG fingerprint/試験状態を含む。CompletedRenderや公開ownerを含めず、publication contractを登録しない。完成publisherの拒否条件を変更しない。
- TR-005: UI/制御は別担当。既存coordinatorの枠で手動起動し、同じprivate artifactのfresh readback/hash/原本/世代を確認してアプリ内に開く。UIは顔保護なし・縮小1024以下・診断露出・暫定色を明示する。別queue、UI側ID採番、共有領域保存・自動起動/公開を追加しない。

## 設計・検証

JpegGenerationRecipe.trial()で試験recipeを作り、JpegGenerationAttempt.renderTrial(stop)からTrialJpegを返す。完成render(stop)は従来の厳格な検査を維持し、共通private pipelineだけを抽出する。
AsShotNoFaceTrialControlsは既存R7PreviewInputsを再利用する固定provider。FrameSourceと実FD所有/失敗close保持は制御担当のAndroidOwnedJpegFramesへ統一する。本部品はJpegFrameSource境界を受け、正常returnはsource資源close済みとする。部品単独のAndroid合成検証では既存decoderの正常close境界を使うが、制御のFD失敗close/実解放ackを検証済みとはしない。
試験結果は完成結果と変換互換にしない。

JVMで相互entry拒否・NOT_RUN/DIAGNOSTIC保持・実provider・原本/target/byte/IO故障/中断/解放を確認する。Androidで同じ合成DNGから専用1024 JPEGが生成され、試験状態/Exif/sRGB/hash/原本不変が保たれることを確認する。既存完成生成の回帰も維持する。
UI/制御との結合は#89担当の固定handleを利用し、未接続時は試験部品だけを完了と報告する。Pixel実機・full-res・画質/性能合格は対象外。撮影開始では停止要求だけでleaseを解放せず、render復帰と実資源の静止まで制御側が所有する。
