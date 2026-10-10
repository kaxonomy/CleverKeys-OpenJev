# OpenJev local intelligence

**Status:** implementation integrated; final Android validation is in progress. The opt-in
terminal-vowel replacement policy passed a separate held-out synthetic evaluation.
Broader neural automatic correction remains unsupported.

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
REPLACE requires validated policy parameters and explicit opt-in. It waits for at least
one second of quiet typing, then checks exact editor ownership before replacing a word.
The supported policy removes one accidental terminal vowel only when the original has
exactly two matching terminal vowels. Longer emphasis, internal duplicates, transpositions
and other edits remain suggestion-only. The original must be unknown to all enabled
vocabulary stores, the baseline must propose a known candidate, and the neural candidate
must win with a positive raw logit and at least 1.5 separation from KEEP. All gates apply
before a verified editor write. Backspace undo respects the existing user preference;
tapping the original remains an explicit undo action. Unvalidated aggressiveness modes
remain unavailable.

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
Delivery is committed work pushed to the fork's `main` branch with a normal fast-forward.
No pull request, force-push, tag or release is authorized.

## Reproduce on Debian

Use JDK 17 and the existing Android SDK; the Termux AAPT2 override is not suitable
for a Debian host. Set `JAVA_HOME`, `ANDROID_HOME` and `ANDROID_SDK_ROOT` to local
installations, then run `bash scripts/gradle-guard.sh assembleDebug assembleDebugAndroidTest runAllTests`.
The guard serializes Gradle and bounds memory. Set `GRADLE_GUARD_LOG` to the central
scratch directory if retaining diagnostics. ABI APKs are in `build/outputs/apk/debug/`.
The debug application ID is `tribixbite.cleverkeys.debug`.

Acquire the model on the host:

```sh
python3 -m venv ~/.cache/cleverkeys-openjev/venv
~/.cache/cleverkeys-openjev/venv/bin/pip install onnxruntime==1.21.1 onnx==1.17.0 tokenizers==0.21.4 numpy==2.4.6
~/.cache/cleverkeys-openjev/venv/bin/python scripts/openjev/model.py --variant fp16
```

Copy `~/.local/share/cleverkeys-openjev/output/openjev-fp16.zip` to the phone using
USB or local file transfer. In Settings → Local intelligence, select Import local
model bundle and choose the ZIP. The verified bundle is copied into app-private
storage. Enable intelligence and the desired controls explicitly. Installed means
the bundle exists; Ready means real finite inference has succeeded. A missing model
leaves normal typing available. Remove local model releases the session and weights.
No network access from the keyboard is needed for any step.

For instrumentation, install the matching x86_64 app APK and debug AndroidTest APK,
then run the AndroidJUnitRunner with a specific `-e class` selection. The local model
installation test consumes `cache/openjev-bundle.zip` staged through `adb shell run-as
tribixbite.cleverkeys.debug`; this invokes the same verified importer as settings.
Run `OpenJevRuntimeInstrumentedTest` for token/inference parity and memory/latency;
`OpenJevImeInstrumentedTest` uses real screen touches on accessible IME key bounds.
The native candidate exporter accepts `-e openjevFixtures openjev/repeated-fixtures.jsonl`.
Synthetic exports are test data and must never be enabled for ordinary editor text.

## Observed correction evidence

The general one-edit policy failed its original 30-case held-out set: zero correct neural
replacements and one false replacement. It is disabled. Its internal-consonant failure
(`lette` → `lete`) is a permanent regression control. A new terminal-vowel cohort was
frozen before observing model outputs. Tuning revealed intentional triple-vowel emphasis;
the structural rule was refined on tuning data to exactly two terminal vowels, then frozen
before inspecting the new held-out set. The raw margin stayed 1.5 throughout.

On 29 fresh held-out synthetic dictionary-stage cases, using actual Android-generated
candidate slates and observed pinned FP16 ONNX scores:

| Metric | CleverKeys baseline | Restricted OpenJev policy |
|---|---:|---:|
| Correct automatic replacements | 12 | 7 |
| False automatic replacements | 3 | 0 |
| Correctly preserved preservation cases | 14/17 | 17/17 |
| Correction precision | 80% | 100% |
| Correction recall | 100% | 58.3% |
| Candidate top-1 accuracy | 11/29 | 21/29 |
| KEEP frequency | 14/29 | 17/29 |
| Useful suggestion proxy | not measured | 5/5 |

These small synthetic results support a narrow opt-in capability. They do not establish
broad spelling quality, calibrated correctness probabilities, real user acceptance, or
complete live-IME false-correction rates. Missing/late model results retain ordinary
CleverKeys fallback. Exact editor transaction behavior requires the separate live tests.

Published FP16 versus FP32 parity: 46/46 fixture top-1 agreements, maximum raw-logit
difference 0.01263. Experimental dynamic INT8 agreed on 43/46 with maximum difference
1.5267 and is rejected by the importer. FP16 is the shipped optional bundle; it reduces
file size but may expand to a large CPU session in memory. No OnePlus 15 performance
measurement is available from the current x86_64 emulator.
