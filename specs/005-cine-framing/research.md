# 調査: CINE画角と設定overlay

## Decision: CINEのみ共通ViewPortを指定

CameraX 1.6.2公開APIの`SessionConfig.Builder.setViewPort`を使い、portrait基準の9:16、ROTATION_0、FILL_CENTERをPreview/VideoCaptureへ共有する。同一ViewPortをfps照会・対応判定・bindに使う。Recorderは16:9、PreviewのResolutionSelectorも16:9優先とする。

Rationale: 解像度優先だけではPreviewと動画のセンサー領域が一致しない。ViewPort.FITは比率を無視するため使用しない。既存SessionConfigのfps/DR/quality fallbackを維持する。

Alternatives considered: UseCaseGroupへの移行は不要。RestrictedなVideoCapabilities.getResolution、存在しないVideoCapture.setTargetAspectRatioは使わない。QualitySelector.getResolutionからHDR実寸を推定しない。

公開API根拠: [SessionConfig.Builder](https://developer.android.com/reference/androidx/camera/core/SessionConfig.Builder)、[ViewPort.Builder](https://developer.android.com/reference/androidx/camera/core/ViewPort.Builder)、[Recorder.Builder](https://developer.android.com/reference/androidx/camera/video/Recorder.Builder)。1.6.2依存に対するコンパイル/lintも確認する。

## Decision: 設定は固定Preview領域内のoverlay

設定はcapture railの上に重ねる。PreviewViewのmount/寸法/モード別top inset/scale typeをパネル状態から独立させる。FIT_CENTERで画像全体を水平中央へ表示する。設定背景はpointerを消費し、Preview callbackもパネル開中のAF/AEを拒否する。

Rationale: 従来の縮小は開閉時の画角/表示変化を生む。PreviewView再生成やcamera再bindを必要としない。

Alternatives considered: 003の設定時縮小は今回の明示要件により置換。独自描画/座標変換/回転処理は追加しない。

## Decision: accessibility clickは中央AF/AE

PreviewViewの標準click actionで中央AF/AEを要求する。物理tap後のperformClick通知中だけ中央動作を抑止し、正確なtap位置への要求1回を保つ。パネル開中は同じguardを適用する。

実機一致は未検証。bindのresolution/cropRect/rotationDegreesと保存MP4の実寸/回転を別記録し、HDR実寸や端末合格を推定しない。
