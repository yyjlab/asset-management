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
    return 'PASS: real Foundation directory and plist bridges';
}
