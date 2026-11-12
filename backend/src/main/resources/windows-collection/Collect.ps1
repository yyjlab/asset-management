param([switch]$LibraryOnly)
$ErrorActionPreference='Stop'
function Convert-MonitorText {
    param($Values)
    $text= -join @($Values | Where-Object {$_ -ge 32 -and $_ -le 126} | ForEach-Object {[char]$_})
    if($text.Trim() -match '^(0+|unknown|none|n/a)$'){return $null}
    if(!$text.Trim()){return $null}
    return $text.Trim()
}
function Read-CollectionDisplays {
    param($Computer)
    $type='unknown'
    if($Computer.PCSystemType -eq 2){$type='laptop'}elseif($Computer.PCSystemType -in @(1,3)){$type='desktop'}
    $monitors=@(); $status='ok'
    try {
        $ids=@(Get-CimInstance -Namespace root/wmi -ClassName WmiMonitorID -OperationTimeoutSec 15 | Where-Object Active)
        $sizes=@{}; $connections=@{}
        try {Get-CimInstance -Namespace root/wmi -ClassName WmiMonitorBasicDisplayParams -OperationTimeoutSec 15 | Where-Object Active | ForEach-Object {$sizes[$_.InstanceName]=$_}}catch{$status='partial'}
        try {Get-CimInstance -Namespace root/wmi -ClassName WmiMonitorConnectionParams -OperationTimeoutSec 15 | Where-Object Active | ForEach-Object {$connections[$_.InstanceName]=$_}}catch{$status='partial'}
        $seen=@{}
        foreach($monitor in $ids){
            $id=[string]$monitor.InstanceName
            if(!$id -or $seen.ContainsKey($id)){continue}; $seen[$id]=$true
            $link='unknown'
            if($connections.ContainsKey($id)){
                $technology=[long]$connections[$id].VideoOutputTechnology
                if($technology -in @(6,11,13,2147483648)){$link='internal'}elseif($technology -in @(0,4,5,8,10,12,16)){$link='external'}
            }
            if($link -eq 'internal'){continue}
            $size=$null
            if($sizes.ContainsKey($id)){
                $width=[double]$sizes[$id].MaxHorizontalImageSize; $height=[double]$sizes[$id].MaxVerticalImageSize
                if($width -gt 0 -and $height -gt 0){$size=[math]::Round([math]::Sqrt($width*$width+$height*$height)/2.54,1);if($size -lt 1 -or $size -gt 200){$size=$null}}
            }
            $brand=Convert-MonitorText $monitor.ManufacturerName
            $brands=@{DEL='Dell';HWP='HP';HPN='HP';LEN='Lenovo';SAM='Samsung';GSM='LG';ACR='Acer';AUS='ASUS';APP='Apple';BNQ='BenQ';PHL='Philips';AOC='AOC'}
            if($brand -and $brands.ContainsKey($brand)){$brand=$brands[$brand]}
            $serial=Convert-MonitorText $monitor.SerialNumberID
            if(!$brand -or !$serial -or !$size -or $link -eq 'unknown'){$status='partial'}
            if($id.Length -gt 160){$sha=[Security.Cryptography.SHA256]::Create();try{$id=[BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($id))).Replace('-','')}finally{$sha.Dispose()}}
            $monitors+=@{deviceKey=$id;connection=$link;brand=$brand;serialNumber=$serial;sizeInches=$size}
        }
    } catch {$status='unavailable';$monitors=@()}
    @{platform='windows';computerType=$type;status=$status;monitors=@($monitors)}
}
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
    @{hardwareUuid=$hardware;name=$name;capturedAt=[DateTime]::UtcNow.ToString('o');collection=(Read-CollectionDisplays $computer);snapshot=@{
        model=$model;cpu=$cpu;memory=$memory;internalDisks=$disks;graphicsCards=$graphics;manufacturerSerialNumber=([string]$bios.SerialNumber).Trim()}}
}
function Get-CollectionDepartment {
    param($Config,[string]$Name)
    $url=[uri]$Config.endpoint
    if(!$url.IsAbsoluteUri -or $url.Scheme -notin @('http','https') -or $url.UserInfo -or $url.Query -or $url.Fragment -or $url.AbsolutePath -cne '/api/integrations/ad-deployment/v1/windows-intakes'){throw '采集包地址无效'}
    [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12
    [Net.WebRequest]::DefaultWebProxy=$null
    $target=$url.GetLeftPart([UriPartial]::Authority)+'/api/integrations/ad-deployment/v1/collector-person'
    $body=@{name=$Name.Trim()}|ConvertTo-Json -Compress
    $response=Invoke-WebRequest -UseBasicParsing -Uri $target -Method Post -MaximumRedirection 0 -TimeoutSec 5 -Headers @{Authorization=('Bearer '+$Config.token)} -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
    # Windows PowerShell 5.1 may decode JSON without a charset as Latin-1.
    try { [Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray()).TrimStart([char]0xFEFF) | ConvertFrom-Json }
    finally { $response.RawContentStream.Dispose() }
}
function Show-CollectionUserForm {
    param([string]$UserName, [string]$Department, $Inventory, $Config)
    Add-Type -AssemblyName System.Windows.Forms
    Add-Type -AssemblyName System.Drawing
    [Windows.Forms.Application]::EnableVisualStyles()
    $form=New-Object System.Windows.Forms.Form
    $form.Text='电脑资产登记'
    $form.StartPosition='CenterScreen'
    $form.FormBorderStyle='FixedDialog'
    $form.MaximizeBox=$false; $form.MinimizeBox=$false
    $form.AutoScaleMode='Dpi'
    $form.BackColor=[Drawing.Color]::White
    $form.ClientSize=New-Object System.Drawing.Size(560,404)
    $form.Font=New-Object System.Drawing.Font('Microsoft YaHei UI',10)
    $layout=New-Object System.Windows.Forms.TableLayoutPanel
    $layout.Dock='Fill'; $layout.Padding=New-Object System.Windows.Forms.Padding(24)
    $layout.AutoScroll=$true
    $layout.ColumnCount=1; $layout.RowCount=8
    foreach($height in @(48,80,26,42,26,42,34,52)){$layout.RowStyles.Add((New-Object System.Windows.Forms.RowStyle('Absolute',$height))) | Out-Null}
    $hint=New-Object System.Windows.Forms.Label
    $hint.Text='确认电脑使用人'
    $hint.Font=New-Object Drawing.Font('Microsoft YaHei UI',17,[Drawing.FontStyle]::Bold)
    $hint.ForeColor=[Drawing.ColorTranslator]::FromHtml('#1D4ED8')
    $hint.Dock='Fill'; $layout.Controls.Add($hint,0,0)
    $summary=New-Object System.Windows.Forms.Label
    $summary.Dock='Fill'; $summary.AutoEllipsis=$true
    $summary.ForeColor=[Drawing.ColorTranslator]::FromHtml('#526174')
    $summary.Font=New-Object Drawing.Font('Microsoft YaHei UI',9)
    $summary.Text="填写实际使用人，不是装机人员。`r`n提交后由管理员审核，不会直接领用。"
    if($Inventory){
        $external=@($Inventory.collection.monitors|Where-Object connection -eq 'external').Count
        $device=if($Inventory.name){[string]$Inventory.name}else{'设备名称未采集'}
        $memory=if($Inventory.snapshot.memory){[string]$Inventory.snapshot.memory}else{'未采集'}
        $screens=if($Inventory.collection.status -eq 'unavailable'){'未读取'}else{[string]$external+' 台'}
        $summary.Text=$device+"`r`n内存："+$memory+"  ·  外接显示器："+$screens+"`r`n填写实际使用人，提交后等待资产管理员审核。"
    }
    $layout.Controls.Add($summary,0,1)
    $nameLabel=New-Object System.Windows.Forms.Label
    $nameLabel.Text='使用人姓名 *'; $nameLabel.Dock='Fill'; $layout.Controls.Add($nameLabel,0,2)
    $nameInput=New-Object System.Windows.Forms.TextBox
    $nameInput.Text=$UserName; $nameInput.MaxLength=120; $nameInput.Dock='Top'; $nameInput.Font=New-Object Drawing.Font('Microsoft YaHei UI',11); $nameInput.BorderStyle='FixedSingle'; $layout.Controls.Add($nameInput,0,3)
    $departmentLabel=New-Object System.Windows.Forms.Label
    $departmentLabel.Text='部门（选填）'; $departmentLabel.Dock='Fill'; $layout.Controls.Add($departmentLabel,0,4)
    $departmentInput=New-Object System.Windows.Forms.TextBox
    $departmentInput.Text=$Department; $departmentInput.MaxLength=120; $departmentInput.Dock='Top'; $departmentInput.Font=New-Object Drawing.Font('Microsoft YaHei UI',11); $departmentInput.BorderStyle='FixedSingle'; $layout.Controls.Add($departmentInput,0,5)
    $nameInput.ImeMode='On'; $departmentInput.ImeMode='On'
    $errorLabel=New-Object System.Windows.Forms.Label
    $errorLabel.ForeColor=[Drawing.Color]::Firebrick; $errorLabel.Dock='Fill'; $layout.Controls.Add($errorLabel,0,6)
    $buttons=New-Object System.Windows.Forms.FlowLayoutPanel
    $buttons.Dock='Fill'; $buttons.FlowDirection='RightToLeft'; $layout.Controls.Add($buttons,0,7)
    $submit=New-Object System.Windows.Forms.Button
    $submit.Text='确认并提交'; $submit.Size=New-Object Drawing.Size(136,38); $submit.FlatStyle='Flat'; $submit.FlatAppearance.BorderSize=0
    $submit.BackColor=[Drawing.ColorTranslator]::FromHtml('#2563EB'); $submit.ForeColor=[Drawing.Color]::White
    $submit.Add_Click({
        if(!$nameInput.Text.Trim()){$errorLabel.Text='请填写实际使用人姓名。'; $nameInput.Focus(); return}
        $form.DialogResult=[Windows.Forms.DialogResult]::OK
    })
    $cancel=New-Object System.Windows.Forms.Button
    $cancel.Text='取消'; $cancel.Size=New-Object Drawing.Size(88,38); $cancel.FlatStyle='Flat'; $cancel.FlatAppearance.BorderColor=[Drawing.ColorTranslator]::FromHtml('#CBD5E1'); $cancel.DialogResult='Cancel'
    $buttons.Controls.Add($submit); $buttons.Controls.Add($cancel)
    $form.Controls.Add($layout); $form.AcceptButton=$submit; $form.CancelButton=$cancel
    $lookup=@{Worker=$null;Pending=$null;Name='';LastQueried='';Due=[DateTime]::UtcNow;Manual=![string]::IsNullOrWhiteSpace($Department);Programmatic=$false;AutoDepartment=''}
    if($lookup.Manual){$errorLabel.ForeColor=[Drawing.ColorTranslator]::FromHtml('#526174');$errorLabel.Text='保留上次填写的部门，可修改。'}
    $nameInput.Add_TextChanged({
        $lookup.Due=[DateTime]::UtcNow.AddMilliseconds(650)
        if(!$lookup.Manual -and $lookup.AutoDepartment -and $departmentInput.Text -eq $lookup.AutoDepartment){
            $lookup.Programmatic=$true; $departmentInput.Text=''; $lookup.Programmatic=$false
            $lookup.AutoDepartment=''; $lookup.Manual=$false
        }
    })
    $departmentInput.Add_TextChanged({if(!$lookup.Programmatic){$lookup.Manual=$true}})
    $timer=New-Object Windows.Forms.Timer
    $timer.Interval=100
    $timer.Add_Tick({
        # Do not rewrite text or lookup hints while the department IME is composing.
        if($departmentInput.Focused){return}
        $current=$nameInput.Text.Trim()
        if($lookup.Worker){
            if(!$lookup.Pending.IsCompleted){return}
            try {
                $answer=$lookup.Worker.EndInvoke($lookup.Pending)
                if($lookup.Name -eq $current -and !$lookup.Manual){
                    $errorLabel.ForeColor=[Drawing.ColorTranslator]::FromHtml('#526174')
                    if($answer.Count -eq 1 -and $answer[0].result -eq 'matched' -and $answer[0].department -is [string] -and $answer[0].department.Length -le 120){
                        $lookup.Programmatic=$true; $departmentInput.Text=$answer[0].department; $lookup.Programmatic=$false
                        $lookup.AutoDepartment=$departmentInput.Text; $errorLabel.Text='已根据登记核对名单填写部门，可修改。'
                    }else{$errorLabel.Text='名单未匹配到部门，请手动填写（可选）。'}
                }
            } catch {if($lookup.Name -eq $current -and !$lookup.Manual){$errorLabel.Text='部门查询暂不可用，可手动填写后提交。'}}
            finally {$lookup.Worker.Dispose(); $lookup.Worker=$null; $lookup.Pending=$null}
        }
        if(!$Config -or !$current -or $lookup.Manual -or $current -eq $lookup.LastQueried -or [DateTime]::UtcNow -lt $lookup.Due){return}
        $lookup.Name=$current; $lookup.LastQueried=$current
        $errorLabel.ForeColor=[Drawing.ColorTranslator]::FromHtml('#526174'); $errorLabel.Text='正在匹配部门…'
        $lookup.Worker=[PowerShell]::Create()
        $script="`$ErrorActionPreference='Stop'; function Get-CollectionDepartment {"+(Get-Command Get-CollectionDepartment -CommandType Function).Definition+'}'
        $null=$lookup.Worker.AddScript($script).AddStatement().AddScript('param($Config,$Name) Get-CollectionDepartment $Config $Name').AddArgument($Config).AddArgument($current)
        $lookup.Pending=$lookup.Worker.BeginInvoke()
    })
    try {
        $timer.Start()
        if($form.ShowDialog() -ne [Windows.Forms.DialogResult]::OK){return $null}
        return @{reportedUserName=$nameInput.Text.Trim(); reportedDepartment=$departmentInput.Text.Trim()}
    } finally {
        $timer.Stop(); $timer.Dispose()
        if($lookup.Worker){$lookup.Worker.Stop(); $lookup.Worker.Dispose()}
        $form.Dispose()
    }
}
function Invoke-CollectionStage {
    param([ValidateSet('collect','send')][string]$Stage, $Config, [string]$Body)
    Add-Type -AssemblyName System.Windows.Forms
    Add-Type -AssemblyName System.Drawing
    [Windows.Forms.Application]::EnableVisualStyles()
    $form=New-Object Windows.Forms.Form
    $form.Text='电脑资产采集'; $form.StartPosition='CenterScreen'; $form.FormBorderStyle='FixedDialog'
    $form.ControlBox=$false; $form.BackColor=[Drawing.Color]::White; $form.AutoScaleMode='Dpi'
    $form.ClientSize=New-Object Drawing.Size(500,196); $form.Font=New-Object Drawing.Font('Microsoft YaHei UI',10)
    $layout=New-Object Windows.Forms.TableLayoutPanel
    $layout.Dock='Fill'; $layout.Padding=New-Object Windows.Forms.Padding(24); $layout.ColumnCount=1; $layout.RowCount=3
    foreach($height in @(46,44,38)){$layout.RowStyles.Add((New-Object Windows.Forms.RowStyle('Absolute',$height)))|Out-Null}
    $title=New-Object Windows.Forms.Label
    $title.Dock='Fill'; $title.Font=New-Object Drawing.Font('Microsoft YaHei UI',16,[Drawing.FontStyle]::Bold)
    $title.ForeColor=[Drawing.ColorTranslator]::FromHtml('#1D4ED8')
    $title.Text=if($Stage -eq 'collect'){'正在读取电脑配置'}else{'正在提交资产信息'}
    $layout.Controls.Add($title,0,0)
    $description=New-Object Windows.Forms.Label
    $description.Dock='Fill'; $description.ForeColor=[Drawing.ColorTranslator]::FromHtml('#526174')
    $description.Text=if($Stage -eq 'collect'){'采集电脑和外接显示器，请稍候。'}else{'正在连接资产系统，使用 U 盘时请勿拔出。'}
    $layout.Controls.Add($description,0,1)
    $progress=New-Object Windows.Forms.ProgressBar
    $progress.Dock='Top'; $progress.Height=12; $progress.Style='Marquee'; $progress.MarqueeAnimationSpeed=30
    $layout.Controls.Add($progress,0,2); $form.Controls.Add($layout)
    $worker=[PowerShell]::Create()
    try {
        $definitions="`$ErrorActionPreference='Stop'`n"
        foreach($name in @('Convert-MonitorText','Read-CollectionDisplays','Read-CollectionInventory','Send-CollectionInventory')){
            $definitions+="function $name {"+(Get-Command $name -CommandType Function).Definition+"}`n"
        }
        $null=$worker.AddScript($definitions).AddStatement()
        if($Stage -eq 'collect'){$null=$worker.AddScript('Read-CollectionInventory')}
        else{$null=$worker.AddScript('param($Config,$Body) Send-CollectionInventory -Config $Config -Body $Body').AddArgument($Config).AddArgument($Body)}
        $form.Show(); $form.Refresh()
        $pending=$worker.BeginInvoke()
        while(!$pending.IsCompleted){[Windows.Forms.Application]::DoEvents(); Start-Sleep -Milliseconds 60}
        $result=$worker.EndInvoke($pending)
        if($result.Count -ne 1){throw '采集程序没有返回有效结果，请重新运行并核对。'}
        return $result[0]
    } finally { $worker.Dispose(); $form.Close(); $form.Dispose() }
}
function Show-CollectionResult {
    param([string]$Title,[string]$Message,[ValidateSet('success','warning','error')][string]$Kind='success')
    Add-Type -AssemblyName System.Windows.Forms
    Add-Type -AssemblyName System.Drawing
    [Windows.Forms.Application]::EnableVisualStyles()
    $form=New-Object Windows.Forms.Form
    $form.Text='电脑资产采集'; $form.StartPosition='CenterScreen'; $form.FormBorderStyle='FixedDialog'
    $form.MaximizeBox=$false; $form.MinimizeBox=$false; $form.AutoScaleMode='Dpi'; $form.BackColor=[Drawing.Color]::White
    $form.ClientSize=New-Object Drawing.Size(540,278); $form.Font=New-Object Drawing.Font('Microsoft YaHei UI',10)
    $layout=New-Object Windows.Forms.TableLayoutPanel
    $layout.Dock='Fill'; $layout.Padding=New-Object Windows.Forms.Padding(24); $layout.ColumnCount=1; $layout.RowCount=3
    foreach($height in @(48,128,48)){$layout.RowStyles.Add((New-Object Windows.Forms.RowStyle('Absolute',$height)))|Out-Null}
    $heading=New-Object Windows.Forms.Label
    $heading.Text=$Title; $heading.Dock='Fill'; $heading.Font=New-Object Drawing.Font('Microsoft YaHei UI',17,[Drawing.FontStyle]::Bold)
    $colors=@{success='#15803D';warning='#92400E';error='#B42318'}; $heading.ForeColor=[Drawing.ColorTranslator]::FromHtml($colors[$Kind])
    $layout.Controls.Add($heading,0,0)
    $detail=New-Object Windows.Forms.TextBox
    $detail.Multiline=$true; $detail.ReadOnly=$true; $detail.BorderStyle='None'; $detail.BackColor=[Drawing.Color]::White
    $detail.ForeColor=[Drawing.ColorTranslator]::FromHtml('#526174'); $detail.Dock='Fill'; $detail.ScrollBars='Vertical'; $detail.Text=$Message
    $layout.Controls.Add($detail,0,1)
    $buttons=New-Object Windows.Forms.FlowLayoutPanel
    $buttons.Dock='Fill'; $buttons.FlowDirection='RightToLeft'
    $close=New-Object Windows.Forms.Button
    $close.Text='关闭'; $close.Size=New-Object Drawing.Size(108,36); $close.FlatStyle='Flat'; $close.FlatAppearance.BorderSize=0
    $close.BackColor=[Drawing.ColorTranslator]::FromHtml('#2563EB'); $close.ForeColor=[Drawing.Color]::White; $close.DialogResult='OK'
    $buttons.Controls.Add($close); $layout.Controls.Add($buttons,0,2); $form.Controls.Add($layout); $form.AcceptButton=$close; $form.CancelButton=$close
    try{$null=$form.ShowDialog()}finally{$form.Dispose()}
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
            if($status -in @(401,403)){throw '提交被拒绝：请检查采集包是否过期或已撤销，并在资产系统核对登记设置。'}
            if($status -ge 400 -and $status -lt 500){throw ('提交被拒绝（HTTP '+$status+'），请在资产系统核对字段或服务设置，本地资料已保留。')}
            if($attempt -eq 3){throw '未能连接资产系统，采集资料已保留在 U 盘，下次运行可重试。'}
            Start-Sleep -Seconds 2
        }
    }
}
if($LibraryOnly){return}
try {
    $config=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'config.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    Write-Host '正在采集电脑配置……'
    $inventory=Invoke-CollectionStage -Stage collect
    $directory=Join-Path $PSScriptRoot 'records'
    if(!(Test-Path -LiteralPath $directory)){New-Item -ItemType Directory -Path $directory | Out-Null}
    $file=Join-Path $directory ($inventory.hardwareUuid+'.json')
    if(Test-Path -LiteralPath $file){
        if((Get-Item -LiteralPath $file).Length -gt 32768){throw '本地采集记录过大，未发送。'}
        $body=[IO.File]::ReadAllText($file)
        if(($body|ConvertFrom-Json).hardwareUuid -cne $inventory.hardwareUuid){throw '本地采集记录身份不匹配，未发送。'}
    }else{$body=$inventory|ConvertTo-Json -Depth 8 -Compress}
    $saved=$body|ConvertFrom-Json
    $saved | Add-Member -NotePropertyName collection -NotePropertyValue $inventory.collection -Force
    Write-Host ('本次检测屏幕 '+$inventory.collection.monitors.Count+' 块；外接 '+@($inventory.collection.monitors|Where-Object connection -eq 'external').Count+' 台。检测状态：'+$inventory.collection.status)
    $person=Show-CollectionUserForm -UserName $saved.reportedUserName -Department $saved.reportedDepartment -Inventory $inventory -Config $config
    if($null -eq $person){Write-Host '已取消，未发送电脑配置。'; exit 0}
    $saved | Add-Member -NotePropertyName reportedUserName -NotePropertyValue $person.reportedUserName -Force
    $saved | Add-Member -NotePropertyName reportedDepartment -NotePropertyValue $person.reportedDepartment -Force
    $body=$saved|ConvertTo-Json -Depth 8 -Compress
    [IO.File]::WriteAllText($file,$body,[Text.UTF8Encoding]::new($false))
    Write-Host '正在提交资产系统，请勿拔出 U 盘……'
    $result=Invoke-CollectionStage -Stage send -Config $config -Body $body
    if($result.result -notin @('pending_review','approved','rejected') -or $result.externalRegistrationId -cne ('windows:'+$inventory.hardwareUuid)){throw '服务器回报不匹配，请在资产系统核对；不要当成提交成功。'}
    if($result.result -eq 'approved'){Show-CollectionResult -Title '这台电脑已登记' -Kind warning -Message ("资产编号："+$result.asset.assetTag+"`r`n`r`n未重复创建电脑，也未更新显示器。`r`n请在资产系统核对已有记录。"); Write-Host ('已有入库记录：'+$result.asset.assetTag)}
    elseif($result.result -eq 'rejected'){Show-CollectionResult -Title '已有未批准记录' -Kind warning -Message '请由管理员在资产入库中修改并重新送审。本次未创建资产。'}
    else{Show-CollectionResult -Title '提交成功，等待审核' -Message "电脑配置及使用人信息已送到资产系统。`r`n`r`n尚未直接创建或领用资产，请等待管理员批准。`r`n现在可以关闭窗口；使用 U 盘时可以安全拔盘。"}
    exit 0
} catch {
    $message=if($config -and $config.token){$_.Exception.Message.Replace([string]$config.token,'[REDACTED]')}else{'采集包配置读取失败，请从资产系统重新下载并完整解压。'}
    Write-Host ('未完成：'+$message) -ForegroundColor Red
    Show-CollectionResult -Title '本次采集未完成' -Kind error -Message ($message+"`r`n`r`n未自动创建资产。请核对网络、采集包有效期或联系管理员后重试。")
    exit 1
}
