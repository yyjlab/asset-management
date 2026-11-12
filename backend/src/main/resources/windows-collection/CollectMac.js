/* Pure parsers are exported for fixture tests; macOS runs this file with osascript/JXA. */
function text(value) { return value == null ? '' : String(value).trim(); }
function serial(value) { var result=text(value); return /^(0+|unknown|none|n\/a)$/i.test(result) ? null : result || null; }
function parseDisplays(profiler) {
    var monitors=[], status='ok';
    if(!Array.isArray(profiler.SPDisplaysDataType))return {monitors:[],status:'unavailable'};
    (profiler.SPDisplaysDataType || []).forEach(function(gpu,gpuIndex){
        (gpu.spdisplays_ndrvs || []).forEach(function(display,index){
            if(display.spdisplays_online && !/yes|online|是/i.test(display.spdisplays_online))return;
            var description=text(display.spdisplays_display_type)+' '+text(display.spdisplays_connection_type);
            var internal=/built.?in|internal|内建|内置/i.test(description) || /yes/i.test(text(display.spdisplays_builtin));
            if(internal)return;
            var external=/external|hdmi|displayport|dvi|vga|thunderbolt|外接/i.test(description);
            var connection=internal?'internal':external?'external':'unknown';
            var name=text(display._name), brand=null;
            var manufacturer=text(display['_spdisplays_display-vendor-id'] || display['spdisplays_vendor-id'] || display['spdisplays_display-vendor-id']).toLowerCase().replace(/^0x/,'');
            while(manufacturer && manufacturer.length<4)manufacturer='0'+manufacturer;
            var brands={'10ac':'Dell','0610':'Apple','22f0':'HP','30e4':'Lenovo','1e6d':'LG','4c2d':'Samsung'};
            brand=brands[manufacturer] || null;
            if(!brand){var match=name.match(/\b(Dell|HP|Lenovo|LG|Samsung|Acer|ASUS|Apple|BenQ|Philips|AOC)\b/i);if(match)brand=match[1];}
            var sn=serial(display['_spdisplays_display-serial-number'] || display['spdisplays_display-serial-number'] || display['spdisplays_serial-number']);
            var sizeText=text(display['spdisplays_display-size']), sizeMatch=sizeText.match(/([0-9]+(?:\.[0-9]+)?)\s*(?:-?inch|英寸)/i);
            var size=sizeMatch ? Number(sizeMatch[1]) : null;
            if(size!==null && (size<1 || size>200))size=null;
            if(!brand || !sn || !size || connection==='unknown')status='partial';
            monitors.push({deviceKey:'display:'+gpuIndex+':'+index,connection:connection,brand:brand,serialNumber:sn,sizeInches:size});
        });
    });
    return {monitors:monitors,status:status};
}
function parseDisks(disks) {
    var result=[];
    disks.forEach(function(disk){
        if(disk.Internal!==true || /virtual/i.test(text(disk.VirtualOrPhysical)) || !Number.isSafeInteger(Number(disk.TotalSize)) || Number(disk.TotalSize)<=0)return;
        var bus=text(disk.BusProtocol), normalized=/^sata$/i.test(bus)?'SATA':/^nvme$/i.test(bus)?'NVMe':/^pci.?express$/i.test(bus)?'PCIe':/^apple fabric$/i.test(bus)?'AppleFabric':null;
        if(!normalized)return;
        result.push({model:text(disk.MediaName)||null,capacityBytes:Number(disk.TotalSize),busType:normalized});
    });
    return result;
}
function makeInventory(profiler, disks) {
    var hardware=(profiler.SPHardwareDataType || [])[0];
    if(!hardware)throw new Error('未读取到电脑信息');
    var uuid=text(hardware.platform_UUID).toLowerCase();
    if(!/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/.test(uuid) || /^(?:0{8}-0{4}-0{4}-0{4}-0{12}|f{8}-f{4}-f{4}-f{4}-f{12})$/.test(uuid))throw new Error('硬件 UUID 无效');
    var model=text(hardware.machine_model), readable=text(hardware.machine_name)||model;
    var detected=parseDisplays(profiler), type=/^MacBook/i.test(readable)||/^MacBook/i.test(model)?'laptop':/^(iMac|Macmini|Mac mini|MacPro|Mac Pro|Mac Studio)/i.test(readable)||/^(iMac|Macmini|MacPro)/i.test(model)?'desktop':'unknown';
    return {hardwareUuid:uuid,name:readable?'Apple '+readable:null,capturedAt:new Date().toISOString(),
        snapshot:{model:model||null,cpu:text(hardware.chip_type || hardware.cpu_type)||null,memory:text(hardware.physical_memory)||null,
            internalDisks:parseDisks(disks),graphicsCards:(profiler.SPDisplaysDataType || []).map(function(gpu){return text(gpu.sppci_model);}).filter(Boolean),manufacturerSerialNumber:serial(hardware.serial_number)},
        collection:{platform:'macos',computerType:type,status:detected.status,monitors:detected.monitors}};
}
function validEndpoint(endpoint) { return /^https?:\/\/(?:[a-z0-9.-]+|\[[a-f0-9:]+\])(?::[0-9]{1,5})?\/api\/integrations\/ad-deployment\/v1\/windows-intakes$/i.test(endpoint); }
function departmentSuggestion(person, saved, lookup) {
    person=text(person);
    if(!person || person.length>120)throw new Error('姓名必填，最多120字；未提交');
    if(text(saved.reportedUserName)===person && text(saved.reportedDepartment) && text(saved.reportedDepartment).length<=120)
        return {department:text(saved.reportedDepartment),message:'保留上次填写的部门，可修改。'};
    try {
        var result=lookup(person);
        if(result && result.result==='matched' && typeof result.department==='string' && text(result.department) && result.department.length<=120)
            return {department:text(result.department),message:'已根据登记核对名单填写部门，请确认或修改。'};
        return {department:'',message:'名单未匹配到部门，请手动填写（可选）。'};
    }catch(e){return {department:'',message:'部门查询暂不可用，可手动填写后提交。'};}
}
function departmentRequest(config, name) {
    if(!validEndpoint(config.endpoint))throw new Error('采集包地址无效');
    var target=config.endpoint.replace(/\/windows-intakes$/,'/collector-person');
    return 'url = '+JSON.stringify(target)+'\nheader = '+JSON.stringify('Authorization: Bearer '+config.token)+'\ndata-binary = '+JSON.stringify(JSON.stringify({name:text(name)}))+'\n';
}
function createCollectionDirectory(path) {
    var error=Ref();
    if(!$.NSFileManager.defaultManager.createDirectoryAtPathWithIntermediateDirectoriesAttributesError($(path),true,$.NSDictionary.dictionary,error))throw new Error('无法保存本地记录');
}
function collectionPlist(xml) {
    var error=Ref(), format=Ref();
    var value=$.NSPropertyListSerialization.propertyListWithDataOptionsFormatError($(xml).dataUsingEncoding($.NSUTF8StringEncoding),0,format,error);
    if(!value || value.isNil())throw new Error('系统资料格式无效');
    return ObjC.deepUnwrap(value);
}
function runCollection() {
    ObjC.import('Foundation');
    var app=Application.currentApplication();app.includeStandardAdditions=true;
    function read(file){var error=Ref();var value=$.NSString.stringWithContentsOfFileEncodingError($(file),$.NSUTF8StringEncoding,error);if(!value)throw new Error('无法读取采集包文件');return ObjC.unwrap(value);}
    function write(file,value){var error=Ref();if(!$(value).writeToFileAtomicallyEncodingError($(file),true,$.NSUTF8StringEncoding,error))throw new Error('无法保存本地采集记录');}
    function command(executable,args,input){
        var task=$.NSTask.alloc.init, output=$.NSPipe.pipe, errors=$.NSPipe.pipe;
        task.launchPath=$(executable);task.arguments=$(args);task.standardOutput=output;task.standardError=errors;
        var pipe;
        if(input!=null){pipe=$.NSPipe.pipe;task.standardInput=pipe;}
        task.launch;
        if(pipe){pipe.fileHandleForWriting.writeData($(input).dataUsingEncoding($.NSUTF8StringEncoding));pipe.fileHandleForWriting.closeFile;}
        var data=output.fileHandleForReading.readDataToEndOfFile;task.waitUntilExit;
        if(task.terminationStatus!==0)throw new Error('系统信息读取或网络请求失败');
        return ObjC.unwrap($.NSString.alloc.initWithDataEncoding(data,$.NSUTF8StringEncoding));
    }
    function plist(executable,args){return collectionPlist(command(executable,args));}
    try {
        var directory=ObjC.unwrap($.NSFileManager.defaultManager.currentDirectoryPath), config=JSON.parse(read(directory+'/config.json'));
        if(!validEndpoint(config.endpoint) || !/^adp_[0-9a-f-]{36}\.[A-Za-z0-9_-]{43}$/.test(config.token))throw new Error('采集包配置无效');
        console.log('正在读取电脑与当前连接的显示器……');
        var profiler=JSON.parse(command('/usr/sbin/system_profiler',['SPHardwareDataType','SPDisplaysDataType','-json','-timeout','30']));
        var disks=[];
        try {var listed=plist('/usr/sbin/diskutil',['list','-plist','internal','physical']);(listed.AllDisksAndPartitions || []).forEach(function(disk){disks.push(plist('/usr/sbin/diskutil',['info','-plist','/dev/'+disk.DeviceIdentifier]));});}catch(e){console.log('内置硬盘未采集，请在审核时核对。');}
        var inventory=makeInventory(profiler,disks), records=directory+'/records', error=Ref();
        createCollectionDirectory(records);
        var file=records+'/'+inventory.hardwareUuid+'.json', saved=inventory;
        if($.NSFileManager.defaultManager.fileExistsAtPath($(file))){var cached=read(file);if(cached.length>32768)throw new Error('本地记录过大');saved=JSON.parse(cached);if(saved.hardwareUuid!==inventory.hardwareUuid)throw new Error('本地记录身份不符');saved.collection=inventory.collection;}
        var person=app.displayDialog('请填写这台电脑的实际使用人姓名，不是装机人员。',{defaultAnswer:saved.reportedUserName||'',buttons:['取消','继续'],defaultButton:'继续',cancelButton:'取消',withTitle:'电脑资产登记'}).textReturned.trim();
        var suggestion=departmentSuggestion(person,saved,function(name){
            var response=command('/usr/bin/curl',['--disable','--config','-','--silent','--show-error','--noproxy','*','--connect-timeout','3','--max-time','5','--max-redirs','0','--request','POST','--header','Content-Type: application/json; charset=utf-8','--write-out','\n%{http_code}'],departmentRequest(config,name));
            var split=response.lastIndexOf('\n');
            if(Number(response.slice(split+1))!==200)throw new Error('部门查询不可用');
            return JSON.parse(response.slice(0,split).replace(/^\uFEFF/,''));
        });
        var department=app.displayDialog(suggestion.message+'\n\n使用人：'+person+'\n部门（选填）。确认后提交电脑和外接显示器，等待管理员审核。',{defaultAnswer:suggestion.department,buttons:['取消','确认并提交'],defaultButton:'确认并提交',cancelButton:'取消',withTitle:'确认电脑使用人'}).textReturned.trim();
        if(department.length>120)throw new Error('部门最多120字；未提交');
        saved.reportedUserName=person;saved.reportedDepartment=department;var body=JSON.stringify(saved);
        if($(body).lengthOfBytesUsingEncoding($.NSUTF8StringEncoding)>32768)throw new Error('采集资料过大，未发送');
        write(file,body);
        var curlConfig='url = '+JSON.stringify(config.endpoint)+'\nheader = '+JSON.stringify('Authorization: Bearer '+config.token)+'\n';
        var response=command('/usr/bin/curl',['--disable','--config','-','--silent','--show-error','--noproxy','*','--connect-timeout','8','--max-time','30','--max-redirs','0','--request','POST','--header','Content-Type: application/json; charset=utf-8','--data-binary','@'+file,'--write-out','\n%{http_code}'],curlConfig);
        var split=response.lastIndexOf('\n'), status=Number(response.slice(split+1));
        if(status!==200 && status!==202)throw new Error('提交未成功（HTTP '+status+'），本地资料已保留，可再次运行重试');
        var result=JSON.parse(response.slice(0,split));
        if(result.externalRegistrationId!=='macos:'+inventory.hardwareUuid || ['pending_review','approved','rejected'].indexOf(result.result)<0)throw new Error('服务器回报不匹配，请核对，勿当作成功');
        var message=result.result==='pending_review'?'电脑配置及使用人信息已送到资产系统，等待管理员审核。尚未直接创建或领用资产。\n\n现在可以关闭窗口；使用 U 盘时可以安全拔盘。':result.result==='approved'?'这台电脑已入库。'+(result.asset && result.asset.assetTag?'资产编号：'+result.asset.assetTag+'。':'')+'本次未重复建档，也未更新显示器，请在资产系统核对。':'这台电脑已有未批准记录，请在资产入库修正并重新送审。本次未创建资产。';
        app.displayAlert(message,{as:'informational'});console.log(message);
    } catch(error){
        if(error.errorNumber===-128 || error.number===-128){console.log('已取消，未发送。');return;}
        var failure=config && config.token ? text(error.message).split(config.token).join('[REDACTED]') : '请核对采集包配置及系统权限';
        console.log('未完成：'+failure);
        app.displayAlert('本次采集未完成',{message:failure+'\n未自动创建资产。请核对网络、采集包有效期或联系管理员后重试。',as:'critical'});
        throw new Error('采集未完成；未修改系统或自动创建资产。');
    }
}
if(typeof module!=='undefined')module.exports={makeInventory:makeInventory,parseDisplays:parseDisplays,parseDisks:parseDisks,validEndpoint:validEndpoint,departmentSuggestion:departmentSuggestion,departmentRequest:departmentRequest};
else runCollection();
