import copy, pathlib, tempfile, unittest
import benchmark as b

class ScoringTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.suite=b.BASE/'v2';cls.catalog=b.read(cls.suite/'tool-catalog.json')
    def fixture(self,cid='P04'):
        d=b.ROOT/'build/benchmark/reference-diagnostic'/cid
        r=b.read(d/'request.json');raw=b.read(d/'raw-result.json');e=b.read(d/'compiler-evidence.json')
        e['sourcePreserved']=b.text_unchanged((self.suite/'public'/cid/'model.ili').read_text(),(d/'candidate.ili').read_text())
        return [cid,'mcp-reference',r,raw,e,b.read(self.suite/'oracle'/cid/'expected.json'),b.read(self.suite/'oracle'/cid/'compiler-gold.json'),self.catalog]
    def test_all_fixed_fixtures_have_repeatable_complete_evidence(self):
        for cid in b.CASES:
            args=self.fixture(cid);one=b.score(*args);two=b.score(*copy.deepcopy(args))
            self.assertEqual(one,two);self.assertEqual(one['score'],1,cid);self.assertTrue(one['evaluationComplete'])
    def test_ordered_and_or_must_not_be_sorted(self):
        args=self.fixture();ast=args[4]['ast'];consequence=ast['condition']['children'][1]
        consequence['children'].reverse()
        score=b.score(*args)
        self.assertEqual(score['score'],0);self.assertFalse(score['astEquivalent']);self.assertFalse(score['evaluationComplete'])
    def test_changed_literal_fails_exhaustive_proof(self):
        args=self.fixture('P01')
        def change(node):
            if isinstance(node,dict):
                if node.get('kind')=='NUMERIC' and node.get('value')=='5':node['value']='4';return True
                return any(change(n) for n in node.values())
            if isinstance(node,list):return any(change(n) for n in node)
            return False
        self.assertTrue(change(args[4]['ast']))
        score=b.score(*args);self.assertEqual(score['score'],0);self.assertIn('counterexample',score['equivalenceEvidence'])
    def test_boolean_false_cannot_be_mistaken_for_a_truthy_enum_string(self):
        args=self.fixture('P01');condition=args[4]['ast']['condition']
        args[4]['ast']['condition']={'kind':'AND','children':[{'kind':'ENUM','value':['false']},condition]}
        result=b.score(*args);self.assertEqual(result['score'],0);self.assertIn('counterexample',result['equivalenceEvidence'])
    def test_strict_boundary_operator_cannot_receive_point(self):
        args=self.fixture('N11');args[4]['ast']['condition']['kind']='GreaterThanOrEqual'
        score=b.score(*args);self.assertEqual(score['score'],0);self.assertFalse(score['evaluationComplete'])
    def test_existence_target_class_is_not_lost(self):
        args=self.fixture('P09');args[4]['ast']['requiredIn'][0]['root']='Wrong.Topic.Class'
        score=b.score(*args);self.assertEqual(score['score'],0);self.assertEqual(score['equivalenceEvidence']['method'],'WRONG_EXISTENCE_TARGET')
    def test_absent_sum_guard_not_silently_repaired(self):
        args=self.fixture('P06');candidate=copy.deepcopy(args[6]['ast'])
        first=candidate['condition']['children'][0]
        self.assertEqual(first['children'][0]['kind'],'DEFINED')
        first['children'].pop(0);args[4]['ast']=candidate
        score=b.score(*args);self.assertEqual(score['score'],0);self.assertFalse(score['evaluationComplete'])
    def test_wrong_payload_is_client_error_only_in_end_to_end(self):
        args=self.fixture();args[1]='end-to-end';args[2]['payload']['spec']['kind']='UNIQUE'
        score=b.score(*args);self.assertEqual(score['score'],0);self.assertEqual(score['responsibility'],'CLIENT_AGENT')
        args[1]='mcp-reference';self.assertEqual(b.score(*args)['responsibility'],'BENCHMARK_INPUT')
    def test_unrelated_model_change_never_passes(self):
        args=self.fixture();args[4]['noCollateralChanges']=False
        self.assertEqual(b.score(*args)['score'],0)
    def test_compiler_failure_is_not_a_boundary(self):
        args=self.fixture('N11');raw=b.unwrap(args[3]);raw['status']='CANDIDATE_MODEL_INVALID';raw['reason']='external area semantics required'
        args[3]=raw;args[4]={'candidateCompiles':False}
        result=b.score(*args);self.assertEqual(result['score'],0);self.assertFalse(result['boundaryRecognized'])
    def test_truncated_or_incomplete_raw_result_blocks_scoring(self):
        args=self.fixture();args[3]={'status':'GENERATED','proofVerified':True}
        with self.assertRaises(b.InvalidEvidence):b.score(*args)
    def test_claimed_proof_without_cases_blocks_scoring(self):
        args=self.fixture();raw=b.unwrap(args[3]);raw['constraintProofs']=[];args[3]=raw
        with self.assertRaises(b.InvalidEvidence):b.score(*args)
    def test_unknown_ast_blocks_a_valid_total(self):
        args=self.fixture();args[4].pop('ast');args[4]['astComplete']=False
        self.assertFalse(b.score(*args)['evaluationComplete'])
    def test_runtime_identity_mismatch_is_fail_closed(self):
        expected={k:'expected' for k in b.IDENTITY};actual=dict(expected,verified=True,mode='IMMUTABLE_BENCHMARK')
        b.verify_identity(actual,expected);actual['jarSha256']='different'
        with self.assertRaises(b.InvalidEvidence):b.verify_identity(actual,expected)
    def test_snapshot_corruption_and_unlisted_model_are_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            d=pathlib.Path(temp);(d/'model.ili').write_text('original')
            b.write(d/'manifest.json',{'files':[{'path':'model.ili','sha256':b.sha(d/'model.ili')}]})
            b.verify_dependencies(d/'manifest.json');(d/'other.ili').write_text('extra')
            with self.assertRaises(b.InvalidEvidence):b.verify_dependencies(d/'manifest.json')
            (d/'other.ili').unlink();(d/'model.ili').write_text('changed')
            with self.assertRaises(b.InvalidEvidence):b.verify_dependencies(d/'manifest.json')
    def test_public_cases_contain_other_solutions_so_context_reuse_is_forbidden(self):
        gold=(b.BASE/'v1/oracle/P02/original-constraint.ili').read_text().strip()
        self.assertIn(gold,(self.suite/'public/P01/model.ili').read_text())
        prompt=(self.suite/'reconstructor-prompt.md').read_text();self.assertIn('genau einen',prompt)
    def test_source_deletion_is_collateral_even_if_compiler_ignores_comment(self):
        self.assertFalse(b.text_unchanged('!! original\nMODEL A\n','MODEL A\n'))
    def test_unknown_schema_assertion_never_silently_ignored(self):
        with self.assertRaises(b.InvalidEvidence):b.schema_errors('x',{'madeUpAssertion':True})
    def test_native_catalog_with_same_names_but_changed_signature_is_rejected(self):
        expected=b.read(self.suite/'native-tool-declarations.json')
        b.verify_native_catalog(list(reversed(expected)),expected)
        changed=copy.deepcopy(expected);changed[0]['description']+='\nChanged payload contract'
        with self.assertRaises(b.InvalidEvidence):b.verify_native_catalog(changed,expected)
    def test_structural_reconstruction_and_proof_remain_separate_from_full_score(self):
        scores=[b.score(*self.fixture(cid)) for cid in b.CASES]
        scores[0].update(score=0,proofVerified=False)
        scores[1].update(score=0,astEquivalent=False)
        totals=b.lane_totals(scores)
        self.assertEqual(totals['positive'],{'earned':8,'total':10})
        self.assertEqual(totals['structuralReconstruction'],{'earned':9,'total':10})
        self.assertEqual(totals['proof'],{'earned':9,'total':10})
        self.assertEqual(totals['boundary'],{'earned':2,'total':2})

