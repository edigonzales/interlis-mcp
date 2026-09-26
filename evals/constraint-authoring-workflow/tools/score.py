"""Offline audit of recorded workflow evidence; not a replacement for a native agent run."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def score(case_id, evidence):
    oracle = json.loads((ROOT / 'reference' / (case_id + '.json')).read_text())
    manifest = next(c for c in json.loads((ROOT / 'suite.json').read_text())['cases'] if c['id'] == case_id)
    errors = []
    for name, expected_hash in manifest['inputs'].items():
        if hashlib.sha256((ROOT / 'public' / case_id / name).read_bytes()).hexdigest() != expected_hash:
            errors.append('suite_input_changed')
    if evidence.get('inputHashes') != manifest['inputs']:
        errors.append('run_input_mismatch')
    events = evidence.get('events', [])
    kinds = [e.get('kind') for e in events]
    # Meaning of prose/questions is assessed by an independent reviewer, never inferred from a tool success flag.
    review = evidence.get('independentReview', {})
    if review.get('interpretationCorrect') is not True:
        errors.append('interpretation_not_reviewed_or_incorrect')
    required = set(oracle['requiredQuestions'])
    if required:
        if not required.issubset(set(review.get('questionsCovered', []))):
            errors.append('required_question_missing')
        if 'WRITE' in kinds or 'AUTHOR' in kinds:
            errors.append('unresolved_requirement_executed')
    else:
        if not set(oracle['rules']).issubset(set(review.get('rulesCovered', []))):
            errors.append('rule_missing')
        frozen = None
        tested_model = None
        attempted = False
        authored_model = None
        source_model = None
        repair_count = 0
        wrote = False
        written_model = None
        for event in events:
            kind = event.get('kind')
            if kind == 'EXPECTATIONS':
                if attempted:
                    errors.append('expectations_changed_after_authoring')
                frozen = event.get('digest')
            elif kind == 'AUTHOR':
                if not frozen:
                    errors.append('expectations_not_frozen')
                attempted = True
                hashes = event.get('modelHashes', {})
                source_model = hashes.get('before')
                authored_model = hashes.get('after')
                tested_model = None
            elif kind == 'REPAIR':
                repair_count += 1
                if event.get('reason') != 'INVALID_SPEC' or repair_count > 1:
                    errors.append('invalid_repair')
            elif kind == 'TEST':
                checked = event.get('modelHashes', {}).get('model')
                if not authored_model or checked != authored_model or checked != event.get('modelHash'):
                    errors.append('server_model_hash_mismatch')
                if not attempted or not frozen or event.get('expectationsDigest') != frozen:
                    errors.append('expectation_trace_mismatch')
                if (event.get('allPassed') is True and event.get('fixtureValid') is True
                        and event.get('constraintExercised') is True):
                    tested_model = event.get('modelHash')
                else:
                    tested_model = None
            elif kind == 'WRITE':
                if not source_model or event.get('sourceModelHash') != source_model:
                    errors.append('source_model_changed_or_unchecked')
                wrote = True
                written_model = event.get('modelHash')
                if not tested_model or event.get('modelHash') != tested_model or event.get('technicalGatePassed') is not True:
                    errors.append('write_gate_violated')
        if not wrote:
            errors.append('no_completed_write')
        if not written_model or evidence.get('referenceModelHash') != written_model:
            errors.append('reference_model_mismatch')
        if case_id == 'R01' and repair_count != 1:
            errors.append('repair_scenario_not_exercised')
        references = evidence.get('referenceResults', [])
        actual = {r.get('id'): r for r in references}
        if len(actual) != len(references):
            errors.append('duplicate_reference_result')
        for expected in oracle['expectations']:
            result = actual.get(expected['id'], {})
            if (result.get('actualValid') is not expected['expectedValid']
                    or result.get('fixtureValid') is not True or result.get('constraintExercised') is not True):
                errors.append('reference_failed:' + expected['id'])
    if not evidence.get('nativeTranscript') or not evidence.get('referenceArtifact') or not review.get('reviewer'):
        errors.append('missing_audit_artifact')
    return {'caseId': case_id, 'passed': not errors, 'errors': sorted(set(errors))}


if __name__ == '__main__':
    import sys
    print(json.dumps(score(sys.argv[1], json.loads(Path(sys.argv[2]).read_text())), indent=2))
