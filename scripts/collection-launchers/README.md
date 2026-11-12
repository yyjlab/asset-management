# 免安装采集文件

Windows 在“设置 → 电脑采集”选择 Windows 和“免安装文件”，下载 EXE 后双击运行。无需解压或安装；填写使用人后提交资产入库审核。

EXE 使用 Windows 自带的 .NET Framework 和 Windows PowerShell，在当前用户本地缓存目录展开脚本，不添加服务或开机启动项。文件包含可撤销的限时采集凭据，请勿公开分发。当前未进行代码签名，系统可能提示发布者未知。

修改启动器源码后，在 Windows 仓库根目录重新构建一次，再执行后端构建：

```powershell
pwsh -File scripts/build-windows-collection-launcher.ps1
```

仅修改采集脚本不需要重新编译启动器；下载时会封装当前脚本。

macOS 选择免安装文件后下载 DMG，双击打开磁盘映像，再双击 Asset Collector；不用解压或复制到“应用程序”。原生启动器包含 Intel 和 Apple Silicon 架构，在当前用户的 Application Support 中保存配置及重试记录。当前未使用 Apple Developer ID 签名或公证，不应关闭系统安全保护；企业终端如阻止运行，请联系管理员。

Mac 运行时显示当前采集步骤和等待秒数；姓名填写后按登记核对名单精确查询部门，可修改或手填。系统询问访问本地网络时请选择允许；曾拒绝时，在“系统设置 → 隐私与安全性 → 本地网络”检查 Asset Collector 的权限。浏览器能访问内网不代表该 APP 已获授权。

失败窗口显示具体阶段、网络退出码或 HTTP 状态。本地已保存资料可重试；超时不代表服务器一定未收到，以资产系统审核记录为准。可提供采集目录中的 `collection-state.json` 排障，不要发送包含凭据的 `config.json`。

macOS 模板由 GitHub Actions 的 Build macOS collector 工作流构建。下载时后端生成带校验和的标准只读 DMG；云端测试使用同一个生成器，通过镜像校验、系统磁盘映像挂载器和 APP 启动入口验证，产物不含真实凭据。模板改动后需要取回通过验证的产物放入后端资源再构建后端。仍需在员工电脑验证钉钉下载后的完整采集与送审；云端测试不代表企业终端安全策略已放行。
