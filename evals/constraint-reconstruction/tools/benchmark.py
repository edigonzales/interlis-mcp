#!/usr/bin/env python3
"""Versioned benchmark evidence/score helpers. No MCP client, authoring or network access.
All actual tool calls MUST be made through the native Codex connector.
"""
import argparse, copy, datetime, decimal, difflib, hashlib, json, os, pathlib, re, shutil, subprocess, sys, tempfile, uuid
import xml.etree.ElementTree as ET
ROOT = pathlib.Path(__file__).resolve().parents[3]
BASE = ROOT/'evals/constraint-reconstruction'
CASES = ['P%02d'%i for i in range(1,11)]+['N11','N12']
AUTHOR = ['authorIli'+k+'Constraint' for k in ['Mandatory','Plausibility','Existence','Set','Unique']]+['generateIliConstraintFromDecisionTable']
TOOLS = AUTHOR+['validateIliModel','reviewIliConstraint','generateIliConstraintCases','listConstraintFunctions','resolveConstraintPath']
PREFIX = 'mcp__interlis_mcp_eval__'
IDENTITY = ['buildId','jarSha256','dependencySnapshotSha256','compilerVersion','validatorVersion','ioxIliVersion']
class InvalidEvidence(ValueError): pass

def read(path): return json.loads(pathlib.Path(path).read_text())
def sha(path): return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()
def digest(value): return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=False).encode()).hexdigest()
def now(): return datetime.datetime.now(datetime.timezone.utc).isoformat()
def write(path, value):
    path=pathlib.Path(path);path.parent.mkdir(parents=True,exist_ok=True)
    data=(json.dumps(value,ensure_ascii=False,indent=2)+'\n').encode()
    fd,tmp=tempfile.mkstemp(prefix='.atomic-',dir=path.parent)
    try:
        with os.fdopen(fd,'wb') as f:f.write(data);f.flush();os.fsync(f.fileno())
        os.replace(tmp,path)
    finally:
        if os.path.exists(tmp):os.unlink(tmp)
def require(condition, message):
    if not condition:raise InvalidEvidence(message)
def git(*args, repo=ROOT):return subprocess.check_output(['git','-C',str(repo),*args]).decode()
def provenance(repo=ROOT):
    paths=git('ls-files','-z','--cached','--others','--exclude-standard',repo=repo).split('\0')
    files={p:sha(repo/p) for p in sorted(set(paths)) if p and (repo/p).is_file()}
    return {'commit':git('rev-parse','HEAD',repo=repo).strip(),'status':git('status','--porcelain=v1','--untracked-files=all',repo=repo),
            'diff':git('diff','--binary','HEAD',repo=repo),'files':files,'contentSha256':digest(files)}

def build_sources():
    paths=[p for folder in ['src/main','gradle'] for p in (ROOT/folder).rglob('*') if p.is_file()]
    paths += [ROOT/'build.gradle',ROOT/'settings.gradle']
    return {str(p.relative_to(ROOT)):sha(p) for p in sorted(paths) if p.exists()}

def preflight(run):
    require(read(run/'run.json')['status']=='IN_PROGRESS','Run not active')
    directory=run/'preflight';directory.mkdir(exist_ok=True)
    java=pathlib.Path(os.environ.get('JAVA_HOME','/Users/stefan/.sdkman/candidates/java/current'))/'bin/java'
    version=subprocess.run([str(java),'-version'],capture_output=True,text=True)
    require(version.returncode==0 and re.search(r'version "21[."]',version.stderr),'Java 21 required')
    (directory/'java-version.txt').write_text(version.stdout+version.stderr)
    commands=[['./gradlew','test','check','benchmarkClasspath'],['./gradlew','e2eTest','--tests','ch.so.agi.mcp.StdioE2eTest.initialize_largeToolsList_fullDrain_thenEofShutsDownWithExitCodeZero']]
    records=[]
    for i,command in enumerate(commands):
        log=directory/('gradle-%d.log'%i);started=now()
        with log.open('w') as output:result=subprocess.run(command,cwd=ROOT,stdout=output,stderr=subprocess.STDOUT)
        records.append({'command':command,'startedAt':started,'endedAt':now(),'exitCode':result.returncode,'logSha256':sha(log)})
        write(directory/'commands.json',records)
        require(result.returncode==0,'Preflight test command failed: '+str(command))
        task='test' if i==0 else 'e2eTest'
        shutil.copytree(ROOT/'build/test-results'/task,directory/'test-results'/task,dirs_exist_ok=True)
    write(directory/'tests.json',{'status':'PASS','commands':records})
    return {'status':'AWAITING_NATIVE_CANARY','preflightDirectory':str(directory)}

def bind_runtime(suite,run):
    directory=run/'preflight';require(read(directory/'tests.json')['status']=='PASS','Tests not passed')
    canary_record=verify_preflight_canary(directory)
    raw=read(directory/'native-canary.json');identity=native_data(raw)['runtimeIdentity']
    verify_identity(identity,identity)
    require(identity['dependencySnapshotSha256']==sha(suite/'dependencies/manifest.json'),'Runtime uses different import snapshot')
    require(identity['buildId']==digest(build_sources()),'Running build differs from current sources')
    home=pathlib.Path(os.environ.get('CODEX_HOME',str(pathlib.Path.home()/'.codex')))
    artifact=home/'automations/interlis-mcp-constraint-benchmark/artifacts'/identity['buildId']/identity['jarSha256']
    require(sha(artifact/'interlis-mcp.jar')==identity['jarSha256'],'Immutable JAR missing or changed')
    require(read(artifact/'build.json')['buildId']==identity['buildId'],'Build provenance mismatch')
    native=read(directory/'native-tool-catalog.json')
    verify_native_catalog(native,read(suite/'native-tool-declarations.json'))
    catalog=read(ROOT/'build/benchmark/tool-catalog.json')
    require(catalog==read(suite/'tool-catalog.json'),'Registered schemas differ from approved suite')
    write(run/'tool-catalog.json',catalog);write(run/'runtime-identity.json',identity)
    write(directory/'smoke.json',{'status':'PASS','nativeToolNames':sorted(t['name'] for t in native),'nativeCatalogSha256':sha(directory/'native-tool-catalog.json'),
         'canarySha256':sha(directory/'native-canary.json'),'canaryRecord':canary_record,'runtimeIdentity':identity,'verifiedAt':now()})
    state=read(run/'run.json');state.update(phase='INPUT_QA',jarSha256=identity['jarSha256'],buildId=identity['buildId'],
        promptSha256=sha(suite/'automation-prompt.md'),reconstructorPromptSha256=sha(suite/'reconstructor-prompt.md'),toolCatalogSha256=sha(run/'tool-catalog.json'))
    write(run/'run.json',state);return identity


