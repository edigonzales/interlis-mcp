#!/usr/bin/env python3
"""Validate three complete native acceptance rounds; never changes the active suite/task."""
import argparse, pathlib
from benchmark import read, write, require, sha, IDENTITY, CASES
p=argparse.ArgumentParser();p.add_argument('runs',nargs=3,type=pathlib.Path);p.add_argument('--output',required=True,type=pathlib.Path);a=p.parse_args()
runs=[r.resolve() for r in a.runs];require(len(set(runs))==3,'Three distinct runs required')
signatures=[];identities=[];totals=[];suite_hashes=[]
for run in runs:
    state=read(run/'run.json');require(state['status']=='COMPLETE','Incomplete acceptance round')
    require(state['clientMode']=='CODEX_MANAGED_MCP' and state['manualMcpProcessesStarted']==0,'Invalid client mode')
    inventory=read(run/'artifacts.sha256.json');require(all(sha(run/path)==h for path,h in inventory.items()),'Acceptance artifact hash mismatch')
    require(read(run/'score/final-score.json')['status']=='VALID','Invalid score')
    identities.append({k:read(run/'runtime-identity.json')[k] for k in IDENTITY});suite_hashes.append(state['suiteManifestSha256'])
    signatures.append([{k:read(run/'mcp-reference'/cid/'score.json').get(k) for k in ['caseId','score','authoringStatus','candidateCompiles','proofVerified','astEquivalent','noCollateralChanges','requestSha256']} for cid in CASES])
    totals.append(read(run/'score/final-score.json')['lanes'])
require(identities[0]==identities[1]==identities[2],'Runtime changed between rounds')
require(suite_hashes[0]==suite_hashes[1]==suite_hashes[2],'Suite changed between rounds')
require(signatures[0]==signatures[1]==signatures[2],'MCP reference results are not stable')
write(a.output,{'schemaVersion':2,'status':'ACCEPTED','runs':list(map(str,runs)),'runtimeIdentity':identities[0],
 'suiteManifestSha256':suite_hashes[0],'allRoundScores':totals,'mcpReferenceStable':True,
 'endToEndPositiveRange':[min(t['end-to-end']['positive']['earned'] for t in totals),max(t['end-to-end']['positive']['earned'] for t in totals)],
 'endToEndBoundaryRange':[min(t['end-to-end']['boundary']['earned'] for t in totals),max(t['end-to-end']['boundary']['earned'] for t in totals)]})
print('All three rounds accepted; all End-to-End scores retained. Activation is a separate step.')
