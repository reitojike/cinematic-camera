# 顔検出の独立リアルタイム診断（準備版、#79 / #406）

製品・v0.1.0・#464には接続しない。`org.cinematic.evaluation.realtime` のdebug APKで、
検出なし、bundled ML Kit 16.1.7 ACCURATE min0.1、ACCURATE min0.05を1条件ずつ比較する。
同じAPKの対照なのでSDK providerやCameraX依存は検出なしにも存在する。
SDKを含まないAPKとの容量・process起動比較とは違う。静止画の本人判定は変更しない。

## 入力と停止

- 起動直後は入力・計測なし。既定は合成640×480。開始ボタンだけが計測を開始する。
- 合成入力は固定の灰色／シアン図形で顔なし。別の移動マーカーでUI更新を発生させる。
  顔のある映像の処理時間・精度・実カメラのフレーム落ちを代表しない。
- カメラは明示的に選択し、CAMERA権限許可後に再度開始する。許可直後に自動開始しない。
  背面CameraX 1.6.2のPreviewとRGBA ImageAnalysis。希望640×480、近傍サイズへfallbackする。
  実入力寸法が異なるrunは直接比較しない。最大各辺1920を超える入力は失敗として停止する。
- CameraXの回転角で向きを補正し不透明ARGB_8888へ変換。カメラの色変換はCameraXに依存し、
  静止画の前処理・同一フレーム比較は保証しない。条件切替後のライブ映像は別の瞬間である。
- `KEEP_ONLY_LATEST` と単一workerを使い、SDK完了までImageProxyを保持する。独自queueを増やさない。
  検出なしも同じRGBA→Bitmap準備を行う。SDKとMeasurement内の複製は行わない。
- 各runでdetectorを生成。停止・背景移行では入力を止め、SDK完了を待ってcloseする。
  解放失敗の画面では再開始しない。SDKが戻らない場合は解放待ちであり完了としない。
  停止後に遅延したprovider完了からカメラをbindしない。Activity再作成時も旧処理を先に完了する。
  プロセス共通の所有状態をclose・集計保存終了まで保持し、新画面でもその間は開始受付・タイマー・入力を開始しない。
  「前の計測が解放中」と表示されたら完了後に開始を押し直す。旧処理の待ち時間を新runの性能に混ぜない。
  close失敗では他のActivityからも開始を受け付けず、未解放として止める。
  CameraXのCameraStateがエラーを通知した場合はCAMERA_ERRORとして停止し、自動再開しない。

## 計時契約（診断値、採用閾値なし）

既存MeasurementのSDK wall / adapter / Bitmap複製込みtotal、生成時初期化とfirst-call / warmを再利用。
fresh process coldやカメラ起動時間ではない。入力準備（RGBAコピー・回転／合成生成）、
analyzer内の準備開始から検出完了までを追加する。CameraX内YUV→RGBA変換・キュー滞留は含まない。

PreviewView.COMPATIBLEを使い、プレビューを含む診断WindowのFrameMetrics TOTAL_DURATIONと
INTENDED_VSYNC_TIMESTAMPの差を記録する。UIはChoreographer callback間隔。
Windowの観測はカメラ映像のみの提示間隔ではなく、他のView描画も含む。合成も同じWindow計時を使う。
FrameMetrics通知のdrop件数を残す。camera timestampとUI clockを引いてlatencyを算出しない。
物理ディスプレイpresent・製品GPU描画時間・end-to-end latencyではない。
callbackが0ならn=0/nullで未測定。CameraXのdrop数は取得できずnull。合成のworker繁忙skipだけを数える。
時間標本は系列ごと最大10000、超過件数を明示する。

最初の入力から30秒で入力を止め、最後の処理とcloseを待つ。input_window_msとdrain_msを分け、
throughputの分母duration_msはdrainを含む。失敗・中断・NOT_RUNを成功へ変換せず、空runはNOT_RUN。
MEASUREDは観測取得であり性能合格ではない。背景移行後や回転後は自動再開しない。

## 保存・権限

画像・動画・顔枠・顔数・URI・camera ID・端末識別子・例外文・個々のtimestampは保存しない。
集計だけをnoBackup領域 `realtime-aggregates/*.json` に新規保存し画面に表示する。
共有/upload・写真picker・ストレージ権限なし。FLAG_SECURE、backup無効。
内部ファイル回収には別途承認された実機手順が必要。終了後の集計文字は選択できる。
既存SDK診断通信許容を継承するが、provider/DataTransportの通信実証・第三者notice網羅確認は未完。
CAMERAは非製品realtime flavorだけで、他flavorへの漏れをauditする。
この手順・ビルドを画像送信や実機カメラ起動の許可と解釈しない。

## 準備検証

既存JDK/SDK/Gradleキャッシュを指定し、共有の重いbuildがない時に直列実行する。

```text
gradlew -p tools/face-evaluation :benchmark:testRealtimeDebugUnitTest :benchmark:assembleRealtimeDebug :benchmark:assembleRealtimeDebugAndroidTest :benchmark:lintRealtimeDebug --offline --no-daemon --max-workers=1
python -B -m unittest discover -s tools/face-evaluation -v
python tools/face-evaluation/audit.py --flavor realtime
```

出力: `benchmark/build/outputs/apk/realtime/debug/benchmark-realtime-debug.apk`。
JVMは条件/phase/空/失敗/欠測・bounded系列・RGBA stride境界。Android合成試験はfake detectorで
既存Measurementとの接続を確認するためのもの。test APKのbuildと実行は別。
合成Activity試験は未計測での起動、検出なしの開始、背景停止と前景での非自動再開を対象にする。
SDK実行中の停止、遅延provider、Activity再作成、実camera解放はまだ実行検証していない。
通常root CIはこの独立projectの専用試験を回さない。製品GPU90や他レーンAVDを準備のために実行しない。

## 後続の本人操作（導入・実行承認後）

1. 別ID・対象head・APK hashを照合して導入。製品アプリの更新・削除は不要。
2. 合成入力で各条件30秒、停止／背景移行を確認（約3分）。未確認を実行済みにしない。
3. カメラを選び、許可して再度開始。同じ照明・構図・距離で検出なし→min0.1→min0.05を各30秒。
   冷まして逆順も実行。熱と他アプリ負荷を揃える。目安5〜10分＋必要な冷却時間。
4. source・寸法・条件、first/warm、欠測/失敗、Window描画時間/間隔・UI間隔、drainを一緒に比較する。
   顔の位置・精度判定には使わず、静止画30枚の再判定は求めない。
5. 停止・背景移行後にcamera使用表示が消えることを本人が確認。解放待ち・ANR・想定外権限で止める。

実機操作、導入、APK配布、実カメラ性能、熱/電力、製品プレビュー比較、採用・mergeは別途。
レーンAの普段使い・最終確認・releaseを待たせない。

根拠: [CameraX ImageAnalysis](https://developer.android.com/media/camera/camerax/analyze)、
[ImageAnalysis API](https://developer.android.com/reference/androidx/camera/core/ImageAnalysis)、
[FrameMetrics](https://developer.android.com/reference/android/view/FrameMetrics)。
