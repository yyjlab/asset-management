# 免安装采集文件

Windows 在“设置 → 电脑采集”选择 Windows 和“免安装文件”，下载 EXE 后双击运行。无需解压或安装；填写使用人后提交资产入库审核。

EXE 使用 Windows 自带的 .NET Framework 和 Windows PowerShell，在当前用户本地缓存目录展开脚本，不添加服务或开机启动项。文件包含可撤销的限时采集凭据，请勿公开分发。当前未进行代码签名，系统可能提示发布者未知。

修改启动器源码后，在 Windows 仓库根目录重新构建一次，再执行后端构建：

```powershell
pwsh -File scripts/build-windows-collection-launcher.ps1
```

仅修改采集脚本不需要重新编译启动器；下载时会封装当前脚本。

macOS 的 DMG 尚未完成真实 macOS 构建与挂载验证，页面暂不开放该格式。现有 macOS 脚本 ZIP 仍可使用，不能视为已完成免解压版本。