def verify_preflight_canary(directory):
    request=directory/'native-canary-request.json';result=directory/'native-canary.json';event=directory/'native-canary-event.json'
    require(read(request)=={'tool':PREFIX+'listConstraintFunctions','payload':{}},'Unexpected preflight canary request')
    times=read(event)
    start=datetime.datetime.fromisoformat(times['startedAt'].replace('Z','+00:00'))
    end=datetime.datetime.fromisoformat(times['endedAt'].replace('Z','+00:00'))
    require(start.tzinfo is not None and end.tzinfo is not None and start<=end,'Invalid preflight call timestamps')
    require(request.stat().st_mtime_ns<=result.stat().st_mtime_ns,'Preflight request not persisted before result')
    return {'requestSha256':sha(request),'eventSha256':sha(event),'startedAt':times['startedAt'],'endedAt':times['endedAt']}


def verify_native_catalog(actual, expected):
    require(len(actual)==len(TOOLS) and {t['name'] for t in actual}=={PREFIX+n for n in TOOLS},'Native tool catalog mismatch')
    require(sorted(actual,key=lambda t:t['name'])==sorted(expected,key=lambda t:t['name']),
            'Native tool descriptions/signatures differ from approved suite')


def lane_totals(scores):
    require([s['caseId'] for s in scores]==CASES,'Incomplete or unordered lane')
    return {'positive':{'earned':sum(s['score'] for s in scores[:10]),'total':10},
            'boundary':{'earned':sum(s['score'] for s in scores[10:]),'total':2},
            'structuralReconstruction':{'earned':sum(s.get('candidateCompiles') is True and s.get('astEquivalent') is True and s.get('noCollateralChanges') is True for s in scores[:10]),'total':10},
            'proof':{'earned':sum(s.get('proofVerified') is True for s in scores[:10]),'total':10}}


def verify_suite(suite, draft=False):
    suite=pathlib.Path(suite).resolve();m=read(suite/'manifest.json')
    require(m['caseOrder']==CASES,'Unexpected case order')
    if not draft:require(m['status']=='APPROVED','Suite not APPROVED')
    listed={x['path'] for x in m['artifacts']}
    actual={str(p.relative_to(suite)) for p in suite.rglob('*') if p.is_file() and p.name!='.DS_Store' and p!=suite/'manifest.json'}
    require(listed==actual,'Manifest inventory mismatch')
    for entry in m['artifacts']:
        p=(suite/entry['path']).resolve();require(p.is_relative_to(suite),'Unsafe artifact path')
        require(sha(p)==entry['sha256'],'Suite hash mismatch: '+entry['path'])
    for entry in m['helperArtifacts']:
        p=(ROOT/entry['path']).resolve();require(p.is_relative_to(ROOT),'Unsafe helper path')
        require(sha(p)==entry['sha256'],'Scoring helper hash mismatch: '+entry['path'])
    if not draft:
        prefix=str(suite.relative_to(ROOT))
        require(not git('status','--porcelain=v1','--untracked-files=all','--',prefix),'Suite working tree is dirty')
        tracked=set(git('ls-files','--',prefix).splitlines())
        require(all(str((suite/p).relative_to(ROOT)) in tracked for p in listed|{'manifest.json'}),'Suite file not committed')
        approval=read(suite/'approval.json')
        require(approval.get('status')=='APPROVED' and approval.get('approvedBy')=='USER','Missing explicit requirement approval')
    subprocess.run(['git','-C',str(ROOT.parent/'sogis-interlis-repository'),'cat-file','-e',m['corpus']['commit']+'^{commit}'],check=True,capture_output=True)
    verify_dependencies(suite/'dependencies/manifest.json')
    return m

def verify_dependencies(manifest):
    manifest=pathlib.Path(manifest).resolve();entries=read(manifest)['files']
    for e in entries:
        p=(manifest.parent/e['path']).resolve();require(p.is_relative_to(manifest.parent),'Unsafe dependency path')
        require(sha(p)==e['sha256'],'Dependency hash mismatch: '+e['path'])
    require({str(p.relative_to(manifest.parent)) for p in manifest.parent.rglob('*') if p.is_file() and p!=manifest and p.name!='.DS_Store'}=={e['path'] for e in entries},'Unlisted dependency model')

