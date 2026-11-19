#!/usr/bin/env python3
"""Exercise the INTERLIS Streamable HTTP endpoint."""
import concurrent.futures
import json
import sys
import time
import urllib.error
import urllib.request

PROTOCOL = '2025-11-25'
MODEL = '''INTERLIS 2.4;
MODEL Smoke (de) AT "https://example.org" VERSION "2026-01-01" =
  TOPIC Data =
    CLASS Item =
      day : MANDATORY INTERLIS.XMLDate;
      UNIQUE UniqueDay: day;
    END Item;
  END Data;
END Smoke.
'''


def probe(url):
    headers = {'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream'}

    def send(method, params=None, request_id=None):
        message = {'jsonrpc': '2.0', 'method': method}
        if params is not None:
            message['params'] = params
        if request_id is not None:
            message['id'] = request_id
        request = urllib.request.Request(url, data=json.dumps(message).encode(), headers=headers)
        with urllib.request.urlopen(request, timeout=30) as response:
            if method == 'initialize':
                headers['Mcp-Session-Id'] = response.headers['Mcp-Session-Id']
                assert headers['Mcp-Session-Id'], 'Expected an MCP session'
            if request_id is None:
                assert response.status == 202, response.status
                return
            if response.headers.get_content_type() == 'text/event-stream':
                reply = next((json.loads(line[5:]) for line in response if line.startswith(b'data:')), None)
            else:
                reply = json.load(response)
        assert reply and reply.get('jsonrpc') == '2.0' and reply.get('id') == request_id, reply
        return reply

    def call(method, params, request_id):
        return send(method, params, request_id)['result']

    def tool(name, arguments, request_id):
        result = call('tools/call', {'name': name, 'arguments': arguments}, request_id)
        assert not result.get('isError'), result
        return result.get('structuredContent') or json.loads(result['content'][0]['text'])

    deadline = time.monotonic() + 90
    while True:
        try:
            hello = call('initialize', {
                'protocolVersion': PROTOCOL, 'capabilities': {},
                'clientInfo': {'name': 'interlis-http-smoke', 'version': '1'},
            }, 1)
            break
        except (ConnectionError, urllib.error.URLError) as error:
            if not isinstance(getattr(error, 'reason', error), ConnectionError) or time.monotonic() >= deadline:
                raise
            time.sleep(1)

    headers['MCP-Protocol-Version'] = hello['protocolVersion']
    try:
        assert hello['serverInfo']['name'] == 'interlis-mcp', hello
        assert hello['protocolVersion'] == PROTOCOL, hello
        assert {'tools', 'resources', 'prompts'} <= hello['capabilities'].keys(), hello
        send('notifications/initialized')

        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
            proof = executor.submit(tool, 'verifyIliModel', {
                'modelText': MODEL, 'modelPurpose': 'VALIDATION', 'ruleProfile': 'CORE',
            }, 2)
            invalid = executor.submit(tool, 'validateIliModel', {
                'modelText': MODEL.replace('INTERLIS.XMLDate', 'MissingSmokeDomain'),
            }, 3)
            verified = proof.result()
            assert verified['status'] == 'VERIFIED', verified
            assert verified['afterCompilation']['valid'] is True, verified
            proofs = verified['constraintProofs']
            assert len(proofs) == 1 and proofs[0]['status'] == 'PASSED', verified
            assert any(case['targetViolationCount'] > 0 and case['passed']
                       for case in proofs[0]['verification']['cases']), verified
            assert 'updatedModelText' not in verified and 'candidateModelText' not in verified, verified
            diagnostic = invalid.result()
            assert diagnostic['valid'] is False, diagnostic
            assert any('MissingSmokeDomain' in message['message'] for message in diagnostic['messages']), diagnostic

        catalog = call('tools/list', {}, 4)
        assert len(catalog['tools']) == 21, catalog
        author = next(spec for spec in catalog['tools'] if spec['name'] == 'authorIliConstraint')
        assert len(author['inputSchema']['properties']['spec']['oneOf']) == 5, author
        args = {
            'modelText': MODEL.replace('      UNIQUE UniqueDay: day;\n', ''),
            'contextFqn': 'Smoke.Data.Item',
            'spec': {'kind': 'UNIQUE', 'name': 'UniqueDay', 'scope': 'GLOBAL', 'keyPaths': ['day']},
        }
        authored = tool(author['name'], args, 5)
        assert authored['status'] == 'GENERATED', authored
        assert authored['afterCompilation']['valid'] is True, authored
        cases = authored['constraintProofs'][0]['verification']['cases']
        assert any(case['passed'] and case['targetViolationCount'] > 0 for case in cases), cases
        assert authored['omittedSuccessfulTestXtfCount'] > 0, authored
        wrong_kind = args | {'spec': args['spec'] | {'restrictedPath': 'day'}}
        rejected = call('tools/call', {'name': author['name'], 'arguments': wrong_kind}, 6)
        assert rejected['isError'] and 'restrictedPath' in rejected['content'][0]['text'], rejected

        expected_resources = {
            'interlis://knowledge/handbook-rules': 'MDE-010',
            'interlis://knowledge/so-data-rules': 'DATA-001',
            'interlis://knowledge/model-corpus-index': 'indexedCount',
            'interlis://knowledge/agent-workflow': 'authorIliConstraint',
        }
        resources = call('resources/list', {}, 7)['resources']
        assert {resource['uri'] for resource in resources} == expected_resources.keys(), resources
        for index, (uri, expected) in enumerate(expected_resources.items(), start=8):
            resource = call('resources/read', {'uri': uri}, index)
            assert expected in resource['contents'][0]['text'], resource

        prompts = call('prompts/list', {}, 12)['prompts']
        assert {prompt['name'] for prompt in prompts} == {
            'interlis-modeling-agent', 'review-interlis-model', 'extend-interlis-model',
            'author-interlis-constraint',
        }, prompts
        for index, prompt in enumerate(prompts, start=13):
            result = call('prompts/get', {'name': prompt['name'], 'arguments': {}}, index)
            assert result['messages'], result
        error = send('missing/method', request_id=17)
        assert error['error']['code'] == -32601, error
    finally:
        request = urllib.request.Request(url, headers=headers, method='DELETE')
        with urllib.request.urlopen(request, timeout=10):
            pass


if __name__ == '__main__':
    probe(sys.argv[1])
    print('Streamable HTTP: parallel verification and compiler error, typed authoring, schema rejection, resources and prompts OK')
