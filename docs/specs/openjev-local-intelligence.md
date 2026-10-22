# OpenJev local intelligence

**Status:** implementation integrated and tested with actual IME touches on an API 35
x86_64 emulator. Genuine offline ONNX inference and delayed automatic replacement work.
The opt-in terminal-vowel policy passed a separate held-out synthetic evaluation.
Broader neural automatic correction and ARM64 phone performance remain unvalidated.

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

The published FP16 graph is 303,785,047 bytes. Its SHA-256 is
`4db28305590c714e33c2cacb75a57ec941c72bb210e1266ba2c7d77e85ddc526`.
Checkpoint, GLiClass and ModernBERT declarations identify Apache-2.0 licensing.
The bundle includes the complete official Apache license, including its appendix.
The license SHA-256 is `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30`.
Source and model attribution are in `scripts/openjev/model-ATTRIBUTION.md`.
The final optional FP16 bundle is 307,382,491 bytes. Its SHA-256 is
`f5e2a5c211d884422d39756e56eb66b57a16ab5c9b9e4daf0d1163f21d47d581`.

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

Use JDK 17, Android SDK platform 37.2 and build-tools 37.0.0. The Termux AAPT2 override does not run on Debian.
The tested environment uses the following paths and memory limits. Adapt the SDK and JDK paths to your host.

```sh
mkdir -p "$HOME/.cache/cleverkeys-openjev/logs" "$HOME/.cache/cleverkeys-openjev/test-scratch"
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME="$HOME/.android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ORT_DISABLE_TELEMETRY=1
export JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$HOME/.cache/cleverkeys-openjev/test-scratch"
export GRADLE_GUARD_XMX=2048m GRADLE_GUARD_METASPACE=512m
export GRADLE_GUARD_LOG="$HOME/.cache/cleverkeys-openjev/logs/gradle-last.log"
bash scripts/gradle-guard.sh assembleDebug assembleDebugAndroidTest runAllTests lintDebug
```

The guard serializes Gradle and bounds memory. ABI APKs are in `build/outputs/apk/debug/`.
The debug application ID is `tribixbite.cleverkeys.debug`.

Acquire the model on the host:

```sh
python3 -m venv ~/.cache/cleverkeys-openjev/venv
~/.cache/cleverkeys-openjev/venv/bin/pip install -r scripts/openjev/requirements.txt
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

After importing the model through settings on an x86_64 emulator, run:

```sh
adb install -r build/outputs/apk/debug/CleverKeys-v2.0.0-x86_64.apk
adb install -r build/outputs/apk/androidTest/debug/CleverKeys-debug-androidTest.apk
adb shell am instrument -w -e class tribixbite.cleverkeys.OpenJevImeInstrumentedTest \
  tribixbite.cleverkeys.debug.test/androidx.test.runner.AndroidJUnitRunner
```

The test fixture enables and selects the IME, launches the debug editor, and restores preferences and the previous IME afterward.

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

## Observed Android runtime measurements

The API 35 `codex_phone` emulator uses `sdk_gphone64_x86_64`/`ranchu`, KVM,
SwiftShader and 4 GB RAM. The two-thread CPU session loaded in 3,094.5 ms; cold
end-to-end inference took 3,492.0 ms. Twelve warm samples measured p50 223.5 ms
and p95 324.1 ms. RSS rose from 240,144 KiB to a measured peak of 1,091,272 KiB;
loaded PSS was 978,650 KiB. Process CPU time for the benchmark workload was
8,920 ms. Android FP16 logits differed from the FP32 reference by at most
0.0088314 in the exported numeric parity test (tolerance 0.03).

These measurements exceed the ideal 75 ms warm p95 target and show substantial
memory overhead. They establish working emulator inference, not phone latency,
battery life, or sustained power consumption. Keep intelligence optional and use
the bounded worker, exact stale-result guards and baseline fallback under load.

Actual IME touchscreen injection measured 33 disabled samples at p50/p95
47.56/61.83 ms and 222 neural-enabled samples at 45.21/75.21 ms.
The enabled workload included 220 characters of continuous typing and editing.
One last-key-to-verified-replacement sample took 1,466.08 ms, including the idle delay.
These timings include instrumentation scheduling. They do not measure frame latency
or physical touch response. Editor and current-word tracking agreed at all 13 sampled
typing boundaries.

## Completed validation

The final JVM runs passed 2,945 pure tests and 1,156 mock tests.
`lintDebug` passed with zero errors and 229 warnings. Its application-context singleton
warning refers to deliberate process-wide runtime ownership, rather than an Activity reference.
The baseline passed 2,923 pure tests. Its mock suite failed one invalid mocked
`/data/` path assertion, which the implementation corrected before comparison.
Python evaluation-accounting tests passed 15/15.

Final Android runs passed 16/16 actual-IME tests and 47/47 targeted editor, undo,
URL, suggestion, password and accessibility regressions. Separate offline runs passed
2/2 live replacement/undo and disabled-typing cases, plus 1/1 numeric parity case.
The emulator had no active default network before and after these offline runs.
Its prior network configuration was restored afterward.
The runtime suite also passed 7/7, including 12 Python tokenizer golden cases.
The verified model importer and observed candidate evaluator each passed 1/1.

The full unfiltered Android suite was not passed. Its experimental CTC benchmarks
require additional weights unavailable in this checkout. The targeted regression and
OpenJev suites use the real shipped swipe assets and actual local OpenJev weights.
No OnePlus 15 or other ARM64 phone was available. The ARM64 APK was built and its
signature, native library alignment and absence of Internet permission were checked.