def schema_errors(value, schema, root=None, path='$'):
    """JSON Schema subset used by the registered MCP catalog; unknown assertions fail closed."""
    if schema is True:return []
    if schema is False:return [path+': prohibited']
    root=root or schema
    if '$ref' in schema:
        ref=schema['$ref'];require(ref.startswith('#/'),'Nonlocal schema ref')
        node=root
        for part in ref[2:].split('/'):node=node[part.replace('~1','/').replace('~0','~')]
        return schema_errors(value,node,root,path)
    known={'type','properties','required','additionalProperties','items','enum','const','oneOf','anyOf','allOf','$defs','definitions','$schema','$id','title','description','default','examples','deprecated','format','minimum','maximum','exclusiveMinimum','exclusiveMaximum','minItems','maxItems','minLength','maxLength','pattern'}
    require(not(set(schema)-known),'Unsupported JSON schema keys: '+str(set(schema)-known))
    errors=[]
    for op in ['oneOf','anyOf','allOf']:
        if op in schema:
            matches=sum(not schema_errors(value,s,root,path) for s in schema[op])
            good=matches==1 if op=='oneOf' else matches>0 if op=='anyOf' else matches==len(schema[op])
            if not good:errors.append(path+': '+op)
    checks={'object':isinstance(value,dict),'array':isinstance(value,list),'string':isinstance(value,str),'boolean':isinstance(value,bool),
            'number':isinstance(value,(int,float)) and not isinstance(value,bool),'integer':isinstance(value,int) and not isinstance(value,bool),'null':value is None}
    if 'type' in schema:
        types=schema['type'] if isinstance(schema['type'],list) else [schema['type']]
        if not any(checks.get(t,False) for t in types):return errors+[path+': type']
    if 'enum' in schema and value not in schema['enum']:errors.append(path+': enum')
    if 'const' in schema and value!=schema['const']:errors.append(path+': const')
    if isinstance(value,dict):
        props=schema.get('properties',{})
        for key in schema.get('required',[]):
            if key not in value:errors.append(path+': missing '+key)
        for key,item in value.items():
            if key in props:errors+=schema_errors(item,props[key],root,path+'.'+key)
            elif schema.get('additionalProperties') is False:errors.append(path+': unexpected '+key)
            elif isinstance(schema.get('additionalProperties'),dict):errors+=schema_errors(item,schema['additionalProperties'],root,path+'.'+key)
    if isinstance(value,list):
        for i,item in enumerate(value):errors+=schema_errors(item,schema.get('items',{}),root,path+'[%d]'%i)
        if len(value)<schema.get('minItems',0) or len(value)>schema.get('maxItems',float('inf')):errors.append(path+': array length')
    if isinstance(value,str):
        if len(value)<schema.get('minLength',0) or len(value)>schema.get('maxLength',float('inf')):errors.append(path+': string length')
        if 'pattern' in schema and not re.search(schema['pattern'],value):errors.append(path+': pattern')
    if checks['number']:
        if value<schema.get('minimum',-float('inf')) or value>schema.get('maximum',float('inf')):errors.append(path+': range')
        if value<=schema.get('exclusiveMinimum',-float('inf')) or value>=schema.get('exclusiveMaximum',float('inf')):errors.append(path+': exclusive range')
    return errors

def compiler(*args):
    java=str(pathlib.Path(os.environ.get('JAVA_HOME','/Users/stefan/.sdkman/candidates/java/current'))/'bin/java')
    cp=(ROOT/'build/benchmark/classpath.txt').read_text()
    process=subprocess.run([java,'-cp',cp,'ch.so.agi.mcp.benchmark.CompilerEvidence',*map(str,args)],capture_output=True,text=True)
    require(process.returncode==0,'Compiler evidence failed: '+process.stderr[-2000:])
    return json.loads(process.stdout)

def unwrap(raw):
    require(isinstance(raw,dict),'Raw MCP result is not an object')
    if isinstance(raw.get('structuredContent'),dict):return raw['structuredContent']
    if 'content' in raw:
        for item in raw['content']:
            if item.get('type')=='text':
                try:
                    data=json.loads(item['text'])
                    if isinstance(data,dict) and 'status' in data:return data
                except (ValueError,KeyError):pass
        if raw.get('isError') is True:return {'status':'TOOL_ERROR','reason':str(raw['content'])}
        raise InvalidEvidence('Missing structured result; possibly truncated')
    require('status' in raw,'Raw result lacks status')
    return raw

def verify_identity(actual,expected):
    require(actual.get('verified') is True and actual.get('mode')=='IMMUTABLE_BENCHMARK','Unverified runtime')
    require(all(actual.get(k) and actual[k]==expected.get(k) for k in IDENTITY),'Runtime identity mismatch')

def text_unchanged(before,after):
    # Source-preserving authoring may insert text; it may never delete/rewrite existing content.
    return all(tag in ('equal','insert') for tag,*_ in difflib.SequenceMatcher(None,before.splitlines(keepends=True),after.splitlines(keepends=True),autojunk=False).get_opcodes())

def normalize_ast(node):
    if isinstance(node,list):return [normalize_ast(x) for x in node]
    if not isinstance(node,dict):return node
    out={k:normalize_ast(v) for k,v in node.items()}
    if out.get('kind') in ['AND','OR']:
        children=[]
        for c in out['children']:
            if c.get('kind')==out['kind']:children.extend(c['children'])
            else:children.append(c)
        out['children']=children
    return out

