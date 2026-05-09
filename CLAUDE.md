# Quality gates

- Public method must not have more than 3 parameters
- Private method must not have more than 4 parameters
- Method must not have more than 20 lines of code
- Boolean parameters are not allowed on public methods, use strategy interfaces instead
- Distinct code paths must encode distinct semantic concerns. Paths that diverge by data shape (cardinality, type,
  container variant, presence of an optional field) without a corresponding semantic difference are layering artifacts;
  collapse them. Bifurcating early because two cases happen to need different APIs downstream is not a semantic split —
  find the level at which the runtime actually observes the difference and bifurcate only there.
- Duplication is judged by content, not just by name or signature. Two methods, two branches, or two factory builders
  with the same body in different scopes are still duplication even when each is small. Extract or unify; an adapter at
  a foreign boundary (e.g. an SDK that doesn't share a base type) is acceptable, ad-hoc parallel rendering is not.
- User-facing failure must be loud and precise. At every contract boundary the user can hit (annotation, DSL, public
  API), invalid or unsupported input must fail at the earliest point that can detect it: compile error if the input is
  structurally checkable at codegen, runtime exception with the user-actionable cause otherwise. Never log-and-skip,
  never fall through to a silent default, never throw `UnsupportedOperationException` from a path the user can reach
  without a clear message naming the concrete remediation.
- Don't reject what the underlying technology supports. Before emitting a compile error or runtime "not supported"
  because *our* implementation can't translate something, verify the underlying tool (JPQL/Hibernate/Spring/JPA/etc.)
  genuinely doesn't support it. If it does, implement the translation; an implementation gap is not a user-facing
  limitation.
- The audit applies to pre-existing code at the same bar as new code. When you fix an issue identified by these gates,
  scan the rest of the surface for the same shape and fix every instance — don't leave older code at a lower bar than
  the change you just made.
- No "conversation via code" – all names, docs, comments msut be readable a year after, without any conversation context
  and still make sense. If you find yourself writing a comment that starts with "this is because..." or "we have to do
  this because..." or referring to some conv point in docs, that's a sign the code itself isn't clear enough. Refactor
  until the code can tell the story on its own, then add a comment if needed to fill in any remaining gaps.
- No `null` returns for cases when "code can't find a value" or "code doesn't know how to handle this" — throw an
  exception instead. `null` is for "the value is explicitly empty," not "I don't know what to do with this input." If
  the method's contract allows for an empty result, return an empty collection or an `Optional.empty()`, but never
  `null`.
- No manual mappings for DTO`s - use mapstruct instead

# Acceptance criteria

- Every requirement is met with zero exceptions. If you are told to do everything – you do everything, not stop after
  Phase 1.

# Flow

- NEVER truncate gradle output