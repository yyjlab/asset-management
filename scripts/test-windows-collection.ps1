param([switch]$TestDialog)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/../backend/src/main/resources/windows-collection/Collect.ps1" -LibraryOnly
$script:cimClasses=[Collections.Generic.List[string]]::new()
$script:failMonitors=$false
function Get-CimInstance {
    param($ClassName, $OperationTimeoutSec, $Namespace)
    $script:cimClasses.Add($ClassName)
    if($script:failMonitors -and $ClassName -eq 'WmiMonitorID'){throw 'Mock monitor read failure'}
    switch ($ClassName) {
        'Win32_ComputerSystemProduct' { [pscustomobject]@{ UUID='12345678-1234-1234-1234-123456789abc'; Version='ThinkPad L13' } }
        'Win32_ComputerSystem' { [pscustomobject]@{ Model='20R4'; SystemFamily='ThinkPad L13'; Manufacturer='LENOVO'; TotalPhysicalMemory=8GB; PCSystemType=2 } }
        'Win32_BIOS' { [pscustomobject]@{ SerialNumber='sample' } }
        'Win32_Processor' { [pscustomobject]@{ Name='Test CPU' } }
        'Win32_VideoController' { [pscustomobject]@{ Name='Microsoft Basic Display Adapter' }; [pscustomobject]@{ Name='Intel Graphics' } }
        'WmiMonitorID' {
            foreach($id in @('internal','external1','external2','external2','inactive')){[pscustomobject]@{InstanceName=$id;Active=($id -ne 'inactive');ManufacturerName=([int[]][char[]]'DEL');SerialNumberID=([int[]][char[]]'SAME-SN')}}
        }
        'WmiMonitorBasicDisplayParams' {foreach($id in @('internal','external1')){[pscustomobject]@{InstanceName=$id;Active=$true;MaxHorizontalImageSize=53;MaxVerticalImageSize=30}}}
        'WmiMonitorConnectionParams' {foreach($id in @('internal','external1','external2')){[pscustomobject]@{InstanceName=$id;Active=$true;VideoOutputTechnology=$(if($id -eq 'internal'){2147483648}else{5})}}}
        'Win32_PointingDevice' { [pscustomobject]@{PNPDeviceID='HID\VID_046D&PID_C52B';Name='HID-compliant mouse'} }
        'Win32_Keyboard' { [pscustomobject]@{PNPDeviceID='ACPI\PNP0303';Name='Standard PS/2 Keyboard'} }
    }
}
function Get-Disk {
    [pscustomobject]@{ Number=0; BusType='NVMe'; Size=256GB; FriendlyName='Internal SSD' }
    [pscustomobject]@{ Number=1; BusType='USB'; Size=32GB; FriendlyName='USB drive' }
}
$inventory=Read-CollectionInventory
if($inventory.name -ne 'LENOVO ThinkPad L13'){throw 'Readable device name lost'}
if($inventory.snapshot.internalDisks.Count -ne 1){throw 'USB disk included'}
if($inventory.snapshot.internalDisks[0].capacityBytes -ne 256GB){throw 'Physical capacity changed'}
if($inventory.snapshot.graphicsCards.Count -ne 1 -or $inventory.snapshot.graphicsCards[0] -ne 'Intel Graphics'){throw 'Basic display adapter included'}
if($inventory.collection.monitors.Count -ne 2){throw 'Active external monitor count incorrect or equal SN merged'}
if(@($inventory.collection.monitors|Where-Object connection -eq 'external').Count -ne 2){throw 'External monitor count incorrect'}
if(@($inventory.collection.monitors|Where-Object connection -eq 'internal').Count){throw 'Built-in panel was not excluded'}
if($null -ne $inventory.collection.monitors[1].sizeInches){throw 'Missing size fabricated'}
if($inventory.collection.computerType -ne 'laptop'){throw 'Chassis type incorrect'}
if($script:cimClasses.Contains('Win32_PointingDevice') -or $script:cimClasses.Contains('Win32_Keyboard')){throw 'Removed mouse/keyboard queries still run'}
if($inventory.collection.ContainsKey('mouseBrands') -or $inventory.collection.ContainsKey('keyboardBrands')){throw 'Removed peripherals still uploaded'}
$script:failMonitors=$true
$unavailable=Read-CollectionDisplays ([pscustomobject]@{PCSystemType=1})
if($unavailable.status -ne 'unavailable' -or $unavailable.monitors.Count -ne 0 -or $unavailable.computerType -ne 'desktop'){throw 'Missing monitor data inferred as laptop'}
$script:failMonitors=$false
$script:sent=0
function Invoke-RestMethod {
    param([switch]$UseBasicParsing, $Uri, $Method, $MaximumRedirection, $TimeoutSec, $Headers, $ContentType, $Body)
    if(([uri]$Uri).AbsolutePath -eq '/api/integrations/ad-deployment/v1/collector-person'){
        if($MaximumRedirection -ne 0 -or $TimeoutSec -ne 5 -or $Method -ne 'Post'){throw 'Unsafe person lookup options'}
        if(([Text.Encoding]::UTF8.GetString($Body)|ConvertFrom-Json).name -ne '测试姓名'){throw 'Lookup UTF-8 name changed'}
        if($Headers.Authorization -ne 'Bearer test-only'){throw 'Lookup credential missing'}
        return @{result='matched';department='研发部'}
    }
    if($MaximumRedirection -ne 0 -or $TimeoutSec -ne 30 -or $Method -ne 'Post'){throw 'Unsafe request options'}
    if($Headers.Authorization -ne 'Bearer test-only'){throw 'Credential missing'}
    if(([Text.Encoding]::UTF8.GetString($Body)|ConvertFrom-Json).hardwareUuid -ne $inventory.hardwareUuid){throw 'Snapshot changed'}
    $script:sent++
    @{result='pending_review'}
}
$null=Send-CollectionInventory @{endpoint='http://asset.example/api/integrations/ad-deployment/v1/windows-intakes';token='test-only'} ($inventory|ConvertTo-Json -Depth 8)
if($script:sent -ne 1){throw 'Request count incorrect'}
function Invoke-WebRequest {
    param([switch]$UseBasicParsing, $Uri, $Method, $MaximumRedirection, $TimeoutSec, $Headers, $ContentType, $Body)
    if(([uri]$Uri).AbsolutePath -ne '/api/integrations/ad-deployment/v1/collector-person' -or $MaximumRedirection -ne 0 -or $TimeoutSec -ne 5 -or $Method -ne 'Post'){throw 'Unsafe person lookup options'}
    if(([Text.Encoding]::UTF8.GetString($Body)|ConvertFrom-Json).name -ne '测试姓名' -or $Headers.Authorization -ne 'Bearer test-only'){throw 'Lookup UTF-8 name or credential changed'}
    $bytes=[Text.Encoding]::UTF8.GetBytes('{"result":"matched","department":"研发部"}')
    $stream=New-Object IO.MemoryStream
    $stream.Write($bytes,0,$bytes.Length)
    # Deliberately emulate an already misdecoded Content string. Only raw bytes are trustworthy.
    @{Content=[Text.Encoding]::GetEncoding(28591).GetString($bytes);RawContentStream=$stream}
}
$department=Get-CollectionDepartment @{endpoint='http://asset.example/api/integrations/ad-deployment/v1/windows-intakes';token='test-only'} ' 测试姓名 '
if($department.department -ne '研发部'){throw 'Exact department lookup failed'}
Write-Host 'PASS Windows collector: readable name, physical disks, GPU filtering, UTF-8 submission and no redirects (mocked).'
if($TestDialog){
    Add-Type -AssemblyName System.Windows.Forms
    $timer=New-Object System.Windows.Forms.Timer
    $timer.Interval=150
    $timer.Add_Tick({
        $form=[Windows.Forms.Application]::OpenForms | Where-Object Text -eq '电脑资产登记' | Select-Object -First 1
        if(!$form){return}
        $layout=$form.Controls[0]
        $inputs=@($layout.Controls | Where-Object {$_ -is [Windows.Forms.TextBox]})
        if(@($inputs | Where-Object ImeMode -ne 'On').Count){throw 'Chinese IME must be enabled for both inputs'}
        $inputs[0].Text='测试使用人'; $inputs[1].Text='测试部门'
        $buttons=$layout.Controls | Where-Object {$_ -is [Windows.Forms.FlowLayoutPanel]}
        if($buttons.Bottom -gt $layout.ClientSize.Height){throw 'Form buttons clipped'}
        ($buttons.Controls | Where-Object Text -eq '确认并提交').PerformClick()
        $timer.Stop()
    })
    try {
        $timer.Start()
        $person=Show-CollectionUserForm
        if($person.reportedUserName -ne '测试使用人' -or $person.reportedDepartment -ne '测试部门'){throw 'Dialog values lost'}
    } finally {$timer.Stop(); $timer.Dispose()}
    $timer=New-Object System.Windows.Forms.Timer
    $timer.Interval=150
    $timer.Add_Tick({
        $form=[Windows.Forms.Application]::OpenForms | Where-Object Text -eq '电脑资产登记' | Select-Object -First 1
        if($form){$form.Close(); $timer.Stop()}
    })
    try {
        $timer.Start()
        if($null -ne (Show-CollectionUserForm)){throw 'Cancel must not return submitted data'}
    } finally {$timer.Stop(); $timer.Dispose()}
    Write-Host 'PASS native dialog: editable name/department, submit and close-to-cancel.'
    $originalLookup=(Get-Command Get-CollectionDepartment).ScriptBlock
    $originalRead=(Get-Command Read-CollectionInventory).ScriptBlock
    $originalSend=(Get-Command Send-CollectionInventory).ScriptBlock
    function Get-CollectionDepartment {
        param($Config,$Name)
        if($Name -eq '查询失败'){throw 'Mock unavailable'}
        if($Name -eq '旧姓名'){Start-Sleep -Milliseconds 500;return @{result='matched';department='旧部门'}}
        if($Name -eq '新姓名'){return @{result='matched';department='新部门'}}
        @{result='matched';department='研发部'}
    }
    try {
        foreach($scenario in @('manual','stale','unavailable','focused')){
            $testState=@{Changed=$false;Since=$null;Error=$null;Started=[DateTime]::UtcNow}
            $timer=New-Object Windows.Forms.Timer; $timer.Interval=80
            $timer.Add_Tick({
                $form=[Windows.Forms.Application]::OpenForms | Where-Object Text -eq '电脑资产登记' | Select-Object -First 1
                if(!$form){return}
                $layout=$form.Controls[0]; $inputs=@($layout.Controls | Where-Object {$_ -is [Windows.Forms.TextBox]})
                $status=@($layout.Controls | Where-Object {$_ -is [Windows.Forms.Label]})[-1].Text
                $buttons=$layout.Controls | Where-Object {$_ -is [Windows.Forms.FlowLayoutPanel]}
                if(([DateTime]::UtcNow-$testState.Started).TotalSeconds -gt 8){$testState.Error='Department UI timed out: '+$status; $form.Close();return}
                if($scenario -eq 'focused' -and !$testState.Changed){$null=$inputs[1].Focus();$testState.Changed=$true;$testState.Since=[DateTime]::UtcNow}
                if($scenario -eq 'focused' -and ([DateTime]::UtcNow-$testState.Since).TotalMilliseconds -gt 1200){
                    if($inputs[1].Text){$testState.Error='Lookup rewrote the focused department input'}
                    $inputs[1].Text='手填研发部';($buttons.Controls|Where-Object Text -eq '确认并提交').PerformClick();$timer.Stop()
                }
                if($scenario -eq 'manual' -and !$testState.Changed -and $inputs[1].Text -eq '研发部'){
                    $inputs[1].Text='手工运营部'; $inputs[0].Text='另一个姓名'; $testState.Changed=$true; $testState.Since=[DateTime]::UtcNow
                }
                if($scenario -eq 'manual' -and $testState.Changed -and ([DateTime]::UtcNow-$testState.Since).TotalMilliseconds -gt 900){
                    if($inputs[1].Text -ne '手工运营部'){$testState.Error='Manual department overwritten'}
                    ($buttons.Controls|Where-Object Text -eq '确认并提交').PerformClick();$timer.Stop()
                }
                if($scenario -eq 'stale' -and !$testState.Changed -and $status -eq '正在匹配部门…'){$inputs[0].Text='新姓名';$testState.Changed=$true}
                if($scenario -eq 'stale' -and $inputs[1].Text -eq '旧部门'){$testState.Error='Old query applied after name changed';$form.Close()}
                if($scenario -eq 'stale' -and $inputs[1].Text -eq '新部门'){($buttons.Controls|Where-Object Text -eq '确认并提交').PerformClick();$timer.Stop()}
                if($scenario -eq 'unavailable' -and $status -like '*查询暂不可用*'){$inputs[1].Text='手填部门';($buttons.Controls|Where-Object Text -eq '确认并提交').PerformClick();$timer.Stop()}
            })
            $name=switch($scenario){manual{'自动匹配'} stale{'旧姓名'} unavailable{'查询失败'} focused{'输入中'}}
            try {$timer.Start();$person=Show-CollectionUserForm -UserName $name -Config @{endpoint='test-only'} -Inventory $inventory}
            finally {$timer.Stop();$timer.Dispose()}
            if($testState.Error){throw $testState.Error}
            if(!$person){throw 'Lookup prevented submission'}
        }
        function Read-CollectionInventory { Start-Sleep -Milliseconds 120; @{hardwareUuid='test-progress'} }
        function Send-CollectionInventory { param($Config,$Body) @{result='pending_review'} }
        if((Invoke-CollectionStage -Stage collect).hardwareUuid -ne 'test-progress'){throw 'Background collect result lost'}
        if((Invoke-CollectionStage -Stage send -Config @{} -Body '{}').result -ne 'pending_review'){throw 'Background send result lost'}
        $timer=New-Object Windows.Forms.Timer; $timer.Interval=120
        $timer.Add_Tick({
            $form=[Windows.Forms.Application]::OpenForms|Where-Object Text -eq '电脑资产采集'|Select-Object -First 1
            if($form){$form.Close();$timer.Stop()}
        })
        try {$timer.Start();Show-CollectionResult -Title '测试提交结果' -Message '模拟成功，不提交实际资产'}finally{$timer.Stop();$timer.Dispose()}
        Write-Host 'PASS department lookup: autofill, manual override, stale responses, unavailable fallback; background progress and result window.'
    } finally {
        Set-Item Function:Get-CollectionDepartment $originalLookup
        Set-Item Function:Read-CollectionInventory $originalRead
        Set-Item Function:Send-CollectionInventory $originalSend
    }
}