def total_boolean_rewrite(candidate, gold, facts):
    """Prove only permutations of total boolean operands, preserving every other AST node.

    Defined checks are total; comparisons are total only for numeric literals and
    direct numeric attributes proven mandatory by the independent compiler.
    Function results (including SUM), traversed paths and optional attributes are
    never assumed total. No propositional abstraction of partial operands is used.
    """
    used=[];rewrites=[]
    def total_number(n):
        if n.get('kind')=='NUMERIC':return not n.get('unit')
        for fact in facts:
            if fact.get('numeric') is True and fact.get('mandatory') is True and fact.get('path')==n:
                if fact not in used:used.append(fact)
                return True
        return False
    def total_bool(n):
        kind=n.get('kind')
        if kind=='DEFINED':
            operand=n.get('operand',{})
            # The only function covered here is the pinned SUM built-in; external
            # functions and other unsupported expressions cannot acquire a theorem.
            return operand.get('kind')=='PATH' or (operand.get('kind')=='FUNCTION' and operand.get('name')=='Math.sum'
                and len(operand.get('arguments',[]))==1 and operand['arguments'][0].get('kind')=='TEXT')
        if kind=='NOT':return total_bool(n['operand'])
        if kind in ['Equality','Inequality','GreaterThan','GreaterThanOrEqual','LessThan','LessThanOrEqual']:
            return total_number(n['left']) and total_number(n['right'])
        if kind in ['AND','OR']:return all(total_bool(c) for c in n['children'])
        return kind=='ENUM' and n.get('value') in [['true'],['false']]
    def same(a,b,path):
        if a==b:return True
        if isinstance(a,dict) and isinstance(b,dict) and set(a)==set(b):
            if a.get('kind')==b.get('kind') and a.get('kind') in ['AND','OR']:
                ac=a['children'];bc=b['children']
                if len(ac)==len(bc) and sorted(map(digest,ac))==sorted(map(digest,bc)) and all(total_bool(c) for c in ac):
                    rewrites.append({'path':path,'operator':a['kind'],'operands':len(ac),
                                     'theorem':'Permutation of total Boolean operands preserves ordered evaluation result.'})
                    return True
            return all(same(a[k],b[k],path+'.'+k) for k in a)
        if isinstance(a,list) and isinstance(b,list) and len(a)==len(b):
            return all(same(x,y,path+'.'+str(i)) for i,(x,y) in enumerate(zip(a,b)))
        return False
    if same(candidate,gold,'ast') and rewrites:
        return {'method':'TOTAL_BOOLEAN_PERMUTATION','rewrites':rewrites,'compilerPathFacts':used}
    return None

def equivalent(cid, candidate, gold, facts=()):
    candidate=normalize_ast(candidate);gold=normalize_ast(gold)
    if candidate==gold:return True,{'method':'ORDERED_COMPILER_AST'}
    if candidate.get('kind')!=gold.get('kind') or candidate.get('contextFqn')!=gold.get('contextFqn'):
        return False,{'method':'WRONG_CONSTRAINT_KIND_OR_CONTEXT'}
    if candidate.get('kind')=='ExistenceConstraint' and [p.get('root') for p in candidate.get('requiredIn',[])]!=[p.get('root') for p in gold.get('requiredIn',[])]:
        return False,{'method':'WRONG_EXISTENCE_TARGET'}
    theorem=total_boolean_rewrite(candidate,gold,facts)
    if theorem:return True,theorem
    # P01 is a finite total enum x integer domain. Exhaustive evaluation is a proof,
    # not a sample. All other alternate forms remain NOT_PROVEN until versioned evidence exists.
    if cid!='P01' or {k:v for k,v in candidate.items() if k!='condition'}!={k:v for k,v in gold.items() if k!='condition'}:
        return None,{'method':'NOT_PROVEN'}
    textures=['sand','schluffiger_sand','lehmiger_sand','lehmreicher_sand','sandiger_lehm','lehm','toniger_lehm','lehmiger_ton','ton','sandiger_schluff','schluff','lehmiger_schluff','toniger_schluff']
    def evaluate(n,env):
        k=n['kind']
        if k=='PATH':
            require(n['root']==gold['contextFqn'] and len(n['steps'])==1,'Unproven P01 path')
            return env[n['steps'][0]['name']]
        if k=='NUMERIC':
            require(not n.get('unit'),'Unproven P01 numeric unit')
            return decimal.Decimal(n['value'])
        if k=='ENUM':
            value='.'.join(n['value'])
            return value=='true' if value in ['true','false'] else value
        if k=='AND':return all(evaluate(c,env) for c in n['children'])
        if k=='OR':return any(evaluate(c,env) for c in n['children'])
        if k=='NOT':return not evaluate(n['operand'],env)
        if k=='DEFINED':evaluate(n['operand'],env);return True
        a=evaluate(n['left'],env);b=evaluate(n['right'],env)
        return {'Equality':lambda:a==b,'Inequality':lambda:a!=b,'GreaterThan':lambda:a>b,'GreaterThanOrEqual':lambda:a>=b,'LessThan':lambda:a<b,'LessThanOrEqual':lambda:a<=b}[k]()
    try:
        for texture in textures:
            for value in range(101):
                env={'Koernungsklasse':texture,'Tongehalt':value}
                if evaluate(candidate['condition'],env)!=evaluate(gold['condition'],env):return False,{'method':'EXHAUSTIVE_P01','counterexample':env}
    except (KeyError,TypeError,InvalidEvidence):return None,{'method':'NOT_PROVEN'}
    return True,{'method':'EXHAUSTIVE_P01','assignments':1313,'domain':'13 mandatory enum values x integer 0..100; verified against pinned model'}

