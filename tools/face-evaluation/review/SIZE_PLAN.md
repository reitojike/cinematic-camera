# APK容量比較の次段階

## 既存実測と境界

#406の既存debug / arm64-v8a+x86_64 / 縮小なし評価APKは、baseline 2,474,357 bytes、
ML Kit 32,441,743 bytes、YuNet 89,259,083 bytes。ML Kitは同梱版のみ。
この値は製品版の増分でもPlay配信サイズでもない。モデル単体とOpenCV全体を混同しない。

## 比較条件

製品source/root Gradleへは追加せず、評価専用projectにunbundled ML Kitのflavorを追加する。
同一head/JDK/AGP/SDK、同一ABI・縮小条件でbaseline、bundled、Play、YuNetを比較する。
APK bytes、baselineとの差、圧縮前native、モデルassetsを分けて記録する。
2 ABI universalだけでなく、実機を想定したarm64-onlyを同条件の独立表にする。
Playモデルの取得後の端末容量、初回未取得時の動作/オフライン可否はAPK増分とは別測定。
未取得のため結果がない状態を「顔なし」と数えない。

Google公式の参考値はPlay版約800 KB、同梱版約6.9 MBであり、本projectの実測ではない。
2026-11-16確認: https://developers.google.com/ml-kit/vision/face-detection/android
現在の公式依存指定は同梱16.1.7、Play17.1.0。既存bundledの版は変えない。
モデルが使用前に存在する必要があることを確認し、公開素材/合成による疎通と
本人の実写精度、Pixelのcold/warm性能を分離する。

## 実行順

v0.1.0のPixel準備/Release用buildを優先。共有Windows build/AVD枠の予約後に
評価flavorの追加、manifest/通知監査、unit/build/lint、合成smokeを行う。
実機導入・配布・実写真の端末外転送はこの計画によって許可されない。
静止画比較の採用候補が決まるまで撮影中プレビューの速度・負荷は測定しない。

## 同条件4flavor実測（2026-11-16）

同一ソース候補・JDK25・AGP9.5.1・Gradle9.9.1・SDK37・minSdk33、debug、縮小なし、
arm64-v8a+x86_64対象（nativeが存在するflavorのみ）。通知assetsを含む。
これは評価APKの増分であり、製品版/R8最適化後/Play配信サイズではない。

| flavor | APK bytes | baseline差 bytes | native非圧縮 bytes |
|---|---:|---:|---:|
| baseline | 2,474,871 | 0 | 0 |
| ML Kit 同梱 | 32,441,826 | 29,966,955 | 18,206,360 |
| ML Kit Play | 6,681,523 | 4,206,652 | 0 |
| YuNet同梱＋OpenCV | 89,259,122 | 86,784,251 | 85,810,144 |

Play版の値にはモデル可用性API依存も含む。端末に後から取得されるモデル量は未測定。
YuNetモデルは232,589 bytesの固定2023mar、APK増の大部分はOpenCVを含むruntime。
モデルだけのサイズとSDK全体を区別する。

全4flavorのapp/test APK生成とlintがverifyEvaluationで成功（1m17s / 294tasks）。
初回offline buildは新依存のキャッシュ不足で失敗し、公式Maven取得後の通常buildで成功。
独立レビューでライセンス参照の旧同梱版表記を修正後、最終offline verifyEvaluationも43秒で成功。
APK auditでbaseline/YuNetは通信権限なし、ML Kit両flavorはINTERNET/ACCESS_NETWORK_STATE
のみ（自package signature権限を除く）、写真/カメラ/storage権限なし、固定model SHA一致。
この試行ではAVD/実機にインストールせず、Playモデル取得/検出実走/初回offlineは未実施。

host全32試験は31成功・1環境エラー。失敗は既存symlink拒否試験の準備段で
Windowsがsymlink作成を拒否（WinError1314）。assertを変更せず、skipや成功にしない。
集計18、権限6の単独suiteは成功。HTML集計の合成8試験も成功。

## Play版の通信境界・実写前ゲート（2026-11-16確認）

一次資料 [ML Kit Terms & Privacy](https://developers.google.com/ml-kit/terms) は、
画像等の入力と処理出力は端末内処理でGoogleへ送らないと説明している。
一方、更新モデル・互換性情報・利用/性能メトリクスの通信はあり得る。

[Android data disclosure](https://developers.google.com/ml-kit/android-data-disclosure) では
診断情報に機種/OS、package/version、遅延、入力形式/解像度、イベント/エラー等を挙げ、
thin（Play）版にはdevice identifierとinstallation identifierを挙げている。
bundled版のinstallation identifierだけの説明と同一ではない。
写真の非送信と、診断情報が一切外に出ないことを混同しない。

本評価実装は同一RGBAから作ったbitmapをInputImage.fromBitmapへ渡し、元URI/ファイル名を
SDKに渡さない。アプリ独自のHTTP/画像送信/顔結果送信は追加しない。結果はapp-privateに
保存し、例外messageをログへ出さない。ModuleInstallの可用性確認だけを行い、
明示的なモデル取得要求は実装していない。SDK初期化providerやIPC、TLS payloadの
実観測を行ったわけではなく、一次資料＋ソース監査に基づく確認である。

現時点ではPlay版をbuildと容量監査だけに使用。モデル取得、実写入力、実機導入、
初回offlineは未実施。実写に使う前に、導入/操作の個別承認とPlay版診断情報の差を明示する。
モデル不在はNOT_RUNだが、可用性APIの失敗や確認後の状態変化はFAILEDになり得る。
いずれも成功した顔なし画像へ変換しない。実機offline動作の合格は主張しない。
