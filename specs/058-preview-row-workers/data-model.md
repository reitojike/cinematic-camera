# Data model
PreviewPixelExecution: one or two workers, cancellation predicate, scoped lifetime. Ranges own scratch and disjoint output indices. A failed/cancelled invocation never returns its partial image. Closed execution cannot submit new work. No stored user data or public API.
