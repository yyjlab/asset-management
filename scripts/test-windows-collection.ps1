param([switch]$TestDialog)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/../backend/src/main/resources/windows-collection/Collect.ps1" -LibraryOnly
function Get-CimInstance {
    param($ClassName, $OperationTimeoutSec)
    switch ($ClassName) {
        'Win32_ComputerSystemProduct' { [pscustomobject]@{ UUID='12345678-1234-1234-1234-123456789abc'; Version='ThinkPad L13' } }
        'Win32_ComputerSystem' { [pscustomobject]@{ Model='20R4'; SystemFamily='ThinkPad L13'; Manufacturer='LENOVO'; TotalPhysicalMemory=8GB } }
        'Win32_BIOS' { [pscustomobject]@{ SerialNumber='sample' } }
        'Win32_Processor' { [pscustomobject]@{ Name='Test CPU' } }
        'Win32_VideoController' { [pscustomobject]@{ Name='Microsoft Basic Display Adapter' }; [pscustomobject]@{ Name='Intel Graphics' } }
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
$script:sent=0
function Invoke-RestMethod {
    param([switch]$UseBasicParsing, $Uri, $Method, $MaximumRedirection, $TimeoutSec, $Headers, $ContentType, $Body)
    if($MaximumRedirection -ne 0 -or $TimeoutSec -ne 30 -or $Method -ne 'Post'){throw 'Unsafe request options'}
    if($Headers.Authorization -ne 'Bearer test-only'){throw 'Credential missing'}
    if(([Text.Encoding]::UTF8.GetString($Body)|ConvertFrom-Json).hardwareUuid -ne $inventory.hardwareUuid){throw 'Snapshot changed'}
    $script:sent++
    @{result='pending_review'}
}
$null=Send-CollectionInventory @{endpoint='http://asset.example/api/integrations/ad-deployment/v1/windows-intakes';token='test-only'} ($inventory|ConvertTo-Json -Depth 8)
if($script:sent -ne 1){throw 'Request count incorrect'}
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
        $inputs[0].Text='测试使用人'; $inputs[1].Text='测试部门'
        $buttons=$layout.Controls | Where-Object {$_ -is [Windows.Forms.FlowLayoutPanel]}
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
}
