# Research

Decision: 既存診断を4タブへ再配置。Rationale: 全項目の対応表をplanへ保持し情報を削らない。Alternative: 単純な折り畳み継続は今回の指定と異なる。
Decision: source/有効期限は既存exposureDisplayDiagnosticとCaptureSampleの時刻を利用。Rationale: 取得/採用の意味を変更しない。Alternative: UIから要求受付を実測へ昇格しない。
Decision: ログ/能力の原文を保持する。ログの明示的な ACCEPTED / Accepted= トークンを含む行のみ受付の緑で強調し、他の自由文は中立色とする。構造化phaseの意味色と区別し、文字列から制御成功や実測採用を判定しない。
技術は既存のComposeのみ。新依存・外部API調査なし。PNGから寸法を測らずINSTRUCTIONSを正とする。
