# 小型DNGの取り込み画面からFGS保存まで（#101 partial）

`ForegroundExportOwnerTest#importedSyntheticDngEditsAndPublishesThroughRealForegroundService` は、実DocumentsUIを起動せず標準ActivityMonitorでpicker結果だけを返す限定接続回帰。実際の取込ボタン/Panel、MediaStoreコピー、編集画面、強度0.6/比較、保存ボタン、foreground service、背景→復帰、公開JPEG readbackを小型64×48合成DNGで確認する。

製品releaseの動作変更、新依存、新権限なし。既存input observerの登録とOwner側取得をdebugでも許す2箇所のtest hookだけを追加し、通常releaseでは引き続き拒否する。既存fixtureアプリの既許可read権限を前提にし、足りなければ停止する。持続URI権限・外部provider・実DocumentsUI・物理SD・実写・24MP全UI/実機RAMの合格ではない。カメラ非占有観測は既存test登録、Service/保存は製品資源観測を使う。

## 条件と確認

- 通常debugアプリ、emulator、isolated=trueとimportUiFgs=trueを明示。通常GPU対応AVDを予約し、空き確認後に-r更新。既存データ/設定/permissionを全消去しない。
- 合成RAWは一意の乱数系列を使い、過去importのfingerprint再利用を拒否。共有原本のcopy成功/SESSION_ONLY、元sourceとコピーのhash不変、保存source/recipe一致、PUBLISHED URI/journal、JPEG64×48/sRGB/fingerprintを検査する。
- JPEG件数は隔離枠の当該アプリownerのbefore/afterで+1。全MediaStore/他providerの重複不存在やprocess-death重複防止の証拠ではない。
- input observerのopen/release対応、保存owner解放、snapshot/RGB16 workspace除去、FGS ticketとService終了を確認。編集設定/私有journalは一意の自作記録として保持し、他担当journalは削除しない。
- 全終了処理をattemptしprimary失敗へsuppressedで保持。成功・owner終了確認後のみ、正確なURI/name/owner/publishedを再検査して自作source/コピー/JPEGの3共有行を回収。不明/失敗は保持して停止する。

```powershell
adb -s $serial shell am instrument -w -r -e isolated true -e importUiFgs true -e class dev.cinematic.p0.photoroll.ForegroundExportOwnerTest#importedSyntheticDngEditsAndPublishesThroughRealForegroundService dev.cinematic.p0.test/androidx.test.runner.AndroidJUnitRunner
```

結果は実行head/条件とともにPRへ記録。既存のCPU/FGS試験や24MP部品試験を置き換えない。#101/#260はOPEN維持。

GPU診断variantはread権限をmanifestから除外しているため全画面先にしない。通常debugの既存許可を再利用し、権限を追加/変更しない。

通常debugの既定GPUを読み取り検査し、engine設定を書き換えない。保存準備まで到達しqueue利用を確認した場合だけdiscardPreviewを呼び、初期化前の失敗からcleanupでqueueを新規起動しない。observerの失敗時不一致は保守的な解放未確認であり、リーク確定とはしない。
