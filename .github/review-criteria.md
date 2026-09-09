# Review criteria

Review the change against CLAUDE.md and
docs/superpowers/specs/2026-09-05-ecommerce-architecture-design.md.

Check in this order, and report only what you can point at a line for:

1. Standing rule violations: forbidden stereotypes, raw query text in Java, layering breaches,
   boundary types named for an operation, lookups not prefixed find, comments in any file, anything
   built that the issue did not ask for.
2. A test-first violation: production code whose behaviour has no failing test behind it.
3. Correctness: a defect that makes the code do the wrong thing for a concrete input.
4. Acceptance criteria in the linked issue that the diff does not satisfy.

Verify claims before making them. Run the build if a claim depends on it. Report nothing if nothing
survives verification, and never pad the review to look thorough. Do not comment on formatting.
