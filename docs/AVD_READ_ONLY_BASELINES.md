# ATD snapshot・GPU cold boot の task 別 read-only 運用

Issue #192 / #290。2026-11-15 の本人指示に基づく、ローカル試験の運用。ATD の非GPU試験と AOSP のGPU試験は下記の範囲を分ける。
既存 GMD の定義・必須 CI・製品コードを変えず、毎taskでAVDを作らずに、準備済み基準を共有する。
本書の成立済み範囲では、[MANAGED_DEVICES](MANAGED_DEVICES.md) のローカル GMD 固定2枠より本書を優先する。
GMD による自動管理そのものは廃止しない。GitHub runner の ATD 検証は別作業（#430）。

## 成立済みの範囲

|項目|構成・用途|
|---|---|
|system image|`system-images;android-37.0;google_atd;x86_64` revision 7、extension 22|
|emulator|37.2.13（build 16740729）|
|端末|Pixel 9a、1080×2424、420dpi、実割当 4096MiB / 2 vCPU|
|描画|既定の非GPU試験用設定。実ログは OpenGL ES Translator / Google SwiftShader / GLES 3.0|
|対象|SystemUI・IME・camera実機・GPU描画に依存しない、合成入力のCompose/View・Android API試験|
|同時数|2台から始め、負荷を確認して最大3台。速度比較は単独|

ATD は hardware rendering が無効。画素確認は software Canvas 等、試験が元から採用する方式に限る。
画面合成、GPU compute、Pixel の速さ・RAM・camera品質の保証には使わない。
API 37 ATD は SDK で提供され実走確認した構成であり、公式ATDページの記載範囲と混同しない。

## 基準の準備と更新

1. 開発用ドライブに専用基準AVDを一度作る。既存AVD/data、SDK/JDK、認証状態は変更しない。
   `ANDROID_AVD_HOME` の既存ディレクトリと、AVD `.ini` に記録される実体pathを確認する。
   task名ごとのAVD全コピーは作らない。個人用の絶対pathをGitに記録しない。
2. **基準を作成・更新・移動する前に全read-only instanceを停止する。**
   `adb devices` と実process（Windowsは `emulator.exe`、`qemu-system-x86_64.exe`、
   `qemu-system-x86_64-headless.exe`）を照合し、使用担当と排他を取る。
3. 通常書込みモード、`-no-snapshot-load -no-snapshot-save -no-window -no-audio -no-boot-anim`
   で起動する。検証時は `-cores 2 -memory 4096`。以後の復帰も同じ設定にする。
4. 起動完了、system_server PID安定、ANR・framework再起動なしを確認する。
   検証時は起動待ち最大6分、起動後90秒のPID安定を確認した。上限超過や失敗を無制限再試行しない。
5. snapshot復帰の判別用に、保存直前のboot UUIDをguestに残す。
   これは試験専用AVDの `/data/local/tmp` だけで行う。

   ```powershell
   adb -s $env:ANDROID_SERIAL shell 'cat /proc/sys/kernel/random/boot_id > /data/local/tmp/dot-snapshot-boot-id'
   adb -s $env:ANDROID_SERIAL shell cat /data/local/tmp/dot-snapshot-boot-id
   adb -s $env:ANDROID_SERIAL emu avd snapshot save prepared_v1
   adb -s $env:ANDROID_SERIAL emu avd snapshot list
   ```

   UUIDの取得が成功し、snapshot save が `OK`、一覧に `prepared_v1` があることを確認する。
   `KO` を成功扱いしない。`-no-snapshot-save` は終了時の自動保存を抑制するが、この明示保存は実行可能。
6. 自分のserialだけを停止し、process終了を確認する。停止要求の受付と終了完了は別。
   更新後はread-onlyで復帰し、下記gateと隔離を再確認してから担当へ渡す。

基準にユーザー写真・credentials・task固有のAPKや保存データは入れない。
Emulator/image/AVD設定の更新でsnapshotは無効化され得る。基準を更新したら検証記録も更新する。

## task ごとの起動・実走

