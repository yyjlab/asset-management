#import <AppKit/AppKit.h>
#import <CommonCrypto/CommonDigest.h>

static void Fail(NSString *message) {
    NSAlert *alert = [NSAlert new];
    alert.messageText = @"电脑资产采集未启动";
    alert.informativeText = message;
    [alert addButtonWithTitle:@"关闭"];
    [NSApp activateIgnoringOtherApps:YES];
    [alert runModal];
}

static BOOL IsLink(NSString *path) {
    return [[[NSFileManager defaultManager] attributesOfItemAtPath:path error:nil][NSFileType]
            isEqualToString:NSFileTypeSymbolicLink];
}

int main(void) {
    @autoreleasepool {
        [NSApplication sharedApplication];
        [NSApp setActivationPolicy:NSApplicationActivationPolicyAccessory];
        NSError *error = nil;
        NSString *source = [[NSBundle mainBundle] resourcePath];
        NSData *config = [NSData dataWithContentsOfFile:[source stringByAppendingPathComponent:@"config.json"]];
        NSData *script = [NSData dataWithContentsOfFile:[source stringByAppendingPathComponent:@"CollectMac.js"]];
        if (!config || !script) { Fail(@"采集包文件缺失，请重新生成并下载。"); return 1; }
        unsigned char digest[CC_SHA256_DIGEST_LENGTH];
        CC_SHA256(config.bytes, (CC_LONG)config.length, digest);
        NSMutableString *key = [NSMutableString new];
        for (int i = 0; i < CC_SHA256_DIGEST_LENGTH; i++) [key appendFormat:@"%02x", digest[i]];
        NSString *support = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, YES).firstObject;
        NSString *base = [support stringByAppendingPathComponent:@"WFQAssetCollector"];
        NSString *work = [base stringByAppendingPathComponent:key];
        if (IsLink(base) || IsLink(work) || ![[NSFileManager defaultManager] createDirectoryAtPath:work withIntermediateDirectories:YES attributes:@{NSFilePosixPermissions:@0700} error:&error]) {
            Fail(@"无法建立本地采集目录，请联系管理员检查当前用户权限。"); return 1;
        }
        NSDictionary *files = @{@"config.json":config, @"CollectMac.js":script};
        for (NSString *name in files) {
            NSString *path = [work stringByAppendingPathComponent:name];
            if (IsLink(path) || ![files[name] writeToFile:path options:NSDataWritingAtomic error:&error]) {
                Fail(@"无法准备采集文件，请联系管理员检查当前用户权限。"); return 1;
            }
            [[NSFileManager defaultManager] setAttributes:@{NSFilePosixPermissions:@0600} ofItemAtPath:path error:nil];
        }
        NSTask *task = [NSTask new];
        task.executableURL = [NSURL fileURLWithPath:@"/usr/bin/osascript"];
        task.arguments = @[@"-l", @"JavaScript", @"CollectMac.js"];
        task.currentDirectoryURL = [NSURL fileURLWithPath:work];
        if (![task launchAndReturnError:&error]) { Fail(@"无法启动系统脚本解释器，请联系管理员检查终端防护设置。"); return 1; }
        [task waitUntilExit];
        return task.terminationStatus;
    }
}
