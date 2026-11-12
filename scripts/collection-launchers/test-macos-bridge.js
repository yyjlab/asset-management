ObjC.import('Foundation');
function run(args) {
    var error=Ref();
    var source=ObjC.unwrap($.NSString.stringWithContentsOfFileEncodingError($(args[0]),$.NSUTF8StringEncoding,error));
    eval(source.replace('else runCollection();',''));
    var directory=args[1]+'/中文采集测试/records';
    createCollectionDirectory(directory);
    createCollectionDirectory(directory);
    if(!$.NSFileManager.defaultManager.fileExistsAtPath($(directory)))throw new Error('Directory missing');
    var value=collectionPlist('<?xml version="1.0"?><plist version="1.0"><dict><key>Internal</key><true/><key>AllDisksAndPartitions</key><array/></dict></plist>');
    if(value.Internal!==true || value.AllDisksAndPartitions.length!==0)throw new Error('Plist parsing failed');
    var view=collectionProgress();
    view.update('CI fixture: reading hardware');view.pump();view.hide();view.close();
    var output=collectionCommand('/bin/sh',['-c','printf fixture-output; printf fixture-error >&2'],null,{directory:directory,timeout:3});
    if(output!=='fixture-output')throw new Error('Command output missing');
    try{collectionCommand('/bin/sh',['-c','printf fixture-secret >&2; exit 7'],null,{directory:directory,secret:'fixture-secret',timeout:3});throw new Error('Expected subprocess failure');}
    catch(e){if(e.message.indexOf('退出码 7')<0 || e.message.indexOf('fixture-secret')>=0)throw e;}
    if(args[2]){
        var config={endpoint:args[2]+'/api/integrations/ad-deployment/v1/windows-intakes',token:'fixture-only'};
        var options={directory:directory,timeout:8,secret:config.token};
        var curl=['--disable','--config','-','--silent','--show-error','--noproxy','*','--connect-timeout','2','--max-time','5','--max-redirs','0','--request','POST','--header','Content-Type: application/json; charset=utf-8','--write-out','\n%{http_code}'];
        var response=collectionCommand('/usr/bin/curl',curl,departmentRequest(config,'张三'),options);
        var department=collectionResponse(response,[200]);
        if(department.department!=='研发部')throw new Error('UTF-8 department lookup failed');
        var body=JSON.stringify({hardwareUuid:'12345678-1234-1234-1234-123456789abc',reportedUserName:'张三',reportedDepartment:department.department});
        var file=directory+'/中文提交记录.json';
        if(!$(body).writeToFileAtomicallyEncodingError($(file),true,$.NSUTF8StringEncoding,Ref()))throw new Error('Cannot save fixture payload');
        var request='url = '+JSON.stringify(config.endpoint)+'\nheader = '+JSON.stringify('Authorization: Bearer '+config.token)+'\n';
        var submitted=collectionResponse(collectionCommand('/usr/bin/curl',curl.concat(['--data-binary','@'+file]),request,options),[202]);
        if(submitted.result!=='pending_review')throw new Error('Submission not acknowledged');
    }
    return 'PASS: real Foundation directory and plist bridges';
}