- 担当、基準名、偶数のconsole port、serial、worktree、出力先を先に予約する。
- 各taskは別worktree、別port、別出力ディレクトリ。`ANDROID_SERIAL` を設定し、adbは常に `-s` を付ける。
- 起動例（変数は担当が確認した値。`Start-Process` は非表示で実行する）：

  ```powershell
  $env:ANDROID_SERIAL = "emulator-$port"
  Start-Process $emulator -WindowStyle Hidden -ArgumentList @(
    '-avd', $baseline, '-port', $port, '-cores', 2, '-memory', 4096,
    '-snapshot', 'prepared_v1', '-read-only', '-no-snapshot-save',
    '-no-window', '-no-audio', '-no-boot-anim'
  )
  ```

- 起動後 `sys.boot_completed=1` を確認。保存したUUIDと現在の `/proc/sys/kernel/random/boot_id`
  を読み、両方が有効なUUIDで完全一致しなければ停止する。snapshot欠落・cold bootへのfallbackを黙認しない。
- system_server安定とANRなしも確認。snapshotに保存された履歴と今回の異常を区別し、
  基準自体が不健全なら作り直す。履歴を単に消して合格にしない。
- emulator stdoutはbufferされ、snapshot復帰成功行が停止後に初めて見える場合がある。
  liveではUUIDの一致を根拠にし、終了時にflushされた復帰ログも保全する。欠測を成功とみなさない。
- 自分のworktreeでhead/APK hashを記録し、対象classと安全引数を明示してinstall/instrumentする。
  既定の全androidTestを無条件実行しない。実機へ同じ引数を流用しない。
- 完了後 `adb -s $env:ANDROID_SERIAL emu kill`。自分のprocessが消えたら枠を返却する。
  別担当のinstanceを止めたり、全体killや `cleanManagedDevices` を実行したりしない。

## 負荷とストレージ

- 2台でRAM・CPU・ディスク空き・guest ANRを確認し、余裕がある場合のみ3台目を追加する。
  実行中も定期確認し、余裕がなくなる場合は新規起動を止め、担当間で順次実行へ戻す。
  GPUを混ぜた並列負荷はこのATD確認だけで合格にしない。
- 速度計測・前後比較は他のAVD試験と重ねない。並列実走の所要時間を性能の採用値にしない。
- read-onlyはディスク使用ゼロではない。基準disk、RAM snapshot、一時差分、ログ、APK、成果物の容量が必要。
  論理ファイル長と実ディスク空きの増減を分けて記録する。空きのある開発ドライブへ配置する。
- read-onlyでもhost側の起動情報、snapshot利用metadata、qcow2のmtimeが変わり得る。
  guestの状態隔離と、hostの全ファイル不変性は別。稼働中の基準移動・削除・更新は禁止。
- 基準のファイルごとの照合では、`snapshots/prepared_v1/snapshot.pb` は「変わりうるもの」として別扱いにする。文書どおりの起動（`-snapshot prepared_v1 -read-only -no-snapshot-save`）でも、起動のたびに snapshot.pb の内容（SHA-256）と更新日時が変わることを確認済みである（2026-11-26 の確認: 起動前後で boot UUID は一致し、確認した6ファイルのうち snapshot.pb だけが変わり、残りの5ファイルは変わらなかった）。snapshot.pb はスナップショットの利用メタデータであり、その書き換えは guest の開始状態の変化を意味しない。
- したがって、基準の同一性の判定は、snapshot.pb を除くファイルの不変と、boot UUID の完全一致（および system_server の安定・ANR なし）で行う。snapshot.pb のハッシュは記録には残すが、合否の条件にはしない。
- ただし、snapshot.pb 以外のファイル（RAM・ディスクのイメージ、config.ini、hardware.ini 等）が変わった場合は、従来どおり基準が変わったものとして扱い、原因を確認する。Emulator や system image の更新で snapshot の互換性が失われた場合は、基準の更新の手順による。
- 同一snapshotのネットワーク/MAC状態まで独立とは仮定しない。通信依存の試験は別途確認する。

## 2026-11-15 の確認結果

- 同一ATD基準のread-only 3台が復帰し、保存前後のboot UUIDが一致、ANRなし。
- 2台へ別々のguest markerを書き、互いに見えないことを確認。後から起動した3台目にも両markerなし。
- 全instance終了後の新instanceでも両markerは不在。taskのguest書込みは基準へ持ち越されなかった。
- #425 はこの基準で通常7状態14枚と表示回帰2件、横向き・fontScale 1.3の4状態8枚と1件が成功。
- 空きRAMは起動前17,010MiB、2台11,634MiB、3台9,007MiB。CPU標本は27% / 24%。
  短い非GPU workloadでの観測であり、長時間負荷や混合GPU負荷の保証ではない。
