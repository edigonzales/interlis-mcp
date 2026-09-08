#!/usr/bin/env python3
"""Explicit fixture-maintenance command, never invoked during a scored run.
Uses the offline compiler inspector, not MCP. Does not approve/activate a suite.
"""
import hashlib, json, pathlib, shutil, subprocess, os
ROOT = pathlib.Path(__file__).resolve().parents[3]
SUITE = ROOT / 'evals/constraint-reconstruction/v2'
JAVA = pathlib.Path(os.environ.get('JAVA_HOME', '/Users/stefan/.sdkman/candidates/java/current')) / 'bin/java'
CP = (ROOT / 'build/benchmark/classpath.txt').read_text()
REPOS = 'https://geo.so.ch/models;https://models.geo.admin.ch;https://models.kgk-cgc.ch/;https://vsa.ch/models;https://405.sia.ch/models;https://models.interlis.ch'
def save(p, data):
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
def inspect(path, repos, selector=None):
    args = [str(JAVA), '-cp', CP, 'ch.so.agi.mcp.benchmark.CompilerEvidence', 'inspect', str(path), repos]
    if selector: args.append(selector)
    p = subprocess.run(args, capture_output=True, text=True)
    if p.returncode: raise RuntimeError(p.stderr[-3000:])
    result = json.loads(p.stdout)
    if not result['valid']: raise RuntimeError((path, result))
    return result
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
if __name__ == '__main__':
    if (SUITE/'manifest.json').exists() and json.loads((SUITE/'manifest.json').read_text()).get('status')=='APPROVED':
        raise SystemExit('Refusing to rewrite APPROVED suite')
    files = {}
    for case in sorted((SUITE/'public').iterdir()):
        data = inspect(case/'model.ili', REPOS)
        for source in data['dependencies']:
            p = pathlib.Path(source); dest = SUITE/'dependencies'/p.name
            dest.parent.mkdir(exist_ok=True)
            if dest.exists() and sha(dest) != sha(p): raise RuntimeError('Conflicting dependency basename: '+str(p))
            shutil.copyfile(p, dest)
            files[p.name] = {'path': p.name, 'sha256': sha(dest), 'source': source}
        print(case.name, 'dependency closure captured', flush=True)
    save(SUITE/'dependencies/manifest.json', {'schemaVersion': 1, 'files': sorted(files.values(), key=lambda x:x['path'])})
    repos = str(SUITE/'dependencies')
    for case in sorted((SUITE/'oracle').iterdir()):
        exp = json.loads((case/'expected.json').read_text())
        original = exp['originalConstraintSelector']
        selector = original if '.' in original else exp['gold']['contextFqn']+'.'+original
        result = inspect(case/'gold-model.ili', repos, selector)
        save(case/'compiler-gold.json', {k: result[k] for k in ['valid', 'ast', 'astComplete']})
        inspect(SUITE/'public'/case.name/'model.ili', repos)
        exp['schemaVersion'] = 2
        exp['gold'] = {'contextFqn': result['ast']['contextFqn'], 'astComplete': True, 'artifact': 'compiler-gold.json'}
        exp['astComparison'] = {'orderedOperands': True, 'ignore': ['constraintName','sourceLine','pureGrouping'], 'unknownEquivalence': 'NOT_PROVEN'}
        exp['acceptedBoundaryStatuses'] = ['EXTERNAL_FUNCTION_SEMANTICS_REQUIRED', 'PROOF_INCOMPLETE'] if case.name.startswith('N') else []
        save(case/'expected.json', exp)
        print(case.name, 'offline public and gold compile; complete independent AST', flush=True)
