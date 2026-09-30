// Offline tests only: mocked native results are never benchmark evidence.
const fs = require('node:fs'), os = require('node:os'), path = require('node:path');
const vm = require('node:vm'), assert = require('node:assert/strict');
const {execFileSync} = require('node:child_process');
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'workflow-recorder-'));
const source = fs.readFileSync(path.join(__dirname, 'native-recorder.js'), 'utf8');
const values = new Map(); let calls = 0, failResponse = false, failRequest = false;
const raw = {content:[{type:'text',text:('ä😀\'" $() `never-execute`\r\n'+'x'.repeat(8173)).repeat(400)}]};
assert(Buffer.byteLength(JSON.stringify(raw)) > 3 * 1024 * 1024);
const tools = {exec_command:async ({cmd}) => {
  if (failResponse && cmd.includes('-response.json')) {failResponse=false; throw new Error('disk unavailable');}
  if (failRequest && cmd.includes('-request.json')) {failRequest=false; return {exit_code:1,output:'disk unavailable'};}
  return {exit_code:0,output:execFileSync('/bin/sh',['-c',cmd],{encoding:'utf8',maxBuffer:1024*1024})};
}, native:async payload => {
  calls++;
  const request=JSON.parse(fs.readFileSync(path.join(root,'logs',String(calls).padStart(3,'0')+'-request.json'),'utf8'));
  assert.deepEqual(request.arguments,JSON.parse(JSON.stringify(payload)));
  if(payload.throw)throw new Error('transport closed');
  return raw;
}};
// Structured-clone store emulates host serialization rather than sharing JS objects.
const recorder = vm.runInNewContext(source,{tools,load:k=>values.has(k)?structuredClone(values.get(k)):undefined,
  store:(k,v)=>values.set(k,structuredClone(v))})(root);
(async()=>{
  const original={spec:{value:'ü😀\' "$()"'},counter:1};const frozen=JSON.stringify(original);
  const result=await recorder.call('native',original);
  assert.deepEqual(result,raw); assert.equal(JSON.stringify(original),frozen);assert.equal(calls,1);
  assert.deepEqual(JSON.parse(fs.readFileSync(path.join(root,'logs/001-response.json'))),raw);
  failResponse=true;
  await assert.rejects(()=>recorder.call('native',{}),/disk unavailable/);
  assert.equal(calls,2);assert(!fs.existsSync(path.join(root,'logs/002-response.json')));
  assert.deepEqual(values.get('workflow-native-recorder:'+root).pending.raw,raw);
  await assert.rejects(()=>recorder.call('native',{}),/pending record/);assert.equal(calls,2);
  assert.deepEqual(await recorder.persist(),raw);assert.equal(calls,2);
  assert.deepEqual(JSON.parse(fs.readFileSync(path.join(root,'logs/002-response.json'))),raw);
  await assert.rejects(()=>recorder.call('native',{throw:true}),/NATIVE_TOOL_EXCEPTION/);
  assert.equal(calls,3);assert(!fs.existsSync(path.join(root,'logs/003-response.json')));
  assert.match(JSON.parse(fs.readFileSync(path.join(root,'logs/003-tool-exception.json'))).error,/transport closed/);
  failRequest=true;
  await assert.rejects(()=>recorder.call('native',{}),/ARCHIVE_ERROR/);assert.equal(calls,3);
  await assert.rejects(()=>recorder.persist(),/no completed/);assert.equal(calls,3);
  console.log(JSON.stringify({status:'PASS',nativeCalls:calls,responseBytes:Buffer.byteLength(JSON.stringify(raw))}));
})().catch(e=>{console.error(e);process.exitCode=1;}).finally(()=>fs.rmSync(root,{recursive:true,force:true}));
