(function createRecorder(output) {
  // Evaluated in functions.exec: native tools, store and load are supplied by the host.
  // No model interpretation, payload repair, retries or MCP client lives here.
  const key = 'workflow-native-recorder:' + output;
  const quote = s => "'" + String(s).replace(/'/g, "'\\''") + "'";
  async function python(script, args) {
    let result;
    try {
      result = await tools.exec_command({
        cmd: 'python3 -c ' + quote(script) + ' ' + args.map(quote).join(' '), max_output_tokens: 1000
      });
    } catch (error) { throw new Error('ARCHIVE_ERROR: ' + String(error)); }
    if (result.exit_code !== 0) throw new Error('ARCHIVE_ERROR: ' + result.output);
    return JSON.parse(result.output);
  }
  function* chunks(text) {
    let part = '', bytes = 0;
    for (const char of text) {
      const cp = char.codePointAt(0);
      const size = cp <= 127 ? 1 : cp <= 2047 ? 2 : cp <= 65535 ? 3 : 4;
      if (bytes + size > 8192) { yield part; part = ''; bytes = 0; }
      part += char; bytes += size;
    }
    if (part || !text) yield part;
  }
  async function save(path, wire) {
    const receipts = [];
    for (const chunk of chunks(wire)) {
      receipts.push(await python(
        "import pathlib,sys,hashlib,json; p=pathlib.Path(sys.argv[1]+'.tmp'); p.parent.mkdir(parents=True,exist_ok=True); b=sys.argv[3].encode('utf-8'); assert len(b)<=8192; f=p.open(sys.argv[2]); f.write(b); f.close(); print(json.dumps({'size':len(b),'sha256':hashlib.sha256(b).hexdigest()}))",
        [path, receipts.length ? 'ab' : 'wb', chunk]));
    }
    // Send receipts separately as bounded chunks as well; never put a large response
    // or a potentially large receipt list in one process argument.
    const check = path + '.receipts.tmp';
    let first = true;
    for (const chunk of chunks(JSON.stringify(receipts))) {
      await python("import pathlib,sys,json; p=pathlib.Path(sys.argv[1]); f=p.open(sys.argv[2],encoding='utf-8'); f.write(sys.argv[3]); f.close(); print('{}')",
        [check, first ? 'w' : 'a', chunk]);
      first = false;
    }
    return python(`import pathlib,sys,json,hashlib
p=pathlib.Path(sys.argv[1]); temp=pathlib.Path(str(p)+'.tmp'); checks=pathlib.Path(str(p)+'.receipts.tmp')
b=temp.read_bytes(); expected=json.loads(checks.read_text()); offset=0
for c in expected:
 part=b[offset:offset+c['size']]; assert len(part)==c['size'] and hashlib.sha256(part).hexdigest()==c['sha256']; offset+=c['size']
assert offset==len(b)
json.loads(b.decode('utf-8')); digest=hashlib.sha256(b).hexdigest()
temp.replace(p); checks.unlink(); print(json.dumps({'sha256':digest,'bytes':len(b)}))`, [path]);
  }
  async function persist() {
    const state = load(key);
    const entry = state?.pending;
    if (!entry || !['RESPONSE', 'TOOL_EXCEPTION'].includes(entry.outcome))
      throw new Error('ARCHIVE_ERROR: no completed native invocation to persist');
    const folder = output + '/logs/' + String(entry.index).padStart(3, '0');
    const receipt = await save(folder + (entry.outcome === 'RESPONSE' ? '-response.json' : '-tool-exception.json'), entry.wire);
    await save(folder + '-event.json', JSON.stringify({
      startedAt: entry.startedAt, endedAt: entry.endedAt, outcome: entry.outcome,
      request: entry.requestReceipt, response: receipt
    }));
    // Keep the raw result even after persistence. An archive exception never replaces it.
    state.pending = null;
    state.last = entry;
    store(key, state);
    if (entry.outcome === 'TOOL_EXCEPTION') throw new Error('NATIVE_TOOL_EXCEPTION: ' + entry.error);
    return entry.raw;
  }
  async function call(tool, payload) {
    const state = load(key) || {index: 0, pending: null};
    if (state.pending) throw new Error('ARCHIVE_ERROR: resolve the pending record before another native call');
    // Freeze exact JSON arguments before invoking the native tool.
    const wire = JSON.stringify({tool, arguments: payload, timestamp: new Date().toISOString()});
    const entry = {index: ++state.index, outcome: 'NOT_INVOKED'};
    state.pending = entry; store(key, state);
    const folder = output + '/logs/' + String(entry.index).padStart(3, '0');
    entry.requestReceipt = await save(folder + '-request.json', wire);
    entry.startedAt = new Date().toISOString(); entry.outcome = 'IN_FLIGHT'; store(key, state);
    try {
      entry.raw = await tools[tool](JSON.parse(wire).arguments);
      entry.outcome = 'RESPONSE';
    } catch (error) {
      entry.error = String(error); entry.outcome = 'TOOL_EXCEPTION';
    }
    entry.endedAt = new Date().toISOString();
    store(key, state); // Before serialization and every response-related file operation.
    entry.wire = JSON.stringify(entry.outcome === 'RESPONSE' ? entry.raw : {error: entry.error});
    store(key, state);
    return persist();
  }
  return {call, persist};
})
