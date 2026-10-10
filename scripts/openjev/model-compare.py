#!/usr/bin/env python3
"""Measure FP32/FP16/experimental INT8 parity on the same synthetic candidate fixtures."""
import argparse,json,pathlib,time,gc
import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer
from model import prompt,digest,CHECKPOINT,REVISION,HASHES

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('fixtures',type=pathlib.Path)
p.add_argument('--binary',action='store_true',help='Observe each alternative separately versus literal KEEP using FP16')
p.add_argument('--baseline-export',type=pathlib.Path,help='Use actual Android WordPredictor baseline candidates instead of predeclared alternatives')
p.add_argument('--split',choices=['tune','test'],help='Restrict actual scoring to one split, e.g tune before freezing thresholds')
p.add_argument('--cache',type=pathlib.Path,default=pathlib.Path.home()/'.cache/cleverkeys-openjev/model-source')
p.add_argument('--output',type=pathlib.Path,default=pathlib.Path.home()/'.local/share/cleverkeys-openjev/output/model-parity.json')
a=p.parse_args()
fixtures=[json.loads(x) for x in a.fixtures.read_text().splitlines() if x]
if a.split: fixtures=[x for x in fixtures if x['split']==a.split]
baselines={x['id']:x for x in map(json.loads,a.baseline_export.read_text().splitlines())} if a.baseline_export else None
tokenizer=Tokenizer.from_file(str(a.cache/'tokenizer.json'))
if a.binary:
 options=ort.SessionOptions();options.intra_op_num_threads=2;options.inter_op_num_threads=1
 session=ort.InferenceSession(str(a.cache/'model_fp16.onnx'),options,providers=['CPUExecutionProvider'])
 rows=[]
 for x in fixtures:
  baseline=baselines.get(x['id']) if baselines is not None else None
  if baselines is not None:
   if baseline is None or baseline['prefix']!=x['prefix'] or baseline['original']!=x['original']: raise ValueError('Baseline export input mismatch')
   candidate=baseline.get('baseline_candidate')
   candidates=[candidate] if candidate and candidate!=x['original'] else []
  else: candidates=x['candidates'][1:]
  for candidate in candidates:
   labels=['the final typed word should remain '+x['original'],'the intended final typed word is '+candidate]
   ids=np.array([tokenizer.encode(prompt(x['prefix']+x['original'],labels)).ids],dtype=np.int64)
   started=time.perf_counter();scores=session.run(None,{'input_ids':ids,'attention_mask':np.ones_like(ids)})[0][0,:2]
   rows.append(dict(id=x['id'],split=x['split'],original=x['original'],candidate=candidate,logits=scores.tolist(),replace_minus_keep=float(scores[1]-scores[0]),latency_ms=(time.perf_counter()-started)*1000))
 artifact=dict(model_identity=CHECKPOINT+'@'+REVISION,model_sha256=digest(a.cache/'model_fp16.onnx'),fixture_sha256=digest(a.fixtures),tokenizer_sha256=digest(a.cache/'tokenizer.json'),prompt_schema='KEEP: the final typed word should remain {original}; REPLACE: the intended final typed word is {candidate}; runtime It is prefix',rows=rows)
 if a.baseline_export: artifact['baseline_export_sha256']=digest(a.baseline_export)
 if a.split: artifact['scored_split']=a.split
 a.output=a.output.with_name('model-binary-observations.json')
 a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(artifact,indent=2)+'\n')
 print(str(a.output),len(rows),'actual binary inferences')
 raise SystemExit(0)
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
