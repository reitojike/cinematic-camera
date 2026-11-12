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
