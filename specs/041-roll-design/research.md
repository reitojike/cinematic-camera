# Research

Decision: 一覧表示を専用Composableへ分離し、状態と外部操作callbackは元画面に残す。Rationale: 保存/現像の所有を触らず配置だけ変更できる。Alternative: ViewModel/独自repository追加は不要。
Decision: 同一集合の更新では既存tile維持、filter/folder変更のclearは既存通り。Rationale: 別集合や失効した権限のtileを現在の結果に見せない。
Decision: 取得済み件数、日付不明の明示、一覧の試験注記と展開説明。Rationale: モックの総数/日付や品質保証を流用しない。
