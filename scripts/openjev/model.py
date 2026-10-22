#!/usr/bin/env python3
"""Pinned host-only OpenJev acquisition, optional INT8 experiment, parity and local bundle creation.

python3 -m venv ~/.cache/cleverkeys-openjev/venv
~/.cache/cleverkeys-openjev/venv/bin/pip install -r scripts/openjev/requirements.txt
~/.cache/cleverkeys-openjev/venv/bin/python scripts/openjev/model.py --variant fp16
No Android network permission or runtime download is needed.
"""
import argparse
import hashlib
import json
import pathlib
import time
import resource
import platform
import shutil
import urllib.request
import zipfile

CHECKPOINT = "heman10x/rlcd-modernbert-151m"
REVISION = "8af2496eb63c7fa66d7d234e1f62629380030eb4"
SOURCE = "https://huggingface.co/" + CHECKPOINT + "/resolve/" + REVISION + "/"
HASHES = {
    "model.onnx": "4ae01f822538b000fa0e55859d4b3e6b40871d860149397e8784428b2a42ee5e",
    "model_fp16.onnx": "4db28305590c714e33c2cacb75a57ec941c72bb210e1266ba2c7d77e85ddc526",
    "tokenizer.json": "8bb449eb0c037aae44115b65905bb339b8f3f74eb37067c19127feb3c0755723",
    "calibrator.json": "af2a876993148efa0726b6ccf710fe2303897d20c0ce8c7c9036eb50f64d23de",
}


def digest(path):
    with open(path, "rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def acquire(directory, name):
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / name
    if not path.exists():
        staging = path.with_suffix(path.suffix + ".partial")
        urllib.request.urlretrieve(SOURCE + name, staging)
        staging.replace(path)
    if name in HASHES and digest(path) != HASHES[name]:
        raise ValueError("Checkpoint checksum mismatch: " + name)
    return path


def prompt(text, descriptions):
    if len(text.encode("utf-16-le")) // 2 > 256 or not 2 <= len(descriptions) <= 8 or any(not 1 <= len(x.encode("utf-16-le")) // 2 <= 160 for x in descriptions):
        raise ValueError("Input outside bounded Android contract")
    if any("<<LABEL>>" in x or "<<SEP>>" in x for x in [text] + descriptions):
        raise ValueError("Reserved token in input")
    return "".join("<<LABEL>>It is " + label for label in descriptions) + "<<SEP>>" + text


def infer(path, tokenizer, text, descriptions, runs=8):
    import numpy as np
    import onnxruntime as ort
    ids = np.asarray([tokenizer.encode(prompt(text, descriptions)).ids], dtype=np.int64)
    if ids.shape[1] > 512:
        raise ValueError("Token budget exceeded")
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    started = time.perf_counter()
    session = ort.InferenceSession(str(path), options, providers=["CPUExecutionProvider"])
    load_ms = (time.perf_counter() - started) * 1000
    samples = []
    for _ in range(runs):
        started = time.perf_counter()
        logits = session.run(None, {"input_ids": ids, "attention_mask": np.ones_like(ids)})[0][0, :len(descriptions)]
        samples.append((time.perf_counter() - started) * 1000)
    if not np.isfinite(logits).all():
        raise ValueError("Non-finite inference result")
    return dict(token_ids=ids[0].tolist(), logits=logits.tolist(), load_ms=load_ms,
                cold_ms=samples[0], warm_p50_ms=float(np.percentile(samples[1:], 50)),
                warm_p95_ms=float(np.percentile(samples[1:], 95)),
                peak_rss_kib=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,
                hardware=platform.machine(), os=platform.platform(), cpu_threads=2)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--variant", choices=["fp32", "fp16", "int8-experiment"], default="fp16")
    parser.add_argument("--cache", type=pathlib.Path, default=pathlib.Path.home()/".cache/cleverkeys-openjev/model-source")
    parser.add_argument("--output", type=pathlib.Path, default=pathlib.Path.home()/".local/share/cleverkeys-openjev/output")
    args = parser.parse_args()
    from tokenizers import Tokenizer
    tokenizer_file = acquire(args.cache, "tokenizer.json")
    calibrator = acquire(args.cache, "calibrator.json")
    name = "model.onnx" if args.variant != "fp16" else "model_fp16.onnx"
    model = acquire(args.cache, name)
    if args.variant == "int8-experiment":
        from onnxruntime.quantization import quantize_dynamic, QuantType
        model = args.cache / "model_int8.onnx"
        if not model.exists():
            quantize_dynamic(str(args.cache / name), str(model), weight_type=QuantType.QInt8,
                             per_channel=True, op_types_to_quantize=["MatMul", "Gemm"])
    text = "Hey, see you soon!"
    descriptions = ["casual chat", "formal email"]
    proof = infer(model, Tokenizer.from_file(str(tokenizer_file)), text, descriptions)
    proof.update(checkpoint=CHECKPOINT, revision=REVISION, variant=args.variant,
                 text=text, descriptions=descriptions, model_sha256=digest(model), model_size=model.stat().st_size)
    args.output.mkdir(parents=True, exist_ok=True)
    # Only synthetic reference text is retained; keyboard text must never be exported.
    (args.output / "model-reference.json").write_text(json.dumps(proof, indent=2) + "\n")
    if args.variant == "int8-experiment":
        print(json.dumps(proof))
        print("Experimental quantization is not accepted by Android's verified importer.")
        return
    license_file = pathlib.Path(__file__).with_name("model-LICENSE")
    files = {"model.onnx": model, "tokenizer.json": tokenizer_file, "calibrator.json": calibrator, "LICENSE": license_file}
    manifest = dict(format_version=1, checkpoint=CHECKPOINT, revision=REVISION, quantization=args.variant,
                    license="Apache-2.0", files={name: dict(size=p.stat().st_size, sha256=digest(p)) for name,p in files.items()})
    output = args.output / ("openjev-" + args.variant + ".zip")
    staging = output.with_suffix(".zip.partial")
    with zipfile.ZipFile(staging, "w", compression=zipfile.ZIP_STORED) as bundle:
        # Fixed timestamps make checksums reproducible without buffering model bytes in RAM.
        bundle.writestr(zipfile.ZipInfo("manifest.json", (1980,1,1,0,0,0)), json.dumps(manifest, sort_keys=True))
        for name,p in files.items():
            info = zipfile.ZipInfo(name, (1980,1,1,0,0,0))
            with bundle.open(info, "w", force_zip64=True) as destination, p.open("rb") as source:
                shutil.copyfileobj(source, destination, 65536)
    staging.replace(output)
    (args.output / (output.name + ".sha256")).write_text(digest(output) + "  " + output.name + "\n")
    print(str(output))
    print(json.dumps({k:v for k,v in proof.items() if k not in ("token_ids", "text", "descriptions")}))


if __name__ == "__main__":
    main()