def score(cid, lane, request, raw, evidence, expected, gold, catalog):
    require(cid in CASES and lane in ['end-to-end','mcp-reference'],'Unknown case/lane')
    tool=request.get('tool','').removeprefix(PREFIX);payload=request.get('payload',{})
    declarations={t['name']:t for t in catalog};require(tool in declarations,'Tool absent from catalog')
    errors=schema_errors(payload,declarations[tool]['inputSchema'])
    result=unwrap(raw);status=result.get('status')
    if status!='TOOL_ERROR':
        require(all(k in result for k in ['status','generated','complete','proofVerified']),'Incomplete authoring result')
    valid=not errors;choice=tool in expected['expectedHighLevelTools']
    ast_ok=None;proof=None;collateral=None;compiles=evidence.get('candidateCompiles')
    if evidence.get('astComplete') is True:ast_ok,proof=equivalent(cid,evidence['ast'],gold['ast'],evidence.get('pathFacts',[]))
    if 'noCollateralChanges' in evidence:collateral=evidence['noCollateralChanges'] and evidence.get('sourcePreserved') is True
    verified=result.get('proofVerified') is True
    if verified:
        proofs=result.get('constraintProofs',[])
        require(proofs and all(p.get('coverageComplete') is True and p.get('verification',{}).get('allPassed') is True and p.get('verification',{}).get('cases') for p in proofs),'Missing proof evidence behind proofVerified')
    successful=status=='GENERATED' and result.get('generated') is True and result.get('complete') is True and bool(result.get('updatedModelText'))
    point=valid and choice and successful and verified and compiles is True and ast_ok is True and collateral is True
    boundary=False
    if cid.startswith('N'):
        reason=str(result.get('reason',''))+' '+str(result.get('reasonCode',''))
        boundary=status in expected['acceptedBoundaryStatuses'] and ('external' in reason.lower() or 'EXTERNAL_FUNCTION' in reason)
        point=valid and choice and boundary and not result.get('updatedModelText') and not successful and not verified and result.get('generated') is False and compiles is True and ast_ok is True and collateral is True
    phase=None;responsibility=None
    if not point:
        if not valid or not choice:phase='PAYLOAD';responsibility='CLIENT_AGENT' if lane=='end-to-end' else 'BENCHMARK_INPUT'
        elif status=='TOOL_ERROR':phase='TOOL_CALL';responsibility='MCP_TOOL_CONTRACT'
        elif compiles is False:phase='COMPILE';responsibility='MCP_AUTHORING'
        elif ast_ok is not True or collateral is not True:phase='RECONSTRUCTION';responsibility='CLIENT_AGENT' if lane=='end-to-end' else 'MCP_AUTHORING'
        elif not verified:phase='PROOF';responsibility='PROOF_FIXTURE' if any(s in str(result.get('reasonCode','')) for s in ['FIXTURE','VIEW_','STRUCTURE_']) else 'SOLVER'
        else:phase='AUTHORING';responsibility='MCP_AUTHORING'
    evaluation_complete=not (compiles is True and valid and choice and (ast_ok is None or (proof or {}).get('method')=='NOT_PROVEN'))
    return {'schemaVersion':2,'evaluationComplete':evaluation_complete,'caseId':cid,'lane':lane,'inputCompleteAndUnambiguous':True,'correctToolChoice':choice,'validToolPayload':valid,
            'payloadErrors':errors,'authoringStatus':status,'authoringSuccessful':successful,'candidateCompiles':compiles,'proofVerified':verified,
            'astEquivalent':ast_ok,'equivalenceEvidence':proof,'noCollateralChanges':collateral,'boundaryRecognized':boundary,'score':int(point),
            'failurePhase':phase,'responsibility':responsibility,'rawResultSha256':digest(raw),'requestSha256':digest(request)}

def native_data(raw):
    if isinstance(raw.get('structuredContent'),dict):return raw['structuredContent']
    for item in raw.get('content',[]):
        if item.get('type')=='text':
            try:
                value=json.loads(item['text'])
                if isinstance(value,dict):return value
            except ValueError:pass
    raise InvalidEvidence('Native structured content missing')

def audit_calls(directory, identity):
    calls=sorted((directory/'calls').glob('*/request.json'))
    require(calls,'Missing native call trace')
    records=[];authors=[];canaries=[];previous_end=None
    require(read(calls[0])['tool']==PREFIX+'listConstraintFunctions','Canary must precede authoring')
    for request_path in calls:
        folder=request_path.parent;request=read(request_path);name=request.get('tool','')
        require(name.startswith(PREFIX) and name[len(PREFIX):] in AUTHOR+['listConstraintFunctions','resolveConstraintPath'],'Forbidden reconstruction tool')
        event=read(folder/'event.json');require(event.get('startedAt') and event.get('endedAt'),'Missing call timestamps')
        started=datetime.datetime.fromisoformat(event['startedAt'].replace('Z','+00:00'));ended=datetime.datetime.fromisoformat(event['endedAt'].replace('Z','+00:00'))
        require(started<=ended and (previous_end is None or started>=previous_end),'Overlapping or reversed MCP calls')
        previous_end=ended
        raw_path=folder/'raw-result.json';transport_path=folder/'transport-error.json'
        require(raw_path.exists()!=transport_path.exists(),'Each call needs exactly one result or transport error')
        if raw_path.exists():
            require(request_path.stat().st_mtime_ns<=raw_path.stat().st_mtime_ns,'Request was not persisted before result')
            raw=read(raw_path)
            if name==PREFIX+'listConstraintFunctions':
                info=native_data(raw)['runtimeIdentity'];verify_identity(info,identity);canaries.append(info)
        else:
            err=read(transport_path);require(err.get('transient') is True and err.get('message'),'Unproven transient transport error')
        if name[len(PREFIX):] in AUTHOR:authors.append((request,folder))
        records.append({'request':str(request_path.relative_to(directory)),'requestSha256':sha(request_path),'resultSha256':sha(raw_path if raw_path.exists() else transport_path),
                        'startedAt':event['startedAt'],'endedAt':event['endedAt'],'transportError':transport_path.exists()})
    require(canaries,'No runtime canary in role trace')
    require(sum(r['transportError'] for r in records)<=1,'More than one transport retry in a case')
    for index,record in enumerate(records):
        if record['transportError']:
            require(index+1<len(calls) and read(calls[index])==read(calls[index+1]),'Transport retry must repeat the immediate unchanged request')
    require(len(authors) in [1,2],'Must have exactly one authoring attempt, optionally one transport retry')
    if len(authors)==2:
        require(authors[0][0]==authors[1][0] and (authors[0][1]/'transport-error.json').exists(),'Semantic retry or modified retry payload')
    request,last=authors[-1]
    require((last/'raw-result.json').exists(),'Authoring transport failed; no case score')
    require(read(directory/'request.json')==request,'Canonical request differs from native call')
    require(read(directory/'raw-result.json')==read(last/'raw-result.json'),'Canonical result differs from native call')
    write(directory/'calls.json',records)
    return records

