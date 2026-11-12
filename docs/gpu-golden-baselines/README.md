# 採用中の GPU 回帰基準

現行 run: `golden-20261112-131916-51b691ad`、画像 source: `51b691ad561da05dde1c8f9a2e9cde09b2656860`。
#355 の R7 foundation 定義域修正を本人が目視確認した出力です。90 gzip は計9,887,209 bytes。
既存 approve コマンドで目視済み候補からbyte不変コピーし、capture.json / host.json とそのhashを承認に結び付けています。
capture.json の baselineApproved=false は採取時点の不変フィールドであり、現在の採用状態とは別です。

## 本人確認と対象

[本人に提示した90条件・代表7条件・前後差](https://github.com/reitojike/cinematic-camera/blob/68b703fb00bd2c24a935298db12b32f2a33fe699/docs/r7-foundation-review/51b691ad/README.md) に対して、2026-11-12 に本人は次のように確認しました。

> 目視では問題なく修正されている

続いて今回の対応について:

> わかりました、修正完了と判断します。実際の写真で問題起きたら別途課題として挙げるので、一旦今回の対応はこれでクローズで良い

画像source51b691adと提示資料commit68b703fの対応をapproval.jsonに記録しています。確認専用PNG等は役割を終えたため作業branchから除き、上の固定commitで判断履歴を参照できます。旧基準は下記の履歴として保持します。

## 確認範囲と限界

- 90条件＋帯64/256行の28比較が成功。旧基準に対して13条件だけ変化し、77条件とc020はRGB8完全一致。既知HDR円内のnear-blackは13条件すべて0になりました。強さありでは後段Glow/Mistへ広域に変化が伝播します。
- 新GPU対host CPU数学参考は全90最大1/255、P99=0、9条件byte完全一致。これはhost比較であり、Android全90の資源安全性を示しません。AndroidのR7全tap/cache80条件、新しい端点・自動負EV/手動EVの回帰を別途実測しています。
- 実写真の画質・頻度・実機性能は未確認。本人が実写で問題を認めた場合は別課題として扱います。c020の既存にじみや#352の保存開始問題をこの修正で解決扱いにしません。
- 旧JPEG・保存世代は移行しません。基準採用と製品のマージ/配布は別の段階です。

## 手動比較

```text
python tools/gpu-golden/gpu_golden.py compare docs/gpu-golden-baselines/golden-20261112-131916-51b691ad <new-candidate> <new-report-directory>
```

同じ環境では厳密RGB8一致。環境や出力が変わればHOLDし、本人確認なしの自動更新・閾値緩和はしません。GPU/ルック変更PRでは最終clean headで既存90条件を再採取して比較します。今回main同期後の再採取も採用画像の差替えではなく同一性確認に使います。自動Mesa全工程CIやCPU goldenの置換は追加していません。

---

# 旧基準の採用履歴（現在の比較先ではない）

以下は #349 採用時の記録です。#355 の旧『未修正・様子見』はその時点の判断であり、現在の修正完了承認は上記を参照してください。

# Adopted current-output GPU regression baseline

Run: `golden-20261112-072140-1a6ded41`; source `1a6ded41d0fe9eb09e467885e625a6c722c237a6`.
Adopted on 2026-11-12 using the existing approve command, without regeneration or pixel changes.
The 90 gzip files total 9,828,462 bytes. capture.json and host.json preserve original provenance; approval.json binds their hashes to owner approval. capture.json baselineApproved=false is the immutable capture-time field, not the current approval state.

## Exact owner approval

The owner was asked:

> 既知の黒化を含む現状の出力として、90条件の基準データ（約9.8 MB）と環境・承認情報を #349 に登録し、最新 main を取り込んで最終確認してよいですか？ 確認用の一覧2枚と代表10枚は外します

The owner subsequently replied:

> はい、OKです

This authorizes the current output, including known shared black inversion, as a regression reference. It does not declare image quality normal or issue #355 fixed. Further investigation/correction is deferred unless noticeable during real-device use. PR merge is not authorized by this record.

## Scope and known behavior

See [90-case investigation](../gpu-golden-candidates/golden-20261112-072140-1a6ded41/README.md) and [issue #355](https://github.com/reitojike/cinematic-camera/issues/355).
All90 CPU/GPU comparisons: maximum channel difference <=1/255, P99=0 per case, 10 byte-exact. The final table uses 76 Android references plus14 host references. Android completed77 outputs (c077 overlaps host), but export selector FAILED at c078 OOM. This is not77 Android test passes or proof of Android resource safety.

## Test wiring

This directory is the argument to the existing manual comparator, not new CI wiring:
```text
python tools/gpu-golden/gpu_golden.py compare docs/gpu-golden-baselines/golden-20261112-072140-1a6ded41 <new-candidate> <new-report-directory>
```
Same-environment comparison requires exact RGB8. Changed environment or output holds for owner review; no automatic replacement or relaxed threshold. Capture remains the existing opt-in Android90 method, including28 band/full checks. No product formula, fixture, CPU golden or CI setting was changed.
