(async function nativeCall(tool, payload) {
  // Generic evidence recorder. The caller supplies the complete semantic payload.
  // All MCP traffic uses the native Codex tools object; there is no MCP client here.
  const output = "{{OUTPUT}}";
  const input = "{{INPUT}}";
  const prefix = "mcp__interlis_mcp_eval__";
  const authors = ["authorIliMandatoryConstraint", "authorIliPlausibilityConstraint",
    "authorIliExistenceConstraint", "authorIliSetConstraint", "authorIliUniqueConstraint",
    "generateIliConstraintFromDecisionTable"];
  const name = tool.startsWith(prefix) ? tool.slice(prefix.length) : "";
  if (![...authors, "listConstraintFunctions", "resolveConstraintPath"].includes(name))
    throw new Error("Recorder: forbidden native tool");
  const quote = value => "'" + String(value).replace(/'/g, "'\\''") + "'";
  const stateKey = "native-recorder:" + output;
  const state = load(stateKey) || {index: 0, authored: false, transportRetries: 0, calls: []};
  async function shell(script, args) {
    const r = await tools.exec_command({cmd: "python3 -c " + quote(script) + " " + args.map(quote).join(" "), max_output_tokens: 3000});
    if (r.exit_code !== 0) throw new Error("Recorder persistence failed: " + r.output);
    return r.output;
  }
  async function save(path, value, literal = false) {
    const data = literal ? value : JSON.stringify(value);
    const script = "import pathlib,sys; p=pathlib.Path(sys.argv[1]); p.parent.mkdir(parents=True,exist_ok=True); f=p.open(sys.argv[2],encoding='utf-8'); f.write(sys.argv[3]); f.close()";
    for (let i = 0; i < data.length || i === 0; i += 24000)
      await shell(script, [path, i === 0 ? "w" : "a", data.slice(i, i + 24000)]);
  }
  if (!state.inputFiles) {
    const script = "import pathlib,hashlib,json,sys; p=pathlib.Path(sys.argv[1]); h=json.loads((p/'hashes.json').read_text()); actual={n:hashlib.sha256((p/n).read_bytes()).hexdigest() for n in ['model.ili','requirement.de.md']}; assert actual==h, 'Input hash mismatch'; print(json.dumps({str(p/n):v for n,v in actual.items()}))";
    state.inputFiles = JSON.parse(await shell(script, [input]));
    store(stateKey, state);
  }
  if (state.index === 0 && name !== "listConstraintFunctions")
    throw new Error("Recorder: call the canary first");
  if (authors.includes(name) && state.authored)
    throw new Error("Recorder: exactly one authoring attempt is permitted");
  if (authors.includes(name)) state.authored = true;
  const request = {tool, payload};
  const wire = JSON.stringify(request);
  let retryOf = null;
  while (true) {
    const index = ++state.index;
    store(stateKey, state);
    const folder = output + "/calls/" + String(index).padStart(2, "0");
    await save(folder + "/request.json", request);
    if (authors.includes(name)) await save(output + "/request.json", request);
    const startedAt = new Date().toISOString();
    let raw, thrown;
    try { raw = await tools[tool](JSON.parse(wire).payload); }
    catch (e) { thrown = String(e); }
    const endedAt = new Date().toISOString();
    const errorText = thrown || (raw?.isError ? JSON.stringify(raw.content) : "");
    const transient = /Transport closed|connection reset|timed out|timeout/i.test(errorText);
    if (thrown || transient) {
      await save(folder + "/transport-error.json", {message: errorText, transient, rawResult: raw ?? null});
      await save(folder + "/event.json", {startedAt, endedAt, retryOf, errorKind: "TRANSPORT"});
      state.calls.push({tool, folder, startedAt, endedAt, transportError: true});
      store(stateKey, state);
      if (!transient || state.transportRetries >= 1) throw new Error("Recorder: native transport failed; complete evidence preserved");
      state.transportRetries++;
      retryOf = index;
      store(stateKey, state);
      continue;
    }
    await save(folder + "/raw-result.json", raw);
    await save(folder + "/event.json", {startedAt, endedAt, retryOf, errorKind: null});
    state.calls.push({tool, folder, startedAt, endedAt, transportError: false});
    store(stateKey, state);
    if (name === "listConstraintFunctions") await save(output + "/runtime-canary.json", raw);
    let data = raw.structuredContent;
    if (!data) for (const item of raw.content || []) if (item.type === "text") {
      try { data = JSON.parse(item.text); break; } catch (_) {}
    }
    if (authors.includes(name)) {
      await save(output + "/raw-result.json", raw);
      const candidate = data?.updatedModelText || data?.candidateModelText;
      if (candidate) await save(output + "/candidate.ili", candidate, true);
      await save(output + "/completion.json", {inputFiles: state.inputFiles, calls: state.calls,
        authoringStatus: data?.status ?? null, outputDirectory: output,
        evidenceStatus: data?.status || raw.isError ? "RECORDED" : "EVIDENCE_INCOMPLETE"});
    }
    return raw;
  }
})
