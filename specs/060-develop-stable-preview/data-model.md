# 表示状態の契約

表示BitmapはUIのみが所有し、採用結果で置き換えた後に従来のDisposableEffectで解放する。新しい画素コピーは作らない。
同一原本・foreground・調整再要求: 前表示を残す。CHECKING/RENDERINGでは更新中を表示し比較・保存は無効。
DISPLAYED: 新しい確定表示と既存の保存条件を使う。
BLOCKED/別原本/背景/アクセス変更: 表示を破棄。古い要求の結果は既存ticketで拒否。
