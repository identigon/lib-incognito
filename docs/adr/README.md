# Architecture Decision Records

Each ADR captures one significant, hard-to-reverse decision: the **context** that forced it, the
**decision** taken, and its **consequences** (including what was given up). They record *why* the
design is what it is — the reasoning that `SPECIFICATION.md` (the *what*) and `PLAN.md` (the *when*)
don't carry.

Conventions: numbered `NNNN-kebab-title.md`, never renumbered; `Status:` one of
`proposed` / `accepted` / `superseded by ADR NNNN`, with the date. A decision that reverses an ADR
adds a new ADR and marks the old one superseded rather than editing it.

| ADR | Decision |
| :--- | :--- |
| [0001](0001-fabrication-not-k-anonymity.md) | Fabrication, not k-anonymity / l-diversity / t-closeness |
| [0002](0002-two-libraries-two-responsibilities.md) | Two libraries: Alterego fabricates fields, Incognito preserves relationships |
| [0003](0003-declared-distinguishing-flag.md) | Declared `distinguishing` flag, not an automatic cardinality gate |
| [0004](0004-fail-closed-classification.md) | Fail-closed classification (an unclassified column aborts the run) |
| [0005](0005-coherent-temporal-jitter.md) | Coherent temporal jitter keyed on the parent's source id |
| [0006](0006-cyclic-fk-two-pass-load.md) | Cyclic foreign keys via Tarjan SCC + placeholder + 2-pass UPDATE |
| [0007](0007-inherited-attribute-root-ancestor.md) | INHERITED_ATTRIBUTE resolved from the root ancestor, fail-closed |
