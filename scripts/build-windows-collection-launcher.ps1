param([string]$Output = "$PSScriptRoot/../backend/src/main/resources/windows-collection/native/windows-launcher.exe")
$ErrorActionPreference='Stop'
$compiler=Join-Path ([Runtime.InteropServices.RuntimeEnvironment]::GetRuntimeDirectory()) 'csc.exe'
if(!(Test-Path -LiteralPath $compiler)){$compiler=Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'}
if(!(Test-Path -LiteralPath $compiler)){throw '.NET Framework C# compiler is required on the Windows build machine.'}
$target=[IO.Path]::GetFullPath($Output)
$null=New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($target)) -Force
$source=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'collection-launchers/WindowsLauncher.cs'))
$manifest=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'collection-launchers/windows.manifest'))
& $compiler /nologo /optimize+ /target:winexe /platform:anycpu /reference:System.Windows.Forms.dll /reference:System.Web.Extensions.dll "/win32manifest:$manifest" "/out:$target" $source
if($LASTEXITCODE -ne 0){throw 'Windows collector launcher compilation failed.'}
Write-Host "Built launcher: $target"
