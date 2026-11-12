// Real JXA/Cocoa/curl integration on a macOS runner; only calls this loopback fixture.
const http=require('node:http');
const assert=require('node:assert/strict');
const {execFile}=require('node:child_process');
const os=require('node:os');
const path=require('node:path');
const fs=require('node:fs/promises');
async function main(){
  const received=[];
  const server=http.createServer((request,response)=>{
    let body='';request.setEncoding('utf8');request.on('data',part=>body+=part);
    request.on('end',()=>{
      received.push({path:request.url,authorization:request.headers.authorization,body:JSON.parse(body)});
      response.setHeader('Content-Type','application/json; charset=utf-8');
      if(request.url.endsWith('/collector-person'))response.end(JSON.stringify({result:'matched',department:'研发部'}));
      else{response.statusCode=202;response.end(JSON.stringify({result:'pending_review'}));}
    });
  });
  const directory=await fs.mkdtemp(path.join(os.tmpdir(),'wfq-mac-runtime-'));
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  try{
    const args=['-l','JavaScript','scripts/collection-launchers/test-macos-bridge.js','backend/src/main/resources/windows-collection/CollectMac.js',directory,`http://127.0.0.1:${server.address().port}`];
    const output=await new Promise((resolve,reject)=>execFile('/usr/bin/osascript',args,{timeout:30000},(error,stdout,stderr)=>error?reject(new Error(stderr||error.message)):resolve(stdout)));
    assert.match(output,/PASS/);assert.equal(received.length,2);
    assert.ok(received.every(item=>item.authorization==='Bearer fixture-only'));
    assert.deepEqual(received[0].body,{name:'张三'});
    assert.equal(received[1].body.reportedDepartment,'研发部');
    assert.equal(received[1].body.reportedUserName,'张三');
    console.log('PASS: actual Cocoa progress, NSTask output, UTF-8 department lookup and asset submission to loopback fixture');
  }finally{await new Promise(resolve=>server.close(resolve));await fs.rm(directory,{recursive:true,force:true});}
}
main().catch(error=>{console.error(error);process.exitCode=1;});
