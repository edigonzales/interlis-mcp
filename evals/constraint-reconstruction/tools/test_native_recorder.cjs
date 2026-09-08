// Offline recorder contract test. These mock results are never benchmark results.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const crypto = require('node:crypto');
const {execFileSync} = require('node:child_process');
const assert = require('node:assert/strict');
const root = process.argv[2];
const source = fs.readFileSync(process.argv[3], 'utf8');
const input = path.join(root, 'input'), output = path.join(root, 'output');
fs.mkdirSync(input, {recursive:true});
const model = 'model fixture\n';
fs.writeFileSync(path.join(input, 'model.ili'), model);
fs.writeFileSync(path.join(input, 'requirement.de.md'), 'requirement fixture\n');
const hashes = Object.fromEntries(['model.ili','requirement.de.md'].map(n => [n,crypto.createHash('sha256').update(fs.readFileSync(path.join(input,n))).digest('hex')]));
fs.writeFileSync(path.join(input,'hashes.json'),JSON.stringify(hashes));
const values = new Map(), attempts = [];
const identity = Object.fromEntries(['buildId','jarSha256','dependencySnapshotSha256','compilerVersion','validatorVersion','ioxIliVersion'].map(k => [k,'fixture']));
Object.assign(identity,{verified:true,mode:'IMMUTABLE_BENCHMARK'});
const prefix='mcp__interlis_mcp_eval__';
const candidate=('ä "quoted" \' apostrophe $(echo never-execute) `echo never-execute`\n').repeat(6000);
const raw={content:[{type:'text',text:JSON.stringify({status:'GENERATED',updatedModelText:candidate})}]};
const tools={exec_command:async ({cmd})=>({exit_code:0,output:execFileSync('/bin/sh',['-c',cmd],{encoding:'utf8',maxBuffer:8*1024*1024})})};
function before(name,payload) {
  const index=attempts.length+1;
  const p=path.join(output,'calls',String(index).padStart(2,'0'),'request.json');
  assert.deepEqual(JSON.parse(fs.readFileSync(p,'utf8')),JSON.parse(JSON.stringify({tool:prefix+name,payload})));
  attempts.push({tool:prefix+name,payload});
}
tools[prefix+'listConstraintFunctions']=async payload=>{
  before('listConstraintFunctions',payload);
  if(attempts.length===1)throw new Error('Transport closed');
  return {structuredContent:{runtimeIdentity:identity}};
};
tools[prefix+'authorIliMandatoryConstraint']=async payload=>{before('authorIliMandatoryConstraint',payload);return raw;};
const recorder=vm.runInNewContext(source.replaceAll('{{INPUT}}',input).replaceAll('{{OUTPUT}}',output),{
  tools,load:k=>values.get(k),store:(k,v)=>values.set(k,v)});
(async()=>{
  await recorder(prefix+'listConstraintFunctions',{});
  assert.equal(attempts.length,2);
  assert.deepEqual(attempts[0],attempts[1]);
  const payload={modelText:model,spec:{kind:'MANDATORY',literal:'a\'b "$()" ä'}};
  const result=await recorder(prefix+'authorIliMandatoryConstraint',payload);
  assert.deepEqual(result,raw);
  assert.equal(fs.readFileSync(path.join(output,'candidate.ili'),'utf8'),candidate);
  assert.deepEqual(JSON.parse(fs.readFileSync(path.join(output,'raw-result.json'),'utf8')),raw);
  assert.deepEqual(JSON.parse(fs.readFileSync(path.join(output,'request.json'),'utf8')),{tool:prefix+'authorIliMandatoryConstraint',payload});
  await assert.rejects(()=>recorder(prefix+'authorIliMandatoryConstraint',payload),/exactly one/);
  assert.equal(attempts.length,3);
  console.log(JSON.stringify({status:'PASS',mockCalls:attempts.length,output,identity}));
})().catch(e=>{console.error(e);process.exitCode=1;});
