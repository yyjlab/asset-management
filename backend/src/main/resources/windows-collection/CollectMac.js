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
    }catch(e){return {department:'',message:'部门查询暂不可用：'+text(e.message)+'。可手动填写后提交。'};}
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
function collectionFailure(executable, code, detail, secret) {
    var reasons={6:'无法解析资产系统域名，请连接公司网络或 VPN，并检查内网 DNS',7:'无法连接资产系统，请检查公司网络、VPN 或本地网络访问权限',28:'连接或读取超时，请检查网络后重试',35:'HTTPS 握手失败',60:'HTTPS 证书不受信任，请联系管理员配置证书，勿关闭证书验证'};
    var reason=executable==='/usr/bin/curl'?(reasons[code]||'网络工具执行失败'):'系统信息读取失败';
    var message=reason+'（退出码 '+code+'）'+(text(detail)?'：'+text(detail):'');
    if(secret)message=message.split(secret).join('[REDACTED]');
    return message.replace(/[\x00-\x1f\x7f]/g,' ').slice(0,600);
}
function collectionResponse(response, accepted) {
    var split=response.lastIndexOf('\n'), status=Number(response.slice(split+1)), content=response.slice(0,split).replace(/^\uFEFF/,'');
    if(split<0 || !Number.isInteger(status) || status<100 || status>599)throw new Error('未收到有效的资产系统 HTTP 回报');
    var result;
    try{result=JSON.parse(content);}catch(e){throw new Error('资产系统返回的不是有效 JSON（HTTP '+status+'），请核对反向代理和接口地址');}
    if(accepted.indexOf(status)<0){
        var description=status===401?'采集凭据已过期、撤销或无效，请重新下载采集包':status===403?'接收接口拒绝访问，请检查接入权限和 HTTP 设置':status===404?'接收接口不存在，请检查后端版本和反向代理路径':status>=500?'资产系统处理失败，请联系管理员查看服务日志':'资产系统未接受资料';
        var reason=text(result.message||result.error);
        throw new Error(description+'（HTTP '+status+'）'+(reason?'：'+reason.slice(0,250):''));
    }
    return result;
}
function collectionProgress() {
    ObjC.import('Cocoa');
    var application=$.NSApplication.sharedApplication;
    application.setActivationPolicy($.NSApplicationActivationPolicyAccessory);
    var window=$.NSWindow.alloc.initWithContentRectStyleMaskBackingDefer($.NSMakeRect(0,0,480,180),1,$.NSBackingStoreBuffered,false);
    window.title=$('电脑资产采集');window.releasedWhenClosed=false;window.center;
    function label(value,y,size){var field=$.NSTextField.labelWithString($(value));field.frame=$.NSMakeRect(24,y,432,30);field.font=$.NSFont.systemFontOfSize(size);window.contentView.addSubview(field);return field;}
    label('正在准备电脑资产资料',122,20);
    var stage=label('准备中',84,14), elapsed=label('请保持网络连接，完成后可关闭。',18,12);
    var spinner=$.NSProgressIndicator.alloc.initWithFrame($.NSMakeRect(24,58,432,12));
    spinner.indeterminate=true;spinner.style=$.NSProgressIndicatorStyleBar;window.contentView.addSubview(spinner);spinner.startAnimation($());
    var started=Date.now();
    function pump(){elapsed.stringValue=$('本步骤已等待 '+Math.floor((Date.now()-started)/1000)+' 秒');window.displayIfNeeded;$.NSRunLoop.currentRunLoop.runUntilDate($.NSDate.dateWithTimeIntervalSinceNow(0.05));}
    return {update:function(value){started=Date.now();stage.stringValue=$(value);window.makeKeyAndOrderFront($());application.activateIgnoringOtherApps(true);pump();},pump:pump,
        hide:function(){window.orderOut($());},close:function(){window.close;}};
}
function collectionCommand(executable,args,input,options) {
    options=options||{};
    var directory=options.directory||ObjC.unwrap($.NSFileManager.defaultManager.currentDirectoryPath);
    var prefix=directory+'/.command-'+ObjC.unwrap($.NSUUID.UUID.UUIDString), files=[prefix+'.out',prefix+'.err'];
    var manager=$.NSFileManager.defaultManager, task=$.NSTask.alloc.init, handles=[], launched=false;
    function readOutput(file){var data=$.NSData.dataWithContentsOfFile($(file));if(data.length>4*1024*1024)throw new Error('系统回报过大，已停止处理');return ObjC.unwrap($.NSString.alloc.initWithDataEncoding(data,$.NSUTF8StringEncoding))||'';}
    try{
        files.forEach(function(file){if(!manager.createFileAtPathContentsAttributes($(file),$.NSData.data,$.NSDictionary.dictionary))throw new Error('无法创建临时采集文件');handles.push($.NSFileHandle.fileHandleForWritingAtPath($(file)));});
        task.launchPath=$(executable);task.arguments=$(args);task.standardOutput=handles[0];task.standardError=handles[1];
        var pipe;
        if(input!=null){pipe=$.NSPipe.pipe;task.standardInput=pipe;}
        task.launch;launched=true;
        if(pipe){pipe.fileHandleForWriting.writeData($(input).dataUsingEncoding($.NSUTF8StringEncoding));pipe.fileHandleForWriting.closeFile;}
        var deadline=Date.now()+(options.timeout||35)*1000;
        while(task.running){
            if(Date.now()>deadline){task.terminate;throw new Error('当前步骤等待超时，已停止本次处理，可再次运行重试');}
            if(options.pump)options.pump();else $.NSThread.sleepForTimeInterval(0.05);
        }
        task.waitUntilExit;
        handles.forEach(function(handle){handle.closeFile;});handles=[];
        if(task.terminationStatus!==0)throw new Error(collectionFailure(executable,task.terminationStatus,readOutput(files[1]),options.secret));
        return readOutput(files[0]);
    }finally{
        if(launched && task.running)task.terminate;
        handles.forEach(function(handle){handle.closeFile;});
        files.forEach(function(file){manager.removeItemAtPathError($(file),Ref());});
    }
}
function runCollection() {
    ObjC.import('Foundation');
    var app=Application.currentApplication();app.includeStandardAdditions=true;
    var progress=null, stage='读取采集包', directory='', config=null;
    function read(file){var error=Ref();var value=$.NSString.stringWithContentsOfFileEncodingError($(file),$.NSUTF8StringEncoding,error);if(!value)throw new Error('无法读取采集包文件');return ObjC.unwrap(value);}
    function write(file,value){var error=Ref();if(!$(value).writeToFileAtomicallyEncodingError($(file),true,$.NSUTF8StringEncoding,error))throw new Error('无法保存本地采集记录');}
    function command(executable,args,input,timeout){return collectionCommand(executable,args,input,{directory:directory,secret:config.token,timeout:timeout,pump:progress.pump});}
    function update(value){stage=value;progress.update(value);}
    function plist(executable,args){return collectionPlist(command(executable,args,null,6));}
    try {
        directory=ObjC.unwrap($.NSFileManager.defaultManager.currentDirectoryPath);config=JSON.parse(read(directory+'/config.json'));
        if(!validEndpoint(config.endpoint) || !/^adp_[0-9a-f-]{36}\.[A-Za-z0-9_-]{43}$/.test(config.token))throw new Error('采集包配置无效');
        progress=collectionProgress();update('1 / 5 · 读取电脑配置');
        var profiler=JSON.parse(command('/usr/sbin/system_profiler',['SPHardwareDataType','-json','-detailLevel','mini','-timeout','10'],null,12));
        update('2 / 5 · 检测显卡与外接显示器');
        try{var displays=JSON.parse(command('/usr/sbin/system_profiler',['SPDisplaysDataType','-json','-timeout','10'],null,12));profiler.SPDisplaysDataType=displays.SPDisplaysDataType;}catch(e){console.log('显示器信息未采集，审核时请核对。');}
        update('3 / 5 · 读取内置物理硬盘');
        var disks=[];
        try {var listed=plist('/usr/sbin/diskutil',['list','-plist','internal','physical']);(listed.AllDisksAndPartitions || []).forEach(function(disk){disks.push(plist('/usr/sbin/diskutil',['info','-plist','/dev/'+disk.DeviceIdentifier]));});}catch(e){console.log('内置硬盘未采集，请在审核时核对。');}
        var inventory=makeInventory(profiler,disks), records=directory+'/records', error=Ref();
        createCollectionDirectory(records);
        var file=records+'/'+inventory.hardwareUuid+'.json', saved=inventory;
        if($.NSFileManager.defaultManager.fileExistsAtPath($(file))){var cached=read(file);if(cached.length>32768)throw new Error('本地记录过大');saved=JSON.parse(cached);if(saved.hardwareUuid!==inventory.hardwareUuid)throw new Error('本地记录身份不符');saved.collection=inventory.collection;}
        progress.hide();stage='填写使用人';
        var person=app.displayDialog('请填写这台电脑的实际使用人姓名，不是装机人员。',{defaultAnswer:saved.reportedUserName||'',buttons:['取消','继续'],defaultButton:'继续',cancelButton:'取消',withTitle:'电脑资产登记'}).textReturned.trim();
        update('4 / 5 · 根据姓名查询部门');
        var suggestion=departmentSuggestion(person,saved,function(name){
            var response=command('/usr/bin/curl',['--disable','--config','-','--silent','--show-error','--noproxy','*','--connect-timeout','3','--max-time','5','--max-redirs','0','--request','POST','--header','Content-Type: application/json; charset=utf-8','--write-out','\n%{http_code}'],departmentRequest(config,name));
            return collectionResponse(response,[200]);
        });
        progress.hide();stage='确认部门';
        var department=app.displayDialog(suggestion.message+'\n\n使用人：'+person+'\n部门（选填）。确认后提交电脑和外接显示器，等待管理员审核。',{defaultAnswer:suggestion.department,buttons:['取消','确认并提交'],defaultButton:'确认并提交',cancelButton:'取消',withTitle:'确认电脑使用人'}).textReturned.trim();
        if(department.length>120)throw new Error('部门最多120字；未提交');
        saved.reportedUserName=person;saved.reportedDepartment=department;var body=JSON.stringify(saved);
        if($(body).lengthOfBytesUsingEncoding($.NSUTF8StringEncoding)>32768)throw new Error('采集资料过大，未发送');
        write(file,body);
        update('5 / 5 · 提交资产系统，等待接收确认');
        var curlConfig='url = '+JSON.stringify(config.endpoint)+'\nheader = '+JSON.stringify('Authorization: Bearer '+config.token)+'\n';
        var response=command('/usr/bin/curl',['--disable','--config','-','--silent','--show-error','--noproxy','*','--connect-timeout','8','--max-time','30','--max-redirs','0','--request','POST','--header','Content-Type: application/json; charset=utf-8','--data-binary','@'+file,'--write-out','\n%{http_code}'],curlConfig);
        var result=collectionResponse(response,[200,202]);
        if(result.externalRegistrationId!=='macos:'+inventory.hardwareUuid || ['pending_review','approved','rejected'].indexOf(result.result)<0)throw new Error('服务器回报不匹配，请核对，勿当作成功');
        var message=result.result==='pending_review'?'电脑配置及使用人信息已送到资产系统，等待管理员审核。尚未直接创建或领用资产。\n\n现在可以关闭窗口；使用 U 盘时可以安全拔盘。':result.result==='approved'?'这台电脑已入库。'+(result.asset && result.asset.assetTag?'资产编号：'+result.asset.assetTag+'。':'')+'本次未重复建档，也未更新显示器，请在资产系统核对。':'这台电脑已有未批准记录，请在资产入库修正并重新送审。本次未创建资产。';
        progress.hide();app.displayAlert(message,{as:'informational'});console.log(message);
    } catch(error){
        if(error.errorNumber===-128 || error.number===-128){console.log('已取消，未发送。');return;}
        var failure=config && config.token ? text(error.message).split(config.token).join('[REDACTED]') : '请核对采集包配置及系统权限';
        if(progress)progress.hide();
        failure='失败步骤：'+stage+'\n'+failure;
        if(directory && config){try{write(directory+'/collection-state.json',JSON.stringify({stage:stage,error:failure.slice(0,1200),updated:new Date().toISOString()}));}catch(ignore){}}
        console.log('未完成：'+failure);
        app.displayAlert('本次采集未完成',{message:failure+'\n本地已保存的资料会保留，可再次运行。尚未收到成功确认，请勿当作已入库。',as:'critical'});
        throw new Error('采集未完成；未修改系统或自动创建资产。');
    }finally{if(progress)progress.close();}
}
if(typeof module!=='undefined')module.exports={makeInventory:makeInventory,parseDisplays:parseDisplays,parseDisks:parseDisks,validEndpoint:validEndpoint,departmentSuggestion:departmentSuggestion,departmentRequest:departmentRequest,collectionFailure:collectionFailure,collectionResponse:collectionResponse};
else runCollection();
