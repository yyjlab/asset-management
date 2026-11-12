# 免安装采集文件

Windows 在“设置 → 电脑采集”选择 Windows 和“免安装文件”，下载 EXE 后双击运行。无需解压或安装；填写使用人后提交资产入库审核。

EXE 使用 Windows 自带的 .NET Framework 和 Windows PowerShell，在当前用户本地缓存目录展开脚本，不添加服务或开机启动项。文件包含可撤销的限时采集凭据，请勿公开分发。当前未进行代码签名，系统可能提示发布者未知。

修改启动器源码后，在 Windows 仓库根目录重新构建一次，再执行后端构建：

```powershell
pwsh -File scripts/build-windows-collection-launcher.ps1
```

仅修改采集脚本不需要重新编译启动器；下载时会封装当前脚本。

macOS 选择免安装文件后下载 DMG，双击打开磁盘映像，再双击 Asset Collector；不用解压或复制到“应用程序”。程序在当前用户的 Application Support 中保存配置及重试记录。当前未签名或公证，不应关闭系统安全保护；企业终端如阻止运行，请联系管理员。

macOS 模板由 GitHub Actions 的 Build macOS collector 工作流构建；构建包含配置槽写入、挂载及测试 JXA 执行验证，产物不含真实凭据。模板改动后需要取回通过验证的产物放入后端资源再构建后端。尚需在实际 Intel 和 Apple Silicon 员工电脑验证完整采集、弹窗与送审。