def audit_isolation(run):
    audit=read(run/'isolation-audit.json');require(audit.get('status')=='PASS','Missing independent isolation audit')
    require(set(audit.get('cases',{}))==set(CASES),'Isolation audit lacks cases')
    agents=[]
    for cid in CASES:
        d=run/'end-to-end'/cid;assignment=read(d/'assignment.json');launch=read(d/'launch.json');case=audit['cases'][cid]
        require(launch.get('forkTurns')=='none' and launch.get('promptSha256')==assignment['promptSha256'],'Inherited or altered reconstruction context')
        require(launch.get('model')=='gpt-5.6-luna' and launch.get('reasoningEffort')=='xhigh','Reconstructor model drift')
        require(launch.get('agentId') and case.get('agentId')==launch['agentId'],'Missing agent identity')
        require(case.get('allowedReadsOnly') is True and case.get('oracleAccess') is False,'Case isolation violation')
        transcript=d/'transcript.jsonl';require(transcript.is_file() and transcript.stat().st_size>0,'Actual role transcript missing')
        require(case.get('transcriptSha256')==sha(transcript),'Role transcript hash mismatch')
        agents.append(launch['agentId'])
    require(len(set(agents))==len(CASES),'Reconstructor reused between cases')


def score_case(suite,run,lane,cid):
    state=read(run/'run.json');require(state['status']=='IN_PROGRESS','Cannot rescore a historical run in place')
    require(read(run/'score/input-qa.json')['status']=='INPUT_VALID','Input QA required before scoring')
    d=run/lane/cid;request=read(d/'request.json');raw=read(d/'raw-result.json');result=unwrap(raw)
    model=(suite/'public'/cid/'model.ili').read_text()
    require(request['payload'].get('modelText')==model,'Submitted input model differs')
    if lane=='mcp-reference':
        template=read(suite/'reference'/cid/'request-template.json');template['payload']['modelText']=model
        require(request==template,'Reference request modified')
    audit_calls(d,read(run/'runtime-identity.json'))
    returned=result.get('updatedModelText') or result.get('candidateModelText')
    evidence={}
    if returned:
        require((d/'candidate.ili').read_text()==returned,'Candidate differs from raw result')
        evidence=compiler('compare',suite/'public'/cid/'model.ili',d/'candidate.ili',suite/'dependencies')
        evidence['sourcePreserved']=text_unchanged(model,returned)
    else:require(not (d/'candidate.ili').exists(),'Fabricated candidate')
    write(d/'compiler-evidence.json',evidence)
    result=score(cid,lane,request,raw,evidence,read(suite/'oracle'/cid/'expected.json'),read(suite/'oracle'/cid/'compiler-gold.json'),read(run/'tool-catalog.json'))
    write(d/'score.json',result)
    state=read(run/'run.json');state.update(phase='SCORING',updatedAt=now())
    state['scoredCases']=sorted(set(state.get('scoredCases',[])+[lane+'/'+cid]))
    write(run/'run.json',state);return result

def input_qa(suite, draft=False):
    verify_suite(suite,draft)
    cases={};catalog=read(suite/'tool-catalog.json')
    for cid in CASES:
        expected=read(suite/'oracle'/cid/'expected.json');gold=read(suite/'oracle'/cid/'compiler-gold.json')
        corpus=expected['corpus'];original=subprocess.check_output(['git','-C',str(ROOT.parent/'sogis-interlis-repository'),'show',corpus['commit']+':'+corpus['path']])
        require((suite/'oracle'/cid/'gold-model.ili').read_bytes()==original,'Gold model differs from corpus commit')
        selector=expected['originalConstraintSelector']
        if '.' not in selector:selector=expected['gold']['contextFqn']+'.'+selector
        inspected=compiler('inspect',suite/'oracle'/cid/'gold-model.ili',suite/'dependencies',selector)
        require(inspected['valid'] and inspected.get('astComplete') and inspected.get('ast')==gold['ast'],'Gold AST mismatch')
        compared=compiler('compare',suite/'public'/cid/'model.ili',suite/'oracle'/cid/'gold-model.ili',suite/'dependencies')
        require(compared.get('beforeValid') is True and compared.get('noCollateralChanges') is True,'Public model differs beyond target removal: '+cid)
        request=read(suite/'reference'/cid/'request-template.json');request['payload']['modelText']=(suite/'public'/cid/'model.ili').read_text()
        name=request['tool'].removeprefix(PREFIX)
        require(name in expected['expectedHighLevelTools'],'Reference tool choice invalid')
        schema=next(t['inputSchema'] for t in catalog if t['name']==name)
        require(not schema_errors(request['payload'],schema),'Reference payload schema invalid')
        cases[cid]={'status':'TECHNICAL_QA_PASS','publicCompiles':True,'goldCompiles':True,'goldAstComplete':True,'corpusVerified':True,
                    'requirementSha256':sha(suite/'public'/cid/'requirement.de.md'),'referenceRequestSha256':digest(request),'goldAstSha256':digest(gold['ast'])}
    return {'schemaVersion':2,'status':'TECHNICAL_QA_PASS' if draft else 'INPUT_VALID','suiteManifestSha256':sha(suite/'manifest.json'),'cases':cases}


