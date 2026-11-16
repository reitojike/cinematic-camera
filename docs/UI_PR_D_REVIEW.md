# PR-D 現像配置の確認一覧（準備中）

根拠: [改修指示書](DEVELOP_UI_REFACTOR_INSTRUCTIONS.md) 3.4(5)(7)、[Issue #439](https://github.com/reitojike/cinematic-camera/issues/439)、#392 と #435 の本人レビュー。

## 表示だけの変更

画像の下に調整パネルを置き、パネル開閉で画像の高さが変わる方式。小画面は画像260dpで、一時通知・行内注記は画像外に置く。画像がない問題状態のカードは画像領域内の中央へ置く。保存行、編集の入力、保存・復旧・比較の callback と有効条件は変更しない。

| 変更前 | 変更後 |
| --- | --- |
| 現像結果 · 長押しで基準表示 | 現像結果 |
| 撮影時WBのRAW基準表示（R7・ルック・手動調整なし、暫定色） | 基準表示（撮影時WB・調整なし） |
| 表示が要求に未到達でも通常色 | 上記キャプション + 更新中、WARNING |
| 長押しの説明が常時キャプション | INFO の「画像を長押ししている間だけ基準表示に切り替わります。」 |
| 調整パネルが画像下端を覆う | 画像領域の下へ分離 |
| 再保存確認の危険操作が閉じる操作と近接 | 危険操作直前を広く空ける（順序・非強調は維持） |

## 採取する画面全体

通常、比較、未反映、編集中、準備中、保存中、取消後、失敗、小画面・拡大文字、再保存確認。合成入力・空GPU Surfaceの画像はその旨を明示し、実写真の見え方やGPU画質・速度の証拠にしない。

## 検証状況（2026-11-15）

製品head `41e22b51`：lint・単体1385件（失敗0、skip12）・APK/testAPK、CI #1217成功。配置8状態・INFO／確認ダイアログ・短画面／caption遷移／比較の計6 Android試験を再実行して成功。GPU90条件・帯28件と基準全画素比較に合格（差0、Google APIs→AOSPの環境差あり）。基準は更新していない。

実製品 `53b9f98d` の手動回帰では非個人の合成DNGを使い、Files選択、GPU表示、INFO開閉、比較／長押し、強さ変更、保存、取消、同設定で再保存、一覧復帰、短画面・fontScale1.3のスクロールを確認。JPEG2枚の復号に成功、原本SHA-256不変。専用Downloadsルートでの既存原本条件による拒否と一覧復帰、内部ストレージDownloadでの読み込み成功も確認。

手動回帰で発見した「保存による意図的なプレビュー停止にも更新中と表示する」不備は `41e22b51` で表示条件のみ修正。独立レビュー、上記6試験・GPU回帰・CIに合格。手動回帰全体は修正後に繰り返したとは扱わず、影響箇所を実経路と同じpresented=nullの試験と画像で再確認した。save ownerや操作条件は変更していない。

固定状態の空Surface画像は実写真やPixelの証拠ではない。修正後の compact-failed / saving を含む見た目は本人確認済み。固定 Surface 案の採用条件と最終検証・レビューは別途確認する。最新文書のみのcommitは上記製品試験を継承し、CIを別途確認する。

確認画像: https://github.com/reitojike/cinematic-camera/blob/docs/pr440-layout-review/docs/ui-review/pr440/README.md
手動回帰の範囲・証拠: https://github.com/reitojike/cinematic-camera/blob/docs/pr440-layout-review/docs/ui-review/pr440/manual/README.md

## 残る旧引数

CPU producerと診断ビルドは退役済みだが、mainには非GPUのPhotoRollScreen呼出しと旧安全性試験fixtureが残る。preview/comparison/stage/mode/statusOverlay の撤去を表示修正だけで安全にできるか確認する。旧入力/保存ownerごと削除する変更はこのPRに無断で混ぜない。
