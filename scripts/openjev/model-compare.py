#!/usr/bin/env python3
"""Measure FP32/FP16/experimental INT8 parity on the same synthetic candidate fixtures."""
import argparse,json,pathlib,time,gc
import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer
from model import prompt,digest

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('fixtures',type=pathlib.Path)
p.add_argument('--cache',type=pathlib.Path,default=pathlib.Path.home()/'.cache/cleverkeys-openjev/model-source')
p.add_argument('--output',type=pathlib.Path,default=pathlib.Path.home()/'.local/share/cleverkeys-openjev/output/model-parity.json')
a=p.parse_args()
fixtures=[json.loads(x) for x in a.fixtures.read_text().splitlines() if x]
tokenizer=Tokenizer.from_file(str(a.cache/'tokenizer.json'))
inputs=[]
for x in fixtures:
 labels=['the final typed word should remain '+x['candidates'][0]]+['the intended final typed word is '+v for v in x['candidates'][1:]]
 ids=np.array([tokenizer.encode(prompt(x['prefix']+x['original'],labels)).ids],dtype=np.int64)
 inputs.append((ids,len(labels)))
results={}
for name in ['model.onnx','model_fp16.onnx','model_int8.onnx']:
 options=ort.SessionOptions();options.intra_op_num_threads=2;options.inter_op_num_threads=1
 started=time.perf_counter();session=ort.InferenceSession(str(a.cache/name),options,providers=['CPUExecutionProvider']);load=(time.perf_counter()-started)*1000
 rows=[]
 for x,(ids,count) in zip(fixtures,inputs):
  started=time.perf_counter();scores=session.run(None,{'input_ids':ids,'attention_mask':np.ones_like(ids)})[0][0,:count];elapsed=(time.perf_counter()-started)*1000
  rows.append(dict(id=x['id'],split=x['split'],logits=scores.tolist(),top1=x['candidates'][int(scores.argmax())],latency_ms=elapsed,correct=x['candidates'][int(scores.argmax())] in x['accepted']))
 results[name]=dict(fixture_sha256=digest(a.fixtures),sha256=digest(a.cache/name),size=(a.cache/name).stat().st_size,load_ms=load,rows=rows)
 del session;gc.collect()
reference=results['model.onnx']['rows']
for name,model in results.items():
 diffs=[max(abs(np.array(row['logits'])-np.array(ref['logits']))) for row,ref in zip(model['rows'],reference)]
 model['summary']=dict(top1_agreement=sum(row['top1']==ref['top1'] for row,ref in zip(model['rows'],reference)),total=len(fixtures),max_logit_difference=float(max(diffs)),mean_max_logit_difference=float(np.mean(diffs)),test_accuracy=sum(row['correct'] for row in model['rows'] if row['split']=='test')/sum(row['split']=='test' for row in model['rows']),p50_ms=float(np.percentile([x['latency_ms'] for x in model['rows']],50)),p95_ms=float(np.percentile([x['latency_ms'] for x in model['rows']],95)))
a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(results,indent=2)+'\n')
print(json.dumps({name:x['summary'] for name,x in results.items()},indent=2))
