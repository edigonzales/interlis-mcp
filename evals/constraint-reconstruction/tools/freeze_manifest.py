#!/usr/bin/env python3
"""Refresh hashes of the DRAFT v2 suite; never approves or activates it."""
import pathlib
from benchmark import BASE, ROOT, read, write, sha, CASES
suite=BASE/'v2';path=suite/'manifest.json'
if path.exists() and read(path).get('status')=='APPROVED':raise SystemExit('Refusing to rewrite APPROVED suite')
original=read(BASE/'v1/manifest.json')
helpers=sorted((BASE/'tools').glob('*.py'))+[ROOT/'src/benchmark/java/ch/so/agi/mcp/benchmark/CompilerEvidence.java']
manifest={'schemaVersion':2,'suiteVersion':'v2','status':'DRAFT','scoringVersion':'2.0.0','corpus':original['corpus'],
          'caseOrder':CASES,'cases':original['cases'],'dependencyManifest':'dependencies/manifest.json',
          'helperArtifacts':[{'path':str(p.relative_to(ROOT)),'sha256':sha(p)} for p in helpers],
          'artifacts':[{'path':str(p.relative_to(suite)),'sha256':sha(p)} for p in sorted(suite.rglob('*')) if p.is_file() and p!=path and p.name!='.DS_Store']}
write(path,manifest)
print('DRAFT manifest refreshed; approval and activeVersion unchanged')
