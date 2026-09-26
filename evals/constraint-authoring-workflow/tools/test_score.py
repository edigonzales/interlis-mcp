import json
import unittest
from score import ROOT, score


class ScorerTest(unittest.TestCase):
    def evidence(self, ident='P01'):
        entry = next(c for c in json.loads((ROOT / 'suite.json').read_text())['cases'] if c['id'] == ident)
        oracle = json.loads((ROOT / 'reference' / (ident + '.json')).read_text())
        return {'referenceModelHash': 'model', 'inputHashes': entry['inputs'], 'nativeTranscript': 'fixture-only.json', 'referenceArtifact': 'fixture-only-reference.json',
                'independentReview': {'reviewer': 'synthetic-test', 'interpretationCorrect': True,
                                      'questionsCovered': oracle['requiredQuestions'], 'rulesCovered': oracle['rules']},
                'referenceResults': [dict(id=e['id'], actualValid=e['expectedValid'], fixtureValid=True, constraintExercised=True) for e in oracle['expectations']],
                'events': [] if oracle['requiredQuestions'] else [
                    {'kind': 'EXPECTATIONS', 'digest': 'expectations'}, {'kind': 'AUTHOR', 'modelHashes': {'before': entry['inputs']['model.ili'], 'after': 'model'}},
                    {'kind': 'TEST', 'modelHashes': {'model': 'model'}, 'expectationsDigest': 'expectations', 'modelHash': 'model', 'allPassed': True, 'fixtureValid': True, 'constraintExercised': True},
                    {'kind': 'WRITE', 'sourceModelHash': entry['inputs']['model.ili'], 'modelHash': 'model', 'technicalGatePassed': True}]}

    def test_valid_audit_fixture(self):
        self.assertTrue(score('P01', self.evidence())['passed'])

    def test_wrong_boundary_is_rejected(self):
        e = self.evidence(); e['referenceResults'][1]['actualValid'] = False
        self.assertFalse(score('P01', e)['passed'])

    def test_no_evidence_is_not_success(self):
        self.assertFalse(score('P01', {})['passed'])

    def test_unsafe_writes_are_rejected(self):
        for mutation in ['model', 'fixture', 'expectations', 'technical', 'untested', 'reference', 'server_hash', 'changed_source']:
            e = self.evidence()
            if mutation == 'model': e['events'][-1]['modelHash'] = 'changed'
            if mutation == 'fixture': e['events'][-2]['fixtureValid'] = False
            if mutation == 'expectations': e['events'].insert(2, {'kind': 'EXPECTATIONS', 'digest': 'changed'})
            if mutation == 'technical': e['events'][-1]['technicalGatePassed'] = False
            if mutation == 'server_hash': e['events'][-2]['modelHashes']['model'] = 'other'
            if mutation == 'changed_source': e['events'][-1]['sourceModelHash'] = 'changed'
            if mutation == 'reference': e['referenceModelHash'] = 'different'
            if mutation == 'untested': e['events'].pop(-2)
            self.assertFalse(score('P01', e)['passed'], mutation)

    def test_clarification_is_success_without_authoring(self):
        e = self.evidence('Q02'); self.assertTrue(score('Q02', e)['passed'])
        e['events'].append({'kind': 'WRITE'}); self.assertFalse(score('Q02', e)['passed'])

    def test_repair_is_bounded(self):
        e = self.evidence('R01'); self.assertFalse(score('R01', e)['passed'])
        e['events'].insert(2, {'kind': 'REPAIR', 'reason': 'INVALID_SPEC'})
        self.assertTrue(score('R01', e)['passed'])
        e['events'].insert(2, {'kind': 'REPAIR', 'reason': 'INVALID_SPEC'})
        self.assertFalse(score('R01', e)['passed'])
