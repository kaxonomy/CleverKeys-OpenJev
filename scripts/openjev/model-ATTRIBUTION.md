The optional OpenJev model bundle uses `heman10x/rlcd-modernbert-151m`, revision
`8af2496eb63c7fa66d7d234e1f62629380030eb4` (Apache-2.0 model card). The published
ONNX bytes are used directly; they contain actual trained parameters, not LFS pointers.
The independent Android adapter follows the GLiClass uni-encoder formatting contract.

Model author: Heman10x. Upstream implementation:
https://github.com/Heman10x-NGU/Verdict-open-jev
(commit `30f15564821626ca5c1ad5b2638c4eb7078787dd`, Apache-2.0).
Base model: `knowledgator/gliclass-modern-base-v2.0` (Apache-2.0), built on
`answerdotai/ModernBERT-base` (Apache-2.0).
License: [model-LICENSE](model-LICENSE), included in each local bundle.

The upstream banking/general decision calibrator is preserved as provenance. Its
softmax temperatures are not validated probabilities of spelling correctness;
automatic replacement requires an independent held-out keyboard evaluation.
The public Verdict 2.0 repository's 598 MB `model.pt` LFS object returned HTTP 404;
it is not used or silently substituted.

Run `model.py --variant fp16` with its documented pinned Python dependencies to
produce the verified local ZIP. Android accepts only pinned FP32/FP16 artifacts.
INT8 is an explicitly marked experiment: its numeric/ranking parity must be measured
before deployment. The settings importer validates sizes, an exact entry allowlist,
SHA-256, pinned checkpoint identity, and pinned ONNX/tokenizer/calibrator checksums.
The keyboard does not download models and needs no INTERNET permission.

The tokenizer uses NFC, GPT-2 ByteLevel BPE, and CLS/SEP special tokens. Each provided
description becomes `<<LABEL>>It is {description}`, then `<<SEP>>{bounded text}`.
Inputs are int64 `[1, sequence_length]` `input_ids` and `attention_mask` (all ones),
maximum 512 tokens. The model returns float32 `[1,25]`; only the first N logits
corresponding to the N complete labels are consumed. Text is limited to 256 UTF-16
characters and 2–8 descriptions, with no silent truncation. Reserved class markers
are rejected in user input.
