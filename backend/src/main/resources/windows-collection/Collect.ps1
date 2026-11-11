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
    }else{
        $body=$inventory|ConvertTo-Json -Depth 8 -Compress
        [IO.File]::WriteAllText($file,$body,[Text.UTF8Encoding]::new($false))
    }
    Write-Host '正在提交资产系统，请勿拔出 U 盘……'
    $result=Send-CollectionInventory $config $body
    if($result.result -notin @('pending_review','approved','rejected') -or $result.externalRegistrationId -cne ('windows:'+$inventory.hardwareUuid)){throw '服务器回报不匹配，请在资产系统核对；不要当成提交成功。'}
    if($result.result -eq 'approved'){Write-Host ('已有入库记录：'+$result.asset.assetTag+'，未重复创建。') -ForegroundColor Green}
    elseif($result.result -eq 'rejected'){Write-Host '这台电脑已有未批准记录，请在资产入库中修改并重新送审。' -ForegroundColor Yellow}
    else{Write-Host '提交成功，请到资产入库的待审核列表核对。现在可以拔出 U 盘，采集下一台。' -ForegroundColor Green}
    exit 0
} catch {
    $message=if($config -and $config.token){$_.Exception.Message.Replace([string]$config.token,'[REDACTED]')}else{'采集包配置读取失败，请从资产系统重新下载并完整解压。'}
    Write-Host ('未完成：'+$message) -ForegroundColor Red
    exit 1
}
