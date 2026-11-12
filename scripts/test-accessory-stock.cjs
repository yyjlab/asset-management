const assert=require('node:assert/strict');
const path=require('node:path');
const {createRequire}=require('node:module');
const {pathToFileURL}=require('node:url');
const root=path.resolve(__dirname,'../frontend');
const localRequire=createRequire(path.join(root,'package.json'));
const {chromium}=require(process.argv[2]||'playwright');
async function main(){
  const {preview}=await import(pathToFileURL(localRequire.resolve('vite')).href);
  const server=await preview({root,configFile:false,preview:{host:'127.0.0.1',port:0},logLevel:'error'});
  let browser;
  try{
    browser=await chromium.launch({channel:'msedge',headless:true});const page=await browser.newPage();page.setDefaultTimeout(10000);
    const errors=[];page.on('pageerror',e=>{errors.push(e.message);console.error('PAGEERROR',e.message)});
    const company={id:1,type:'COMPANY',name:'微丰启'},location={id:2,type:'LOCATION',name:'公司'},model={id:3,type:'MODEL',name:'ThinkPad L13'},category={id:4,type:'CATEGORY',name:'笔记本电脑',assetProfile:'COMPUTER'},status={id:5,type:'STATUS',name:'当前可用'};
    const stock={id:7,version:0,type:'鼠标',brand:'罗技',companyId:1,company:'微丰启',locationId:2,location:'公司',total:10,available:6,paired:1,used:3,scrapped:0};
    const stockRows=[stock];
    const asset={id:10,version:0,assetTag:'202611120001',name:'测试电脑',company,location,model,category,status,checkedOut:false,assignedTo:null,relatedDevices:[],accessories:[],imageUrls:[],customParameters:[],boundDisplays:[],domainJoinStatus:'UNKNOWN',createdAt:'2026-11-12T09:00:00',updatedAt:'2026-11-12T09:00:00'};
    let saved=null;
    await page.route('**/api/**',async route=>{
      const req=route.request(),url=new URL(req.url()),p=url.pathname;let data=[];
      if(p==='/api/auth/me')data={id:1,username:'admin',displayName:'admin',role:'SUPER_ADMIN',permissions:['ASSET_VIEW','ASSET_EDIT','ASSET_CREATE'],canManageUsers:true,dataScope:'ALL',departments:[],hasAvatar:false,dingtalkLogin:false};
      else if(p==='/api/auth/csrf')data={headerName:'X-CSRF-TOKEN',token:'test-only'};
      else if(p==='/api/dashboard')data=null;
      else if(p==='/api/assets/summary')data={total:1,available:1,checkedOut:0,maintenance:0,scrapped:0};
      else if(p==='/api/assets')data=[asset];
      else if(p==='/api/lookups')data=[company,location,model,category,status];
      else if(p==='/api/assets/10'&&req.method()==='PUT'){saved=req.postDataJSON();data={...asset,...saved};}
      else if(p==='/api/foundation/accessories/choices')data=[];
      else if(p==='/api/accessory-stock/choices'||p==='/api/accessory-stock')data=stockRows;
      else if(p==='/api/accessory-stock/7')data={allocations:[{assetId:10,assetTag:asset.assetTag,name:asset.name,assignedTo:'测试员工',quantity:3,checkedOut:true}],events:[{time:'2026-11-12T09:00:00',operator:'admin',summary:'配件入库',changes:'{"数量":10}'}]};
      else if(p==='/api/accessory-stock/7/adjust'){assert.equal(req.headers()['x-csrf-token'],'test-only');const payload=req.postDataJSON();assert.equal(payload.version,stock.version);stock.total+=payload.quantity;stock.available+=payload.quantity;stock.version++;data=stock;}
      else if(p.includes('domain-statuses'))data=[];
      else if(p==='/api/assignees')data=[];
      else {await route.fulfill({status:503,json:{message:'Outside isolated stock test'}});return;}
      await route.fulfill({json:data});
    });
    await page.goto(server.resolvedUrls.local[0],{waitUntil:'domcontentloaded',timeout:30000});
    await page.getByRole('button',{name:'资产',exact:true}).click();
    await page.getByRole('button',{name:'配件',exact:true}).click();
    const inventory=page.getByRole('region',{name:'配件库存'});await inventory.getByText('罗技',{exact:true}).waitFor();
    assert.equal(await inventory.locator('.stock-metric').count(),5);
    const colors=await inventory.locator('.stock-metric strong').evaluateAll(nodes=>nodes.map(node=>getComputedStyle(node).color));assert.ok(new Set(colors).size>=4,'summary states should be visually distinct');
    for(const theme of ['light','dark'])for(const size of [{width:1440,height:900},{width:390,height:844}]){
      await page.evaluate(value=>{document.documentElement.dataset.theme=value},theme);
      await page.setViewportSize(size);assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
      if(process.argv.includes('--preview') && size.width===1440)console.log('SCREENSHOT:'+ (await inventory.screenshot({type:'jpeg',quality:65})).toString('base64'));
    }
    await page.evaluate(()=>{document.documentElement.dataset.theme='light'});
    await inventory.getByRole('button',{name:'明细',exact:true}).click();
    await page.getByRole('dialog').getByText('测试员工',{exact:true}).waitFor();
    await page.getByRole('button',{name:'关闭配件窗口'}).click();
    await inventory.getByRole('button',{name:'补货',exact:true}).click();
    await page.getByRole('dialog').getByLabel('数量',{exact:true}).fill('2');
    await page.getByRole('dialog').getByRole('button',{name:'确认入库'}).click();
    await page.getByRole('dialog').waitFor({state:'hidden'});assert.equal(stock.total,12);
    await page.setViewportSize({width:1440,height:900});
    await page.getByRole('button',{name:'全部资产',exact:true}).click();
    await page.getByRole('button',{name:/编辑/}).first().waitFor().catch(async e=>{console.error((await page.locator('body').innerText()).slice(0,4000));throw e});
    await page.getByRole('button',{name:/编辑/}).first().click();
    await page.getByRole('button',{name:'选择配件',exact:true}).click();
    const picker=page.getByRole('dialog',{name:'选择随附配件'});await picker.getByText('罗技',{exact:true}).waitFor();
    await picker.getByRole('checkbox').check();
    for(const size of [{width:1440,height:900},{width:390,height:844}]){
      await page.setViewportSize(size);
      const rect=await picker.locator('.accessory-choice').first().boundingBox();assert.ok(Math.abs(rect.width-rect.height)<2,'brand choices must be square');
      assert.ok(rect.width<=132,'choice cards must remain compact');
      const dialogRect=await picker.boundingBox();assert.ok(dialogRect.width<=362,'single choice should use a narrow dialog');
      const overflow=await picker.evaluate(e=>e.scrollWidth>e.clientWidth);assert.equal(overflow,false,'picker must fit mobile');
      if(process.argv.includes('--preview'))console.log('SCREENSHOT:'+(await picker.screenshot({type:'jpeg',quality:65})).toString('base64'));
    }
    await picker.getByRole('button',{name:'添加（1）',exact:true}).click();
    assert.equal(await page.getByRole('dialog',{name:'选择随附配件'}).count(),0);
    assert.equal(await page.getByLabel('鼠标 1 数量').inputValue(),'1');
    const selectedRect=await page.locator('.accessory-selected').first().boundingBox();assert.ok(selectedRect.width<=158&&selectedRect.height<=165,'selected accessory must not have oversized whitespace');
    stockRows.push({...stock,id:8,type:'键盘',brand:'双飞燕',available:3});
    await page.getByRole('button',{name:'选择配件',exact:true}).click();
    await picker.getByText('双飞燕',{exact:true}).waitFor();
    assert.equal(await picker.locator('.accessory-choice').count(),2);
    assert.ok((await picker.boundingBox()).width<=482,'multiple brands still need a compact dialog');
    await picker.getByRole('button',{name:'键盘',exact:true}).click();
    assert.equal(await picker.locator('.accessory-choice').count(),1);
    await picker.getByRole('button',{name:'关闭配件选择',exact:true}).click();
    await page.getByRole('button',{name:'保存修改',exact:true}).click();
    await page.waitForFunction(()=>!document.querySelector('.asset-form')) .catch(()=>{});
    assert.equal(saved.relatedDevices[0].stockId,7);assert.equal(saved.relatedDevices[0].model,'罗技');
    assert.deepEqual(errors,[]);
    console.log('PASS accessory inventory: menu, totals, restock, usage details, square picker desktop/mobile, saved stock identity.');
  }finally{await browser?.close();await server.httpServer.close();}
}
main().catch(e=>{console.error(e);process.exitCode=1});
