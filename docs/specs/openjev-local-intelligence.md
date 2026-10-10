# OpenJev local intelligence

**Status:** implementation and validation in progress. Automatic neural replacement is
disabled until separate tuning and held-out evaluation establish a safe policy.

## Requirements and architecture

Preserve CleverKeys' candidate generator, editor commit/undo behavior, language and
accessibility contracts. Optional intelligence evaluates bounded dictionary candidates
and always includes the literal original as KEEP. It never generates ordinary correction
candidates. Missing, corrupt, unsupported, slow or uncertain inference falls back safely.
All controls default off; disabling intelligence restores the existing pipeline.

Editor metadata is checked before reading text. Password/PIN, numeric/financial,
private/no-personalized-learning and restricted fields bypass intelligence. Unsupported
and mixed-language input bypass candidate decisions. Reliable InputType/action metadata
determines context first; an application package alone cannot determine writing context.
Ambiguous context may use the actual local model. Text is bounded to 256 UTF-16 units,
never logged, uploaded or persisted, and caches live only in the current editor session.

The shared enums represent CHAT, EMAIL, SEARCH, CODE_OR_TERMINAL, FORM,
GENERAL_WRITING and UNKNOWN; FORMAL/CASUAL/UNKNOWN tone; and
HELPFUL/NOT_HELPFUL/UNCERTAIN suggestion policy. Independent model logits are not
calibrated probabilities. A deterministic policy returns KEEP, SUGGEST or REPLACE.
REPLACE remains unavailable in every aggressiveness mode until validated.

Model load/tokenization/inference run on a bounded serialized worker. Stale requests
lose ownership on editor, selection, text, language or configuration changes. Main-thread
publication revalidates the editor identity and exact EditorReadback. Inference cannot
block typing or cause delayed destructive edits. Explicit snippets use existing editor
machinery and are never inserted automatically.

## Model and installation

Use the publicly available `heman10x/rlcd-modernbert-151m` checkpoint, pinned to
`8af2496eb63c7fa66d7d234e1f62629380030eb4`. Validate the published graph and tokenizer
against host reference inference before Android parity tests. Retain attribution and
verify checkpoint licensing. Verdict 2.0 repository visibility is not proof of available
weights. No substitute model or hardcoded inference may be presented as OpenJev.

Host acquisition/conversion creates a checksummed bundle outside Git. The app imports
only through explicit local selection into app-private storage. ZIP entries, sizes,
duplicates, paths and hashes are validated in staging before replacing a working model;
failed imports retain the prior model. Model readiness and errors are visible in settings.
The keyboard has no Internet permission and never downloads models.

## Validation and delivery

Run baseline and integrated pure/mock suites through `scripts/gradle-guard.sh`, then
build matching debug app/test APKs using the Debian SDK and JDK 17. Test actual IME
key interactions on an emulator, including genuine model execution, ordinary typing,
explicit candidate acceptance, undo, field/selection changes, password bypass,
missing-model fallback, offline operation and rapid input. `adb input text` alone
does not establish keyboard inference.

Evaluate identical deterministic fixtures using observed CleverKeys candidates and
actual model scores. Keep tuning and held-out data distinct; report accuracy,
preservation, false automatic corrections, abstention and latency without claiming
improvement unless measured. Record cold/warm load/inference, p50/p95, peak RSS and
artifact sizes. Emulator measurements do not establish OnePlus 15 performance.

All task scratch data and delegated worktrees live under
`~/.cache/cleverkeys-openjev/`; final APKs, model bundle, checksums and concise validation
live under `~/.local/share/cleverkeys-openjev/output/`. Workstreams share a recorded
baseline but have separate worktrees/branches and integrate reviewed commits centrally.
Delivery is a committed, pushed feature branch; no pull request, merge, tag or release.