def initialize(suite,runs):
    runs.mkdir(parents=True,exist_ok=True)
    lock=runs/'active-run.lock'
    try:fd=os.open(lock,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
    except FileExistsError:raise InvalidEvidence('Active-run lock exists; inspect owner, never overwrite automatically')
    run=runs/(datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')+'-'+git('rev-parse','--short','HEAD').strip()+'-'+uuid.uuid4().hex[:6])
    try:
        run.mkdir();os.write(fd,str(run).encode());os.close(fd)
        m=verify_suite(suite)
        write(run/'run.json',{'schemaVersion':2,'status':'IN_PROGRESS','phase':'PREFLIGHT','startedAt':now(),'suiteVersion':m['suiteVersion'],
             'suiteManifestSha256':sha(suite/'manifest.json'),'clientMode':'CODEX_MANAGED_MCP','manualMcpProcessesStarted':0,'reconstructorModel':'gpt-5.6-luna','reasoningEffort':'xhigh'})
        write(run/'provenance-before.json',{'mcp':provenance(),'corpus':provenance(ROOT.parent/'sogis-interlis-repository')})
        return run
    except Exception as error:
        if run.exists():
            write(run/'run.json',{'schemaVersion':2,'status':'INCOMPLETE_FAIL_CLOSED','phase':'SUITE_GATE','reason':str(error),'startedAt':now()})
            (run/'REPORT.md').write_text('Benchmark nicht gewertet: '+str(error)+'\n')
        if lock.exists() and lock.read_text()==str(run):lock.unlink()
        raise

def prepare_case(suite,run,cid,lane):
    require(cid in CASES and lane in ['end-to-end','mcp-reference'],'Unknown case/lane')
    state=read(run/'run.json');require(state['status']=='IN_PROGRESS' and state['phase'] in ['READY','RUNNING','SCORING'],'Run not ready for cases')
    require(read(run/'preflight/smoke.json')['status']=='PASS' and read(run/'score/input-qa.json')['status']=='INPUT_VALID','Preflight/Input QA required')
    state.update(phase='RUNNING',updatedAt=now());write(run/'run.json',state)
    d=run/lane/cid;require(not d.exists(),'Case already prepared');d.mkdir(parents=True)
    if lane=='mcp-reference':
        request=read(suite/'reference'/cid/'request-template.json');request['payload']['modelText']=(suite/'public'/cid/'model.ili').read_text()
        write(d/'request.json',request)
        return {'output':str(d),'request':str(d/'request.json')}
    temp=run/'isolated-inputs'/uuid.uuid4().hex;temp.mkdir(parents=True)
    for name in ['model.ili','requirement.de.md']:shutil.copyfile(suite/'public'/cid/name,temp/name)
    write(temp/'hashes.json',{p.name:sha(p) for p in temp.iterdir() if p.is_file()})
    recorder=(suite/'native-recorder.js').read_text().replace('{{INPUT}}',str(temp)).replace('{{OUTPUT}}',str(d))
    (d/'native-recorder.js').write_text(recorder)
    prompt=(suite/'reconstructor-prompt.md').read_text().replace('{{INPUT}}',str(temp)).replace('{{OUTPUT}}',str(d))
    write(d/'assignment.json',{'caseId':cid,'forkTurns':'none','inputDirectory':str(temp),'outputDirectory':str(d),'prompt':prompt,'promptSha256':hashlib.sha256(prompt.encode()).hexdigest()})
    return {'input':str(temp),'output':str(d),'assignment':str(d/'assignment.json')}

def finalize(suite,run):
    state=read(run/'run.json');require(state['status']=='IN_PROGRESS','Run not active')
    verify_suite(suite);require(sha(suite/'manifest.json')==state['suiteManifestSha256'],'Suite changed during run')
    smoke=read(run/'preflight/smoke.json');require(smoke['status']=='PASS','Preflight did not pass')
    require(smoke['canaryRecord']==verify_preflight_canary(run/'preflight'),'Preflight call record changed')
    require(smoke['nativeCatalogSha256']==sha(run/'preflight/native-tool-catalog.json') and smoke['canarySha256']==sha(run/'preflight/native-canary.json'),'Preflight evidence changed')
    verify_identity(native_data(read(run/'preflight/native-canary.json'))['runtimeIdentity'],read(run/'runtime-identity.json'))
    require(read(run/'tool-catalog.json')==read(suite/'tool-catalog.json'),'Tool schema drift')
    junit=list((run/'preflight/test-results').rglob('TEST-*.xml'));require(junit,'JUnit evidence missing')
    xml=[ET.parse(p).getroot() for p in junit]
    require(all(int(r.get('failures',0))==0 and int(r.get('errors',0))==0 for r in xml),'JUnit failure in archived evidence')
    require(any(t.get('name')=='initialize_largeToolsList_fullDrain_thenEofShutsDownWithExitCodeZero()' and t.find('skipped') is None for r in xml for t in r.findall('testcase')),'Lifecycle test evidence missing')
    qa=read(run/'score/input-qa.json')
    require(qa['status']=='INPUT_VALID' and qa.get('suiteManifestSha256')==state['suiteManifestSha256'] and set(qa.get('cases',{}))==set(CASES),'Input QA failed')
    audit_isolation(run)
    before=read(run/'provenance-before.json');after={'mcp':provenance(),'corpus':provenance(ROOT.parent/'sogis-interlis-repository')}
    write(run/'provenance-after.json',after);require(before==after,'Working-tree content changed during run')
    all_scores={lane:[score_case(suite,run,lane,c) for c in CASES] for lane in ['end-to-end','mcp-reference']}
    require(not any(s['responsibility']=='BENCHMARK_INPUT' for s in all_scores['mcp-reference']),'Reference payload invalid')
    require(all(s['evaluationComplete'] for scores in all_scores.values() for s in scores),'Equivalence not proven: no valid total score')
    intervals=[(datetime.datetime.fromisoformat(smoke['canaryRecord']['startedAt'].replace('Z','+00:00')),datetime.datetime.fromisoformat(smoke['canaryRecord']['endedAt'].replace('Z','+00:00')))]
    for lane in all_scores:
        for cid in CASES:
            intervals.extend((datetime.datetime.fromisoformat(r['startedAt'].replace('Z','+00:00')),datetime.datetime.fromisoformat(r['endedAt'].replace('Z','+00:00'))) for r in read(run/lane/cid/'calls.json'))
    intervals.sort()
    require(all(b[0]>=a[1] for a,b in zip(intervals,intervals[1:])),'MCP calls overlapped across cases or lanes')
    require((run/'FINDINGS.md').is_file(),'Prioritized findings missing')
    totals={lane:lane_totals(scores) for lane,scores in all_scores.items()}
    write(run/'score/final-score.json',{'schemaVersion':2,'status':'VALID','lanes':totals})
    report=['# INTERLIS MCP Constraint Benchmark v2','',f"MCP `{before['mcp']['commit']}`; Dirty: `{bool(before['mcp']['status'])}`; Corpus `{before['corpus']['commit']}`; Vorprüfung PASS.",
       '', 'Bekannte Regressionfälle; keine Aussage über unbekannte Modelle. v1-Gesamtwerte sind nicht direkt vergleichbar.','']
    for lane,scores in all_scores.items():
        report += ['## '+lane,'',f"Rekonstruktion {totals[lane]['positive']['earned']}/10; Grenzen {totals[lane]['boundary']['earned']}/2.",'',
                   f"Strukturelle Rekonstruktion {totals[lane]['structuralReconstruction']['earned']}/10; vollständiger Proof {totals[lane]['proof']['earned']}/10. Diese Teilmessungen sind keine zusätzlichen Gesamtpunkte.",'',
                   '|Fall|Werkzeug|Status|Compile|Proof|AST|Keine Fremdänderung|Punkt|Verantwortung|','|---|---|---|---|---|---|---|---|---|']
        for s in scores:
            d=run/lane/s['caseId'];tool=read(d/'request.json')['tool'].removeprefix(PREFIX)
            report.append('|'+ '|'.join(str(v) for v in [s['caseId'],tool,s['authoringStatus'],s['candidateCompiles'],s['proofVerified'],s['astEquivalent'],s['noCollateralChanges'],s['score'],s['responsibility']])+'|')
        report+=['']
        for s in scores:
            cid=s['caseId'];d=run/lane/cid
            paths=[suite/'public'/cid/'requirement.de.md',suite/'public'/cid/'model.ili']+[d/name for name in ['request.json','raw-result.json','candidate.ili','compiler-evidence.json','score.json'] if (d/name).exists()]
            report.append(cid+': '+', '.join('[%s](%s)'%(p.name,p.resolve()) for p in paths))
    (run/'REPORT.md').write_text('\n'.join(report)+'\n')
    state=read(run/'run.json');state.update(status='COMPLETE',phase='COMPLETE',completedAt=now(),scores=totals)
    # Verify all case/report evidence before publishing COMPLETE. Detached inventory has no self-hash.
    inventory={str(p.relative_to(run)):sha(p) for p in sorted(run.rglob('*')) if p.is_file() and p.name!='artifacts.sha256.json'}
    write(run/'artifacts.sha256.json',inventory)
    require(all(sha(run/p)==h for p,h in inventory.items()),'Artifact verification failed')
    write(run/'run.json',state)
    inventory['run.json']=sha(run/'run.json')
    write(run/'artifacts.sha256.json',inventory)
    lock=run.parent/'active-run.lock';require(lock.read_text()==str(run),'Lock owner mismatch');lock.unlink()
    return totals

def abort(run, reason):
    state=read(run/'run.json');require(state['status']=='IN_PROGRESS','Cannot alter a historical run')
    state.update(status='INCOMPLETE_FAIL_CLOSED',phase='ABORTED',reason=reason,endedAt=now())
    write(run/'run.json',state)
    if (run/'score/final-score.json').exists():write(run/'score/final-score.json',{'status':'INVALID','reason':reason})
    (run/'REPORT.md').write_text('Benchmark nicht gewertet. '+reason+'\n')
    lock=run.parent/'active-run.lock'
    require(lock.read_text()==str(run),'Lock owner mismatch');lock.unlink()
    return state


def main():
    p=argparse.ArgumentParser();p.add_argument('command',choices=['verify','input-qa','init','preflight','bind-runtime','prepare-case','score-case','finalize','inspect','abort']);p.add_argument('--suite',type=pathlib.Path,default=BASE/'v2');p.add_argument('--run',type=pathlib.Path);p.add_argument('--runs',type=pathlib.Path,default=pathlib.Path.home()/'.codex/automations/interlis-mcp-constraint-benchmark/runs-v2');p.add_argument('--case');p.add_argument('--lane',choices=['end-to-end','mcp-reference']);p.add_argument('--draft',action='store_true');p.add_argument('--reason');a=p.parse_args()
    suite=a.suite.resolve();run=a.run.resolve() if a.run else None
    if a.command=='verify':out=verify_suite(suite,a.draft)
    elif a.command=='input-qa':
        out=input_qa(suite,a.draft)
        if run:
            require(not a.draft,'Draft QA cannot unlock a scored run')
            write(run/'score/input-qa.json',out)
            state=read(run/'run.json');require(state['status']=='IN_PROGRESS','Run not active');state.update(phase='READY',updatedAt=now());write(run/'run.json',state)
    elif a.command=='preflight':out=preflight(run)
    elif a.command=='bind-runtime':out=bind_runtime(suite,run)
    elif a.command=='abort':out=abort(run,a.reason or 'Explicitly interrupted')
    elif a.command=='init':out=str(initialize(suite,a.runs.resolve()))
    elif a.command=='prepare-case':out=prepare_case(suite,run,a.case,a.lane)
    elif a.command=='score-case':out=score_case(suite,run,a.lane,a.case)
    elif a.command=='finalize':out=finalize(suite,run)
    else:out=compiler('inspect',suite/'public'/a.case/'model.ili',suite/'dependencies')
    print(json.dumps(out,ensure_ascii=False))
if __name__=='__main__':
    try:main()
    except (InvalidEvidence,KeyError,ValueError) as e:sys.exit('FAIL_CLOSED: '+str(e))
