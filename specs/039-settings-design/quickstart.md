# Quickstart / 検証計画

以下を実装の検証手順とする。未実行の条件は合格扱いにしない。

1. 専用worktreeのstatus/最新main/並行作業を確認する。RAW試作に触れず、負荷が重なるビルドやAVD起動を避ける。
2. `.\gradlew.bat :app:lintDebug :app:testDebugUnitTest :app:assembleDebug --no-daemon` と `git diff --check`。結果と環境失敗/未実行を区別。
3. 412×924dp相当でSTILL/CINE設定の全項目、非対応、能力未取得、再接続、録画/処理、リセット確認を表示。寸法は指示書で確認し、PNGの数値/ログを製品へ入れない。
4. overlay開閉と選択でpreview寸法/画角不変。空白タップとscrollが背後AF/AEに流れない。閉じる/Back/診断戻りを確認。
5. 同値ON/OFFで反転しない。AE/WB条件、busy/復旧時のguardが従来と一致。取消は無変更、全リセットは6項目が従来通り。
6. 横持ち・拡大文字でも本文/管理操作に到達できる。ブラウザとAndroidの字幅/行高差、縦配置固定と文字回転を別途確認。横PNGを単純な横画面完成図として扱わない。
7. 変更一覧/秘密除外確認後、A単独のDraft PR。必須CI/canonical review/独立レビュー/未解決会話を確認し、mergeは別承認。

Pixel実機の見え方・操作感は本人確認まで未検証。合成UI/AVDの成功でRAW/24MP性能・process deathを検証したとしない。