- 3台時の開発ドライブ空きは起動前より約322MB減。他の同時作業を含むため、全量をAVD一時差分とは断定しない。
  snapshotのRAMファイルだけで約3GBを使った。ストレージ増ゼロとはしない。

## GPU 用環境との境界

検証済みAOSP API37.0 default x86_64 rev6 / host + GuestAngle,Vulkan は、
最小compute・安全性・既存golden90条件の実比較が成立した通常起動を維持する（#428 / #429）。
環境変更時の合否は [GPU golden](../tools/gpu-golden/README.md) の条件に従い、
全画素一致・他の必須試験成功なら環境名の違いだけでHOLDしない。差は場所と大きさを示して本人判断を得る。

同じGPU条件ではsnapshot saveが `UNSUPPORTED_VK_APP` で不可。
別候補のhost/-Vulkan/-GuestAngleではsnapshot保存・復帰は成功したが、
製品最小試験が `eglCreateContext(ES 3.2) failed: EGL 0x3005` で失敗した。
この候補のGPU90は未実施で、不採用。製品の要求・期待値・goldenを緩めていない。
### GPU read-only 2台の成立条件と実証

2026-11-15、`b82a9cd9` の instance 別起動証跡を使い、同一AOSP基準の cold boot 2台を実証した。
ATDのsnapshot方式とは別で、GPUにはsnapshotを使わない。

- `start_gpu_instance.ps1` と `capture_android.ps1 -InstanceRecord` をセットで導入する。
  [具体的な起動・採取手順](../tools/gpu-golden/README.md)に従い、
  同一AVDの起動登録は全担当がwrapperを使う。基準変更は全instance停止後のみ。
- wrapperは共有 `emu-launch-params.txt` の書換え前に実原本をmutex内で保全する。
  serial、QEMU PID/開始時刻/引数、config hash、boot UUIDを私有記録へ紐付け、
  採取開始・build後・instrumentation後・pull後の照合に使う。欠損・不一致は停止する。
  従来の共有原本だけの採取を、先に起動したinstanceへ無理に使わない。
- 別worktree・console port 5620/5622で各起動・登録。2台目が共有原本を上書きした後でも、
  **両方**で90条件と帯28件の実走・pull・runtime再照合に成功した。
  同一sourceから各worktreeで作ったAPKのhashは異なり、APKのバイト同一を主張しない。個別のAPK証跡で採取した。
  既存goldenとの比較は両方 `pass`、`environmentChanged=true`、全画素差0。基準画像は変更していない。
- run IDは `golden-20261115-010325-b82a9cd9` と `golden-20261115-010400-b82a9cd9`。
  system image、emulator版、GPU/driver等の環境証跡は各runのhost metadataで追跡する。
- 各system_serverが実走前後で不変、ANR 0、boot 1回。guest markerは互いに不可視。
  両停止後にadb一覧空・QEMU process 0を確認した。個別PIDや個人pathは公開文書に含めない。
- 観測時の空きRAMは9,927MiB、CPU標本66%、システムドライブ空き22.15GB、開発ドライブ空き1.48TB。
  他のホスト作業も含む一時点の値であり、速度比較・長時間負荷・3台のGPU実走の保証ではない。
  GPUはまず同時2台までとし、負荷を監視する。性能比較は単独実行を維持する。

実証headから後続mainには #425 の表示変更と #430 の非GPU ATD CI変更が追加されている。
この記録は上記実証headの結果であり、後続の製品変更に必要なGPU回帰を省略する根拠にはしない。
起動証跡の小変更のレビュー・CIが完了してから通常運用へ適用する。

## 公式根拠

- [Multiple AVD instances / Emulator release notes](https://developer.android.com/studio/releases/emulator)
- [Snapshots：保存内容と構成互換性](https://developer.android.com/studio/run/emulator-snapshots)
- [Emulator command line](https://developer.android.com/studio/run/emulator-commandline)
- [Vulkanとsnapshotの制約](https://developer.android.com/studio/run/emulator-troubleshooting#unable-to-create-a-snapshot)
- [Graphics acceleration：現行backend](https://developer.android.com/studio/run/emulator-acceleration)