class CallAuditTests(unittest.TestCase):
    def test_preflight_canary_requires_exact_request_and_ordered_timestamps(self):
        with tempfile.TemporaryDirectory() as temp:
            d=pathlib.Path(temp)
            b.write(d/'native-canary-request.json',{'tool':b.PREFIX+'listConstraintFunctions','payload':{}})
            b.write(d/'native-canary.json',{'structuredContent':{'runtimeIdentity':self.identity()}})
            event={'startedAt':'2026-09-08T00:00:00Z','endedAt':'2026-09-08T00:00:01Z'}
            b.write(d/'native-canary-event.json',event)
            self.assertEqual(b.verify_preflight_canary(d)['startedAt'],event['startedAt'])
            event['endedAt']='2026-09-07T23:59:59Z';b.write(d/'native-canary-event.json',event)
            with self.assertRaises(b.InvalidEvidence):b.verify_preflight_canary(d)
    def identity(self):return dict({k:'fixture' for k in b.IDENTITY},verified=True,mode='IMMUTABLE_BENCHMARK')
    def call(self,d,index,request,transport=False):
        folder=d/'calls'/('%02d'%index);b.write(folder/'request.json',request)
        b.write(folder/'event.json',{'startedAt':'2026-09-08T00:00:%02dZ'%(index*2),'endedAt':'2026-09-08T00:00:%02dZ'%(index*2+1)})
        raw={'structuredContent':{'runtimeIdentity':self.identity()}} if request['tool'].endswith('listConstraintFunctions') else {'structuredContent':{'status':'INVALID_SPEC','generated':False,'complete':False,'proofVerified':False}}
        if transport:b.write(folder/'transport-error.json',{'transient':True,'message':'Synthetic transport failure for unit test'})
        else:b.write(folder/'raw-result.json',raw)
        return raw
    def test_native_trace_and_unmodified_retry_are_required(self):
        with tempfile.TemporaryDirectory() as temp:
            d=pathlib.Path(temp);self.call(d,1,{'tool':b.PREFIX+'listConstraintFunctions','payload':{}})
            request={'tool':b.PREFIX+'authorIliMandatoryConstraint','payload':{'spec':{'kind':'MANDATORY'}}}
            self.call(d,2,request,transport=True);raw=self.call(d,3,request)
            b.write(d/'request.json',request);b.write(d/'raw-result.json',raw)
            self.assertEqual(len(b.audit_calls(d,self.identity())),3)
            altered=copy.deepcopy(request);altered['payload']['spec']['name']='Repaired'
            b.write(d/'calls/03/request.json',altered);b.write(d/'calls/03/raw-result.json',raw);b.write(d/'request.json',altered)
            with self.assertRaises(b.InvalidEvidence):b.audit_calls(d,self.identity())
    def test_missing_raw_result_never_becomes_a_server_score(self):
        with tempfile.TemporaryDirectory() as temp:
            d=pathlib.Path(temp);self.call(d,1,{'tool':b.PREFIX+'listConstraintFunctions','payload':{}})
            request={'tool':b.PREFIX+'authorIliMandatoryConstraint','payload':{}};raw=self.call(d,2,request)
            b.write(d/'request.json',request);b.write(d/'raw-result.json',raw);(d/'calls/02/raw-result.json').unlink()
            with self.assertRaises(b.InvalidEvidence):b.audit_calls(d,self.identity())
    def test_a_reconstructor_cannot_be_reused_for_another_case(self):
        with tempfile.TemporaryDirectory() as temp:
            run=pathlib.Path(temp);audit={'status':'PASS','cases':{}}
            for cid in b.CASES:
                d=run/'end-to-end'/cid;b.write(d/'assignment.json',{'promptSha256':'fixture'})
                b.write(d/'launch.json',{'forkTurns':'none','promptSha256':'fixture','model':'gpt-5.6-luna','reasoningEffort':'xhigh','agentId':'reused-agent'})
                (d/'transcript.jsonl').write_text('synthetic unit-test transcript\n')
                audit['cases'][cid]={'agentId':'reused-agent','allowedReadsOnly':True,'oracleAccess':False,'transcriptSha256':b.sha(d/'transcript.jsonl')}
            b.write(run/'isolation-audit.json',audit)
            with self.assertRaises(b.InvalidEvidence):b.audit_isolation(run)

if __name__=='__main__':unittest.main()
