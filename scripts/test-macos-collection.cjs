const test = require('node:test');
const assert = require('node:assert/strict');
const { makeInventory, parseDisplays, parseDisks, validEndpoint, departmentSuggestion, departmentRequest, collectionFailure, collectionResponse } = require('../backend/src/main/resources/windows-collection/CollectMac.js');
const uuid = '12345678-1234-1234-1234-123456789abc';
test('Apple Silicon and Intel report actual hardware without Python', () => {
  for (const model of ['Mac14,7', 'MacBookPro16,1']) {
    const result = makeInventory({ SPHardwareDataType: [{ platform_UUID: uuid.toUpperCase(), machine_model: model, machine_name: 'MacBook Pro', chip_type: 'Apple M2', physical_memory: '16 GB', serial_number: 'MAC-SN' }], SPDisplaysDataType: [] }, [], []);
    assert.equal(result.hardwareUuid, uuid); assert.equal(result.collection.computerType, 'laptop');
    assert.equal(result.snapshot.model, model); assert.equal(result.snapshot.cpu, 'Apple M2');
  }
});
test('counts multiple monitors separately, excludes offline entries and does not infer size from pixels', () => {
  const result = parseDisplays({ SPDisplaysDataType: [{ spdisplays_ndrvs: [
    { _name: 'Color LCD', spdisplays_connection_type: 'spdisplays_internal', spdisplays_resolution: '3024 x 1964' },
    { _name: 'DELL U2720Q', spdisplays_connection_type: 'HDMI', 'spdisplays_display-serial-number': 'SAME', 'spdisplays_display-size': '27-inch' },
    { _name: 'DELL U2720Q', spdisplays_connection_type: 'DisplayPort', 'spdisplays_display-serial-number': 'SAME' },
    { _name: 'Offline', spdisplays_online: 'No' }
  ] }] });
  assert.equal(result.monitors.length, 2); assert.ok(result.monitors.every(monitor => monitor.connection !== 'internal'));
  assert.equal(result.monitors[0].sizeInches, 27); assert.equal(result.monitors[1].sizeInches, null); assert.equal(result.status, 'partial');
  assert.equal(parseDisplays({}).status, 'unavailable');
});
test('only known internal physical disk protocols are included', () => {
  const result = parseDisks([
    { Internal: true, TotalSize: 512000000000, MediaName: 'APPLE SSD', BusProtocol: 'Apple Fabric', VirtualOrPhysical: 'Physical' },
    { Internal: false, TotalSize: 64000000000, BusProtocol: 'USB', MediaName: 'USB' },
    { Internal: true, TotalSize: 512000000000, BusProtocol: 'PCI-Express', VirtualOrPhysical: 'Virtual' }
  ]);
  assert.deepEqual(result, [{ model: 'APPLE SSD', capacityBytes: 512000000000, busType: 'AppleFabric' }]);
});
test('mouse and keyboard collection is not executed or uploaded', () => {
  const source = require('node:fs').readFileSync(require.resolve('../backend/src/main/resources/windows-collection/CollectMac.js'), 'utf8');
  assert.doesNotMatch(source, /IOHIDDevice|peripheralBrands|mouseBrands|keyboardBrands/);
  const result = makeInventory({ SPHardwareDataType: [{ platform_UUID: uuid, machine_name: 'Mac mini', machine_model: 'Macmini9,1' }], SPDisplaysDataType: [] }, []);
  assert.equal(result.collection.computerType, 'desktop'); assert.equal('mouseBrands' in result.collection, false);
});
test('invalid UUID and credential-bearing or redirected endpoint paths are rejected', () => {
  assert.throws(() => makeInventory({ SPHardwareDataType: [{ platform_UUID: '00000000-0000-0000-0000-000000000000' }] }, [], []));
  assert.equal(validEndpoint('http://asset.wfq.com/api/integrations/ad-deployment/v1/windows-intakes'), true);
  for (const url of ['http://a:b@asset.wfq.com/api/integrations/ad-deployment/v1/windows-intakes', 'http://asset.wfq.com/evil', 'http://asset.wfq.com/api/integrations/ad-deployment/v1/windows-intakes?token=secret']) assert.equal(validEndpoint(url), false);
});
test('exact name lookup prefills department and remains an editable suggestion', () => {
  let queried='';
  const suggestion=departmentSuggestion(' 张三 ',{},name=>{queried=name;return {result:'matched',department:'研发部'};});
  assert.equal(queried,'张三');assert.equal(suggestion.department,'研发部');
  assert.match(suggestion.message,/确认或修改/);
  const saved={reportedUserName:'张三',reportedDepartment:'运营部'};
  assert.equal(departmentSuggestion('张三',saved,()=>assert.fail('Saved manual department must not be overwritten')).department,'运营部');
  assert.deepEqual(saved,{reportedUserName:'张三',reportedDepartment:'运营部'});
});
test('a changed person does not reuse another employee department and lookup failures allow manual input', () => {
  const saved={reportedUserName:'旧姓名',reportedDepartment:'旧部门'};
  for(const result of [{result:'not_found'},{result:'empty_department'},{result:'matched',department:'x'.repeat(121)},{result:'matched',department:42}]){
    const value=departmentSuggestion('新姓名',saved,()=>result);assert.equal(value.department,'');assert.match(value.message,/手动填写/);
  }
  const unavailable=departmentSuggestion('新姓名',saved,()=>{throw new Error('Timeout or HTTP 401');});
  assert.equal(unavailable.department,'');assert.match(unavailable.message,/手动填写/);
  for(const name of ['',' ','x'.repeat(121)])assert.throws(()=>departmentSuggestion(name,{},()=>assert.fail('Invalid names must not be queried')));
});
test('department request stays on configured origin and sends only the exact name', () => {
  const config={endpoint:'http://asset.example:8081/api/integrations/ad-deployment/v1/windows-intakes',token:'test-only'};
  const request=departmentRequest(config,' 研发测试 "姓名" ');
  const lines=request.trim().split('\n').map(line=>JSON.parse(line.slice(line.indexOf('=')+1).trim()));
  assert.equal(lines[0],'http://asset.example:8081/api/integrations/ad-deployment/v1/collector-person');
  assert.equal(lines[1],'Authorization: Bearer test-only');
  assert.deepEqual(JSON.parse(lines[2]),{name:'研发测试 "姓名"'});
  assert.throws(()=>departmentRequest({...config,endpoint:'http://evil.example/other'},'姓名'));
});
test('real curl stdin configuration preserves UTF-8 department and never puts credentials in command arguments', async () => {
  const http=require('node:http'),{execFile}=require('node:child_process');
  let received;
  const server=http.createServer((request,response)=>{let body='';request.setEncoding('utf8');request.on('data',part=>body+=part);request.on('end',()=>{
    received={url:request.url,authorization:request.headers.authorization,body:JSON.parse(body)};
    response.setHeader('Content-Type','application/json');response.end(JSON.stringify({result:'matched',department:'研发部'}));
  });});
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  try{
    const config={endpoint:`http://127.0.0.1:${server.address().port}/api/integrations/ad-deployment/v1/windows-intakes`,token:'test-only'};
    const args=['--disable','--config','-','--silent','--show-error','--noproxy','*','--connect-timeout','3','--max-time','5','--max-redirs','0','--request','POST','--header','Content-Type: application/json; charset=utf-8'];
    const value=await new Promise((resolve,reject)=>{const child=execFile(process.platform==='win32'?'curl.exe':'/usr/bin/curl',args,{encoding:'utf8',timeout:8000},(error,stdout)=>error?reject(error):resolve(JSON.parse(stdout)));child.stdin.end(departmentRequest(config,' 姓名"\\测试 '),'utf8');});
    assert.equal(value.department,'研发部');assert.deepEqual(received,{url:'/api/integrations/ad-deployment/v1/collector-person',authorization:'Bearer test-only',body:{name:'姓名"\\测试'}});
    assert.ok(!args.join(' ').includes(config.token));
  }finally{await new Promise(resolve=>server.close(resolve));}
});
test('Mac failures identify DNS, connection, timeout, TLS and HTTP authentication without exposing secrets', () => {
  for(const [code,expected] of [[6,/域名/],[7,/连接/],[28,/超时/],[60,/证书/]]){
    const detail=collectionFailure('/usr/bin/curl',code,'fixture-secret\nreason','fixture-secret');
    assert.match(detail,expected);assert.ok(!detail.includes('fixture-secret'));assert.ok(!detail.includes('\n'));
  }
  assert.throws(()=>collectionResponse('{"message":"expired"}\n401',[200,202]),/凭据.*HTTP 401/);
  assert.throws(()=>collectionResponse('{"error":"missing"}\n404',[200]),/接口不存在/);
  assert.throws(()=>collectionResponse('<html>proxy</html>\n502',[200]),/反向代理/);
  assert.throws(()=>collectionResponse('{}',[200]),/HTTP 回报/);
  assert.deepEqual(collectionResponse('{"result":"matched","department":"研发部"}\n200',[200]),{result:'matched',department:'研发部'});
  const suggestion=departmentSuggestion('张三',{},()=>{throw new Error('DNS unavailable');});
  assert.match(suggestion.message,/DNS unavailable/);assert.match(suggestion.message,/手动填写/);
});
