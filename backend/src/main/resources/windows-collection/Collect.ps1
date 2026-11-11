param([switch]$LibraryOnly)
$ErrorActionPreference='Stop'
function Read-CollectionInventory {
    $product=Get-CimInstance Win32_ComputerSystemProduct -OperationTimeoutSec 15
    $hardware=([guid]$product.UUID).ToString()
    if($hardware -in @('00000000-0000-0000-0000-000000000000','ffffffff-ffff-ffff-ffff-ffffffffffff')){throw '硬件 UUID 无效，不能使用 SN 替代。'}
    $computer=Get-CimInstance Win32_ComputerSystem -OperationTimeoutSec 15
    $bios=Get-CimInstance Win32_BIOS -OperationTimeoutSec 15
    $cpu=@(Get-CimInstance Win32_Processor -OperationTimeoutSec 15 | ForEach-Object {$_.Name.Trim()} | Sort-Object -Unique) -join '; '
    $disks=@()
    try {
        $disks=@(Get-Disk | Where-Object { [string]$_.BusType -in @('NVMe','SATA','ATA','SAS','SCSI','RAID','MMC') -and $_.Size -gt 0 } |
            Sort-Object Number | ForEach-Object { @{model=([string]$_.FriendlyName).Trim();capacityBytes=[long]$_.Size;busType=[string]$_.BusType} })
    } catch { Write-Host '内置硬盘未采集，请在审核时核对；可尝试右键以管理员身份运行。' -ForegroundColor Yellow }
    $graphics=@(Get-CimInstance Win32_VideoController -OperationTimeoutSec 15 | ForEach-Object {$_.Name} |
        Where-Object {$_ -and $_ -notmatch '(?i)basic.*(display|render)|基本.*(显示|呈现)|standard.*vga|标准.*vga'} | Sort-Object -Unique)
    $model=([string]$product.Version).Trim()
    if(!$model -or $model -match '(?i)^(none|not specified|default string|to be filled.*)$'){$model=([string]$computer.Model).Trim()}
    $name=([string]$computer.SystemFamily).Trim()
    if(!$name -or $name -match '(?i)^(none|not specified|default string|to be filled.*)$'){$name=$model}
    $vendor=([string]$computer.Manufacturer).Trim()
    if($vendor -and $name -notlike "$vendor*"){$name="$vendor $name"}
    $memory=if($computer.TotalPhysicalMemory){([math]::Round($computer.TotalPhysicalMemory/1GB,2)).ToString([Globalization.CultureInfo]::InvariantCulture)+' GiB'}else{$null}
    @{hardwareUuid=$hardware;name=$name;capturedAt=[DateTime]::UtcNow.ToString('o');snapshot=@{
        model=$model;cpu=$cpu;memory=$memory;internalDisks=$disks;graphicsCards=$graphics;manufacturerSerialNumber=([string]$bios.SerialNumber).Trim()}}
}
function Show-CollectionUserForm {
    param([string]$UserName, [string]$Department)
    Add-Type -AssemblyName System.Windows.Forms
    Add-Type -AssemblyName System.Drawing
    $form=New-Object System.Windows.Forms.Form
    $form.Text='电脑资产登记'
    $form.StartPosition='CenterScreen'
    $form.FormBorderStyle='FixedDialog'
    $form.MaximizeBox=$false; $form.MinimizeBox=$false
    $form.AutoScaleMode='Dpi'
    $form.ClientSize=New-Object System.Drawing.Size(500,310)
    $form.Font=New-Object System.Drawing.Font('Microsoft YaHei UI',10)
    $layout=New-Object System.Windows.Forms.TableLayoutPanel
    $layout.Dock='Fill'; $layout.Padding=New-Object System.Windows.Forms.Padding(20)
    $layout.ColumnCount=1; $layout.RowCount=7
    $layout.RowStyles.Add((New-Object System.Windows.Forms.RowStyle('Absolute',60))) | Out-Null
    foreach($height in @(26,38,26,38,36,46)){$layout.RowStyles.Add((New-Object System.Windows.Forms.RowStyle('Absolute',$height))) | Out-Null}
    $hint=New-Object System.Windows.Forms.Label
    $hint.Text="请填写这台电脑的实际使用人，不是装机人员。`r`n确认后提交电脑配置，领用信息由管理员审核。"
    $hint.Dock='Fill'; $layout.Controls.Add($hint,0,0)
    $nameLabel=New-Object System.Windows.Forms.Label
    $nameLabel.Text='使用人姓名 *'; $nameLabel.Dock='Fill'; $layout.Controls.Add($nameLabel,0,1)
    $nameInput=New-Object System.Windows.Forms.TextBox
    $nameInput.Text=$UserName; $nameInput.MaxLength=120; $nameInput.Dock='Fill'; $layout.Controls.Add($nameInput,0,2)
    $departmentLabel=New-Object System.Windows.Forms.Label
    $departmentLabel.Text='部门（选填）'; $departmentLabel.Dock='Fill'; $layout.Controls.Add($departmentLabel,0,3)
    $departmentInput=New-Object System.Windows.Forms.TextBox
    $departmentInput.Text=$Department; $departmentInput.MaxLength=120; $departmentInput.Dock='Fill'; $layout.Controls.Add($departmentInput,0,4)
    $errorLabel=New-Object System.Windows.Forms.Label
    $errorLabel.ForeColor=[Drawing.Color]::Firebrick; $errorLabel.Dock='Fill'; $layout.Controls.Add($errorLabel,0,5)
    $buttons=New-Object System.Windows.Forms.FlowLayoutPanel
    $buttons.Dock='Fill'; $buttons.FlowDirection='RightToLeft'; $layout.Controls.Add($buttons,0,6)
    $submit=New-Object System.Windows.Forms.Button
    $submit.Text='确认并提交'; $submit.AutoSize=$true
    $submit.Add_Click({
        if(!$nameInput.Text.Trim()){$errorLabel.Text='请填写实际使用人姓名。'; $nameInput.Focus(); return}
        $form.DialogResult=[Windows.Forms.DialogResult]::OK
    })
    $cancel=New-Object System.Windows.Forms.Button
    $cancel.Text='取消'; $cancel.AutoSize=$true; $cancel.DialogResult='Cancel'
    $buttons.Controls.Add($submit); $buttons.Controls.Add($cancel)
    $form.Controls.Add($layout); $form.AcceptButton=$submit; $form.CancelButton=$cancel
    try {
        if($form.ShowDialog() -ne [Windows.Forms.DialogResult]::OK){return $null}
        return @{reportedUserName=$nameInput.Text.Trim(); reportedDepartment=$departmentInput.Text.Trim()}
    } finally { $form.Dispose() }
}
function Send-CollectionInventory {
    param($Config,[string]$Body)
    $url=[uri]$Config.endpoint
    if(!$url.IsAbsoluteUri -or $url.Scheme -notin @('http','https') -or $url.UserInfo -or $url.Query -or $url.Fragment -or
       $url.AbsolutePath -cne '/api/integrations/ad-deployment/v1/windows-intakes'){throw '采集包地址无效，请从资产系统重新下载。'}
    [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12
    [Net.WebRequest]::DefaultWebProxy=$null
    for($attempt=1;$attempt -le 3;$attempt++){
        try {
            return Invoke-RestMethod -UseBasicParsing -Uri $url -Method Post -MaximumRedirection 0 -TimeoutSec 30 `
                -Headers @{Authorization=('Bearer '+$Config.token)} -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($Body))
        } catch {
            $status=if($_.Exception.Response){[int]$_.Exception.Response.StatusCode}else{0}
            if($status -ge 400 -and $status -lt 500){throw '提交被拒绝：请检查采集包是否过期或已撤销，并在资产系统核对登记设置。'}
            if($attempt -eq 3){throw '未能连接资产系统，采集资料已保留在 U 盘，下次运行可重试。'}
            Start-Sleep -Seconds 2
        }
    }
}
if($LibraryOnly){return}
try {
    $config=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'config.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    Write-Host '正在采集电脑配置……'
    $inventory=Read-CollectionInventory
    $directory=Join-Path $PSScriptRoot 'records'
    if(!(Test-Path -LiteralPath $directory)){New-Item -ItemType Directory -Path $directory | Out-Null}
    $file=Join-Path $directory ($inventory.hardwareUuid+'.json')
    if(Test-Path -LiteralPath $file){
        if((Get-Item -LiteralPath $file).Length -gt 32768){throw '本地采集记录过大，未发送。'}
        $body=[IO.File]::ReadAllText($file)
        if(($body|ConvertFrom-Json).hardwareUuid -cne $inventory.hardwareUuid){throw '本地采集记录身份不匹配，未发送。'}
    }else{$body=$inventory|ConvertTo-Json -Depth 8 -Compress}
    $saved=$body|ConvertFrom-Json
    $person=Show-CollectionUserForm -UserName $saved.reportedUserName -Department $saved.reportedDepartment
    if($null -eq $person){Write-Host '已取消，未发送电脑配置。'; exit 0}
    $saved | Add-Member -NotePropertyName reportedUserName -NotePropertyValue $person.reportedUserName -Force
    $saved | Add-Member -NotePropertyName reportedDepartment -NotePropertyValue $person.reportedDepartment -Force
    $body=$saved|ConvertTo-Json -Depth 8 -Compress
    [IO.File]::WriteAllText($file,$body,[Text.UTF8Encoding]::new($false))
    Write-Host '正在提交资产系统，请勿拔出 U 盘……'
    $result=Send-CollectionInventory $config $body
    if($result.result -notin @('pending_review','approved','rejected') -or $result.externalRegistrationId -cne ('windows:'+$inventory.hardwareUuid)){throw '服务器回报不匹配，请在资产系统核对；不要当成提交成功。'}
    if($result.result -eq 'approved'){Write-Host ('已有入库记录：'+$result.asset.assetTag+'，未重复创建。') -ForegroundColor Green}
    elseif($result.result -eq 'rejected'){Write-Host '这台电脑已有未批准记录，请在资产入库中修改并重新送审。' -ForegroundColor Yellow}
    else{Write-Host '提交成功，使用人和部门已送资产入库审核，不代表已领用。可以关闭窗口；使用 U 盘时现在可安全拔盘。' -ForegroundColor Green}
    exit 0
} catch {
    $message=if($config -and $config.token){$_.Exception.Message.Replace([string]$config.token,'[REDACTED]')}else{'采集包配置读取失败，请从资产系统重新下载并完整解压。'}
    Write-Host ('未完成：'+$message) -ForegroundColor Red
    exit 1
}
