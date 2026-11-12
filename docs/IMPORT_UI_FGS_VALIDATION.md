# 小型DNGの取り込み画面からFGS保存まで（#101 partial）

`ForegroundExportOwnerTest#importedSyntheticDngEditsAndPublishesThroughRealForegroundService` は、実DocumentsUIを起動せず標準ActivityMonitorでpicker結果だけを返す限定接続回帰。実際の取込ボタン/Panel、MediaStoreコピー、編集画面、強度0.6/比較、保存ボタン、foreground service、背景→復帰、公開JPEG readbackを小型64×48合成DNGで確認する。

picker復帰時の受付競合を限定修正する。新依存・新権限は追加しない。既存input observerの登録とOwner側取得をdebugでも許す2箇所のtest hookだけを追加し、通常releaseでは引き続き拒否する。既存fixtureアプリの既許可read権限を前提にし、足りなければ停止する。持続URI権限・外部provider・実DocumentsUI・物理SD・実写・24MP全UI/実機RAMの合格ではない。カメラ非占有観測は既存test登録、Service/保存は製品資源観測を使う。

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


## picker復帰時の受付競合

合成診断で、picker復帰直後に fresh blockerなし・所有枠空きでも、共通条件が未確認（camera/memory/rebind/recording/storage/thermal）のため受付が拒否されることを確認した。失敗段階はADMISSION、例外分類はIOException。原本事前読取/journal作成より前の失敗であり、コピーや公開を再試行して解消する問題ではない。一時診断は素材名・URI・例外messageを記録せず、原因確認後に撤去した。

選択結果は、現在の復帰世代の最初の既存resourceObserved通知まで消費しない。共通coordinator更新後の既存通知を再利用し、固定sleepや条件上書きは行わない。既存入力準備の10秒上限内で観測が届かなければ選択を破棄して理由を示す。取消・閉じる・待機開始後の背景移行も未受付選択を破棄する。最初の観測にblockerがあっても、その解消を待つ自動再試行はせず、従来の受付判定に委ねる。

JVM回帰は復帰時unknownの再現と観測後の一回受付、古い世代/背景通知の拒否、実blockerの拒否保持、取消後の非受付、観測不能時の上限終了を確認する。64MiB/メモリguard、コピー所有・公開調停・原本保護の実装は変更しない。実DocumentsUIと実機/実写の確認は別途残る。


## 実DocumentsUIの小型合成限定ケース

`ForegroundExportOwnerTest#documentsUiSyntheticDngEditsAndPublishesThroughRealForegroundService` は `isolated=true` と `importUiFgsDocuments=true` の明示時だけ実行する。新provider・新権限・依存は追加せず、既存UiAutomatorで標準pickerを操作する。通常の取込Panelから起動し、Downloads root内の今回だけの一意な自作64×48 DNGを完全一致で選ぶ。結果注入・別rootへのfallback・任意ファイル選択・画面dumpは行わない。見つからない/曖昧なら停止する。

sourceは自身がMediaStore.Downloadsへ作成した1件。DocumentsProviderのdocument URIと選択名、既存copy fingerprint/原本不変、取り込み終了後のpersisted grant集合不変を検査する。後段は既存ケースと同じ編集/実FGS保存/公開JPEG/owner解放検査を再利用する。実pickerによるアプリ間のURI受渡しを検証するが、persistable grantの取得途中を観測した証拠ではなく、任意の外部providerやSD抜去を保証しない。

成功・解放確認後だけ自身source/copy/JPEGの正確な3行を回収する。失敗時は保持し、開始時に既存pickerが前景なら停止し、picker終了は今回の起動後に同定した正確なpackageだけにBack最大3回を限定する。未同定時は操作せず終了未確認として保持する。閉じられない場合も全消去や強制終了で隠さず、保持して報告する。既存の他データ/journal/permissionを変更しない。UIは英語/日本語の既存navigation labelに限定し、未知のUIでは停止する。

```powershell
adb -s $serial shell am instrument -w -r -e isolated true -e importUiFgsDocuments true -e class dev.cinematic.p0.photoroll.ForegroundExportOwnerTest#documentsUiSyntheticDngEditsAndPublishesThroughRealForegroundService dev.cinematic.p0.test/androidx.test.runner.AndroidJUnitRunner
```

実機・実写・24MP全UI・Pixel RAM/768MiBは別許可による確認を残す。合成PASSだけで#101/#260をcloseしない。


## 実DocumentsUI復帰の初期callback未確定（#374）

#373 は foreground 復帰時の共有条件リセットから、最初の resourceObserved 通知までを待つ修正である。実DocumentsUIでは、その最初の通知自体が CameraManager 登録直後の初期callbackを取り終えていない場合が残った。

ローカルの自作合成診断では、受付拒否判定後に条件と観測メタデータをメモリへ保存し、catch後だけ匿名分類を出力した。共有条件の参照一致を確認し、同じ観測世代・sequenceの readiness/publication が初期callback未完了、unavailable未観測、サンプル不整合、共有blocker CAMERA_SESSIONのみで拒否されることを再現した。再診断では受付記録をThreadLocalに限定した。fresh側に別の既存camera snapshot timeoutも観測しており、stored側の分類をfresh側の分類と混同しない。診断の追加自体によるタイミング差は排除できず、診断版の結果を通常APKの成功としない。一時診断コードは撤去する。

修正は「初期callback未完了、unavailable未観測、blocker集合が厳密にCAMERA_SESSIONだけ」の観測に限り、同じ既存10秒枠内で次の通知を待つ。UNKNOWN全般やsample不整合全般を待つものではない。確定IN_USE、他blocker併存、初期callback完了後の不整合は従来どおり受付判定へ進み、fresh/coordinatorのguardは維持する。受付拒否後の自動再試行・固定sleep・期限再設定は追加しない。世代変更・背景化・取消の破棄条件も維持する。

追加JVM回帰は実測tupleからの一回受付、通知連続でも期限を延ばさないこと、確定unavailable/他blocker/完了後不整合の拒否、待機中の世代変更と取消を対象とする。通常APKでの実DocumentsUI→取り込み→編集→実FGS保存が成功するまではDraftを維持する。
