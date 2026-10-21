# 资产管理系统

面向公司内部的资产管理工具，使用 React 19.2.8 + Spring Boot 4.1.0 + MySQL 8.4.11 + Java 21（运行环境 21.0.11）+ TypeScript 7.0.2 + Vite 8.2.1 + Maven 3.9.11，通过 Docker ComposeV2 部署。

![pasted-image.png · 814](https://img.cdn1.vip/i/6aaa56c5b470d_1789548229.webp)

## 核心功能

- 资产新增、编辑、克隆、领用、归还和删除，自动生成资产编号。
- 电脑与显示器绑定、随附配件、图片及备注管理。
- 二维码标签和扫码查看，CSV 模板下载与导入校验。
- 用户管理、操作日志查询与日志 CSV 导出。
- 员工设备登记审核、信息纠错，领用／归还／转交确认，入离职交接。
- 部门盘点、报修维修、耗材出入库与低库存提醒。
- 统一待办、岗位配发模板、设备履历、保修供应商、管理统计与采购补货。

## 项目目录

```text
asset-management/
├── backend/           后端源码与测试
├── frontend/          前端源码
├── deploy/systemd/    定时备份配置
├── scripts/           部署与备份脚本
├── compose.yaml       容器编排
└── .env.example       环境变量模板
```

## 部署与更新

前提：Linux 已安装 Docker Engine、Compose V2 和 curl；项目代码已放到 `/opt/asset-management`。建议至少 2 核 CPU、4 GB 内存。

首次部署前，将 `.env.example` 复制为 `.env`，填写 `MYSQL_ROOT_PASSWORD`、`MYSQL_PASSWORD`、`ASSET_ADMIN_PASSWORD` 三个不同的强密码，并按需设置 `HTTP_BIND_IP`、`HTTP_PORT`（默认 80）。不要覆盖已有 `.env`。

首次部署和后续更新均使用同一脚本：

```bash
cd /opt/asset-management
bash scripts/deploy-linux.sh
```

也可以在项目根目录直接构建并启动所有服务：

```bash
docker compose up -d --build --wait
```

重建容器期间可能短暂中断，需要重新登录。

### 钉钉登录（可选）

在后端容器运行环境设置 `.env.example` 中的六个 `DINGTALK_LOGIN_*` 变量并启用登录；仅构建镜像不会自动带入这些配置。密钥不要写入 Dockerfile 或代码仓库。

让钉钉应用管理员添加与 `DINGTALK_LOGIN_REDIRECT_URI` 完全一致的回调地址：`http://asset.wfq.com/api/v1/auth/dingtalk/callback`。浏览器须能访问该内网地址及 `login.dingtalk.com`，后端须能访问 `api.dingtalk.com` 和 `oapi.dingtalk.com`；正式使用建议 HTTPS。外层反向代理也应关闭该回调路径的访问日志，避免记录临时授权码。

本企业员工首次钉钉登录后自动创建普通账号，中文姓名转小写拼音，重名追加数字；默认只查看本人资产，不开放新增、修改、导入或导出。首次建号会用企业应用凭据核验内部成员身份并读取企业通讯录姓名；应用须有根据 unionId 查询 userid、读取成员详情的权限及相应通讯录可见范围。外部联系人及核验失败者不能自动建号。钉钉应用名称保持不变。

已有本地账号应先用账号密码登录，点击头像 → “绑定我的钉钉”，避免另建账号。不会按姓名自动合并；一个钉钉身份只能关联一个账号。没有设置过权限的老账号保持原有权限。

默认使用钉钉快捷授权登录（头像确认），技术上属于 [OAuth 2.0 网页授权码登录](https://open-dingtalk.github.io/developerpedia/docs/develop/permission/token/browser/get_user_app_token_browser/)。电脑钉钉已登录、官方授权页识别到账号后，会显示头像；本人确认即可返回资产系统，无需再次扫码或输入密码。该流程已在当前使用环境确认可用，但仍需本人授权，不是钉钉客户端内的静默免登。

登录页仅保留账号密码和“钉钉登录”入口；扫码登录由钉钉官方页面提供，系统不再重复提供独立扫码入口和说明。

回调 state 与当前浏览器会话绑定，有效期默认10分钟且只能使用一次。取消授权、成员核验失败、会话过期及服务超时会返回提示。真实钉钉授权与内网部署需要现场联调，自动测试不能代替该验证。

### 用户权限

超级管理员在“用户”页面统一管理本地及钉钉账号，可筛选“钉钉用户”，设置资产操作权限、仅本人 / 指定部门 / 全部资产范围及停用状态。驾驶舱汇总统计对所有登录用户开放，明细和最近更新只展示授权资产；日志、系统设置及用户管理仍仅超级管理员可用。

钉钉登录后展示除“设置”外的所有菜单，“设置”仍仅超级管理员可见。菜单展示不授予权限，未授权页面会提示联系管理员，不读取受限数据；账号密码登录的管理菜单仍按权限控制，待办、自助、交接及采购申请入口对所有登录用户开放。更新登录展示规则后需重新使用钉钉登录。

“仅本人”依赖资产编辑页中的“关联领用账号”。原来的领用人文字保留，不会按同名自动关联；管理员确认后手动关联。资产图片、扫码详情、标签和导出均校验数据范围，扫码查看现在需要登录。批量 CSV 导入只允许有导入、新增及分配领用人权限且范围为“全部资产”的账号。权限修改在下一次请求生效，停用不删除资产。

### 入离职交接

员工进入“员工自助”查看本人资产、设备登记和交接。本人资产按账号 ID 明确关联，不按同名猜测；管理员在“我的资产／我的交接”也只看本人事项。设备登记先进入独立申请，管理员核对实物、SN、照片后明确选择新增正式资产或记录已有资产关联，关联操作不覆盖旧台账。SN不确定时可留空并上传设备／铭牌照片（最多2张，每张上传后不超过2MB）；不做自动OCR识别。可要求补充、驳回或由员工撤回，历史记录保留。

在“入离职交接”新建入职配发或离职交接单，选择明确的员工账号、日期和设备，也可先填写尚未建账号的新员工及手工核对项。流程：管理员逐项验收／配发 → 员工确认，或管理员登记线下凭证 → 完结；取消必须填写原因，历史记录不删除。

- 超级管理员，或拥有“全部资产”范围及“查看资产、分配领用人、归还资产”权限的账号可管理交接。普通员工仅能查看和确认明确关联本人账号的单据；原账号权限不自动调整。
- 离职清单只按账号 ID 自动列出资产。同名文字、电脑绑定的显示器及未建账配件需人工核对；未匹配到资产不等于没有资产。旧资产可在明确核对归属后手工加入。
- 所有交接操作只保存独立记录，不自动分配设备、清空领用人、修改资产状态、删除图片或停用员工。实际台账变更仍使用原资产页面。新员工有账号后，由管理员明确关联，不按姓名自动合并。
- 当前为手工交接模块，钉钉审批编号仅用于记录；智能人事事件订阅与审批自动同步尚未接入。

登记审核需要“全部资产”范围及“查看资产、新增资产、分配领用人”权限。交接权限仍按上述规则独立检查。

### 领用归还、盘点、维修与耗材

| 入口 | 使用流程 |
|---|---|
| 领用归还／员工自助 | 员工申请 → 管理员选设备／核实 → 接收人本人确认 → 管理员明确确认写入台账；归还时清空领用账号，转交时改为接收账号 |
| 信息纠错 | 提交CPU、内存、硬盘或归属部门修改建议 → 审核及本人确认 → 管理员落账；SN、型号等仍在原资产编辑页核对修改 |
| 资产盘点 | 管理员按部门建立快照 → 员工确认在手／缺失／信息不符 → 管理员逐项核实并填写处理说明 → 完结；不会自动改资产、认定丢失或报废 |
| 报修维修 | 员工报修 → 管理员受理、记录进度 → 填写修复／报废结果及费用 → 本人确认 → 管理员写入状态；受理本身不修改台账 |
| 耗材管理 | 新建零库存目录 → 按数量入库／登记领用（填写员工系统账号及说明）→ 查看不可覆盖的流水；库存不足或版本冲突会拒绝操作 |

- 这些模块的管理操作需要“全部资产”范围及“查看资产、编辑资产、分配领用人、归还资产”权限。普通员工只处理本人申请、本人接收事项与盘点项；耗材目录和库存可查看，领用流水仅看本人。菜单展示不等于管理授权。
- 正式资产仅在工单最终确认时更新相关字段，不覆盖价格、图片、配件或其他未申请字段。同一设备只能有一张已受理工单，完结／取消后释放占用；期间资产被编辑会阻止旧工单落账，需取消后重新核对。管理员不能代员工线上签收，关闭的工单与流水不提供删除入口。
- 维修、报废设备在重启、编辑备注、归还及转交时保留其状态；实物未交还时继续保留领用账号。快捷归还也检查资产版本，冲突时须刷新并重新核对领用人；前后端更新后，已打开的旧页面需刷新后再归还。
- 盘点一次最多500项，超限会提示按部门分批，不会静默漏项。未关联账号或员工未确认的设备由管理员线下核实并记录说明；台账变化会显示提醒。
- 耗材以整数数量记账，目录初始库存为0，余额上限十亿；不能直接修改库存。低库存是余额小于或等于预警阈值，库存不为零时不可更换单位。

### 待办、模板、售后与采购

| 入口 | 使用方法 |
|---|---|
| 我的待办 | 汇总本人待确认、待补资料及管理员待处理事项，点“查看处理”进入对应单据；每类显示最近10项，计数包含全部待办 |
| 岗位配发模板 | 设置设备名称和数量，在新建入职交接时追加准备项；不自动分配实物、扣库存或改变已有交接单 |
| 资产详情 → 设备履历 | 按当前权限合并已保存的工单、登记、交接等记录，每类最多100条；不会补造历史，完整审计仍仅超级管理员可见 |
| 保修与供应商 | 独立登记购买日期、保修截止及售后联系人，筛选30天内到期／已过保；供应商停用保留历史快照，不修改原资产价格或归属 |
| 管理统计 | 查看当前资产状态、部门占用、空闲设备、盘点差异、低库存、已完结维修费用及采购进度；不是财务或折旧报表 |
| 采购补货 | 员工申请 → 管理员内部审核 → 登记外部下单信息 → 明确确认整单验收入库；不自动向供应商下单、付款或取消外部订单 |

模板、售后、管理统计及采购管理复用“全部资产”范围和“查看、编辑、分配领用人、归还资产”权限。普通员工只能查看本人采购申请；菜单可见不授予管理权限，不自动提升已有账号权限。

设备采购验收只关联申请后新建、未领用且可分配的资产：先在资产页面新增本次到货设备，再刷新采购页面逐台关联，不会覆盖旧资产。耗材验收按采购单位增加一次库存并保留流水，**不要再手动重复入库**。当前只支持整单验收，分批到货请拆单；版本冲突须刷新核对后重试。取消、驳回保留记录，不删除历史。

### 钉钉业务提醒（可选，一次配置）

默认关闭，不影响钉钉登录。需要群提醒时，在服务器原 `.env` 中填写 `ASSET_WORKFLOW_NOTIFY_ENABLED=true`、`ASSET_WORKFLOW_DINGTALK_WEBHOOK`、`ASSET_WORKFLOW_DINGTALK_SECRET`，使用启用加签的群机器人，再按现有镜像更新流程重建后端；不要覆盖 `.env` 或把密钥提交仓库。这与 WUD 的镜像更新通知是两套配置。

启用后申请及采购进度、盘点进度、低库存自动排队；每日北京时间9点汇总未完成及逾期交接，9:15汇总未来30天保修到期设备，不需要每天手工执行。每轮最多发送20条，轮询间隔1分钟；失败最多尝试5次后在“员工自助 → 通知状态”由超级管理员重新排队。配置显示正常只代表格式检查通过，是否送达以发送状态及群消息为准。超时重试可能重复提醒，但不会重复操作资产。机器人群应限制成员，提醒不包含SN、价格或照片。

首次上线先备份数据库和资产图片、同时更新前后端。本次模板、保修供应商及采购功能新增8张独立表，不修改旧表字段，不做旧资产回填、清理或账号合并。登记原始照片保存在数据库，审核新建资产后的图片同时使用原图片卷；回滚镜像时保留所有表及原数据卷。本地 H2 测试不能替代生产 MySQL 和真实钉钉联调。钉钉入离职审批自动接入仍暂缓，等待HR确认入口与流程模板。

### 数据保留

| 内容 | 保存位置 |
|---|---|
| 资产、用户、头像、日志、交接、登记、工单、盘点、耗材、模板、保修、供应商、采购、通知和设置 | `asset-management_mysql_data` 数据卷 |
| 资产图片文件 | `asset-management_asset_images` 数据卷 |

- 保持 Compose 项目名和数据卷配置不变，正常重复部署会复用现有数据。
- 不要执行 `docker compose down -v` 或删除上述数据卷。
- 初始化密码仅用于首次创建账号；修改 `.env` 不会重置已有管理员或数据库密码。
- 新代码可能更新数据库结构，不能仅靠换回旧代码恢复数据库。

## Jenkins 构建与 WUD 自动更新（可选）

流程：推送 `main` → Jenkins 轮询并构建 → 推送 SWR 镜像 → WUD 每分钟检查 → 更新已授权的前后端 → 写回资产系统 `compose.yaml` 的镜像标签。Jenkins 不需要连接业务服务器；只推送代码或镜像，不代表部署已经成功。

原来的源码手动部署方式保留。资产系统只使用 `compose.yaml`，旧 `compose.jenkins.yaml` 可以保留，但不再叠加使用。WUD 用独立项目 `wud` 部署，不与资产系统共用启停命令。

### Jenkins 构建（一次性配置）

- 前后端各一个任务，仓库均为 `https://github.com/yyjlab/asset-management.git`，分支 `*/main`；需要时配置 GitHub 拉取凭据。两个任务勾选“轮询 SCM”，填 `* * * * *`，每分钟检查新提交；这种方式不需要 GitHub Webhook。设置了路径过滤时，仅修改 README 或 YAML 可能不触发构建。
- 设置参数 `COMPONENT`（`backend` 或 `frontend`）、`REGISTRY_HOST`（`swr.cn-south-1.myhuaweicloud.com`）、`IMAGE_REPOSITORY`。后端仓库为 `swr.cn-south-1.myhuaweicloud.com/recovery-uat/be-asset`，前端为同路径下的 `fe-asset`，均不含标签。
- 用 Jenkins Credentials Binding 注入 `REGISTRY_USER`、`REGISTRY_PASSWORD`。构建节点须能执行 Docker，并能访问 GitHub、依赖源和 SWR；不要把密码直接写进 Shell。

两个任务均可使用以下 Execute shell。后端先运行测试；前端 Dockerfile 已包含类型检查和生产构建：

```sh
#!/bin/sh
set +x
set -eu
cd "$WORKSPACE"
: "${REGISTRY_HOST:?请设置镜像仓库域名}"
: "${IMAGE_REPOSITORY:?请设置完整镜像仓库路径}"
: "${REGISTRY_USER:?请绑定镜像仓库凭据}"
: "${REGISTRY_PASSWORD:?请绑定镜像仓库凭据}"

case "$COMPONENT" in
  backend)
    docker run --rm -v "$WORKSPACE/backend:/workspace" -w /workspace \
      maven:3.9.11-eclipse-temurin-21-alpine mvn -B -ntp test
    ;;
  frontend) ;;
  *) echo 'COMPONENT 只能填写 backend 或 frontend' >&2; exit 1 ;;
esac

TAG="test${BUILD_NUMBER}"
IMAGE="$IMAGE_REPOSITORY:$TAG"
docker build -f "$COMPONENT/Dockerfile" -t "$IMAGE" "$COMPONENT"
printf '%s' "$REGISTRY_PASSWORD" | docker login "$REGISTRY_HOST" --username "$REGISTRY_USER" --password-stdin
docker push "$IMAGE"
printf '已推送镜像：%s\n' "$IMAGE"
```

标签必须是递增的 `test数字`，例如 `test9` → `test10`，前后端编号独立。不要重置为较小编号，也不要反复覆盖同一标签：当前不检查同标签的摘要变化，也不接受 `latest`、`prod` 或“构建号-gitSHA”标签。后端 JUnit 报告路径为 `backend/target/surefire-reports/*.xml`。

### 独立 WUD 与资产接入（一次性配置）

以下在业务服务器操作，不是在 Jenkins 构建服务器操作。本例接管已存在的 WUD 与 `wud_data` 卷，复用原管理员账号；不是全新空卷安装。只能运行一套使用该卷的 WUD，先核对并停止旧实例，再接管为独立项目 `wud`、容器名 `wud`，不要删除数据卷。

- 资产配置放在 `/opt/asset-management/compose.yaml`，保留同目录原来的 `.env`，包括数据库、端口及钉钉变量。上传前备份原配置、数据库和图片，并保持两条 `image` 与当前运行版本一致，避免接入时回退镜像。
- WUD 配置为 `/opt/asset-management/compose.wud.yaml`，在同目录原 `.env` 中保留已有内容并填写下面三项，不移动文件。只读校验命令为：在 `/opt/asset-management` 执行 `docker compose -p wud -f compose.wud.yaml config --quiet`。WUD 与资产配置同目录不影响项目独立，始终用 `-p wud -f compose.wud.yaml` 单独管理；下面的接入命令要求独立 `wud` 已正常运行。

```dotenv
WUD_BIND_IP=服务器内网IP
WUD_SWR_USER=具有目标仓库读取权限的SWR用户名
WUD_SWR_PASSWORD='对应的有效SWR密码'
```

WUD 的 SWR 凭据独立于宿主机 `docker login`，临时凭据过期后需要更新并重建 WUD。只允许可信管理员访问 WUD；Docker socket 具有宿主机管理权限，挂载的业务配置目录也可写。不要把 `.env` 提交到仓库。

WUD 使用 `wud-swr-fixed:9.4.1-1` 兼容镜像：基于原版 9.4.1，只将 SWR 根接口的错误认证范围改为实际镜像的 `pull` 范围。账号、权限、管理员数据和其他仓库逻辑不变。依据：[上游认证代码](https://github.com/getwud/wud/blob/9.4.1/app/registries/DockerRegistryV2.ts#L437)。补丁回归测试在镜像构建时执行；真实镜像构建和自动更新仍须在服务器验证。

首次或更新补丁时，把 `Dockerfile.wud` 上传到 `/opt/asset-management/`，把 `scripts/patch-wud-swr-auth.cjs`、`scripts/patch-wud-swr-auth.test.cjs` 上传到 `/opt/asset-management/scripts/`。保留服务器原 `.env` 和已有 Compose 配置，只按仓库 `compose.wud.yaml` 示例将 `wud.image` 改为 `wud-swr-fixed:9.4.1-1` 并增加 `wud.build`，不要覆盖其他项目的挂载或设置。执行下面命令构建并仅重建 WUD，复用 `wud_data`，不直接启停业务容器；恢复后可能立即自动更新已授权服务，无需重新构建资产前后端。构建失败时不要继续替换。

```bash
cd /opt/asset-management &&
docker compose -p wud -f compose.wud.yaml config --quiet &&
docker compose -p wud -f compose.wud.yaml build wud &&
docker compose -p wud -f compose.wud.yaml up -d --no-deps --no-build --pull never --wait --wait-timeout 180 wud
```

当前配置已挂载资产目录，并只给 `backend`、`web` 授权 `dockercompose.shared` 触发器；MySQL 不参与自动更新，并通过 `WUD_WATCHER_LOCAL_EXCLUDE: '^asset-management-mysql-1$$'` 排除其镜像版本监控，避免无关的 Docker Hub 查询报错。这不停止数据库，也不改变其他项目的监控。只调整该监控配置时，校验后单独重建 WUD 即可，无需重新构建镜像或启停业务服务。首次接入按以下顺序一次性应用：先暂停 WUD，更新前后端的接入标签，等待就绪，再启动 WUD。命令会核对当前镜像，不一致时停止；先同步配置中的版本，不要跳过检查。更新会短暂中断前后端，启用后可能立即部署 SWR 中已有的最大编号 `testN`。

```bash
test "$(docker ps --filter volume=wud_data --format '{{.Names}}')" = wud &&
test "$(docker inspect wud --format '{{index .Config.Labels "com.docker.compose.project"}}')" = wud &&
cd /opt/asset-management &&
docker compose -p asset-management -f compose.yaml config --quiet &&
docker compose -p asset-management -f compose.yaml config --images | grep -Fx -- "$(docker inspect asset-management-backend-1 --format '{{.Config.Image}}')" &&
docker compose -p asset-management -f compose.yaml config --images | grep -Fx -- "$(docker inspect asset-management-web-1 --format '{{.Config.Image}}')" &&
cd /opt/asset-management &&
docker compose -p wud -f compose.wud.yaml config --quiet &&
docker compose -p wud -f compose.wud.yaml stop wud &&
cd /opt/asset-management &&
docker compose -p asset-management -f compose.yaml up -d --no-deps --no-build --pull never --wait --wait-timeout 180 backend web &&
cd /opt/asset-management &&
docker compose -p wud -f compose.wud.yaml up -d --no-build --pull never --wait --wait-timeout 180 wud
```

这两份 YAML 不会随镜像自动复制到服务器；修改挂载、标签、环境变量等配置后，仍需先在对应服务器应用。接入其他 Compose 项目时，在 WUD 中逐个挂载其配置目录，在目标服务添加 `wud.trigger.dockercompose.shared.enabled: "true"`，并按其标签格式配置筛选规则。多文件项目用 `wud.compose.file` 指定保存完整镜像地址的文件；不把业务服务合进 WUD 的 Compose。

### 日常自动运行与验证

一次性接入完成后，日常只需推送代码，不需要再手动改镜像标签、拉取镜像或执行部署命令。前提是 Jenkins 检出该提交、成功推送更大编号的标签，且 WUD 的 SWR 凭据有效、`WUD_AUTO_UPDATE` 没有设为 `false`。Jenkins 轮询、构建、WUD 检查、镜像下载和启动都需要时间，不是推送后立即完成。

WUD 会把新标签写回服务器的 `/opt/asset-management/compose.yaml`；不要用仓库的旧副本覆盖该运行文件。前后端独立发布，须保持新旧版本兼容。WUD 已配置健康检查失败回滚，但不能回滚数据库；探针通过也不等于业务功能全部通过。

以下为可选的只读验证：授权应为 `true`，配置挂载应可写；检查运行镜像是否变为新标签、Compose 是否同步，再访问系统检查登录、资产列表和图片。仅修改文档或部署配置时，网页外观可能不变。

```bash
docker inspect asset-management-backend-1 asset-management-web-1 --format '{{.Name}} 镜像={{.Config.Image}} 创建时间={{.Created}} 更新授权={{index .Config.Labels "wud.trigger.dockercompose.shared.enabled"}}'
docker inspect wud --format '{{range .Mounts}}{{if eq .Destination "/opt/asset-management"}}配置挂载={{.Source}} 可写={{.RW}}{{end}}{{end}}'
docker compose --env-file /opt/asset-management/.env -p asset-management -f /opt/asset-management/compose.yaml config --images
docker logs --since 10m wud
```

未触发时，依次查看 Jenkins 的 SCM 轮询日志和构建日志、SWR 是否已推送新标签、WUD 日志中的鉴权和配置文件错误。参考：[Jenkins 轮询语法](https://www.jenkins.io/doc/book/pipeline/syntax/#triggers)、[WUD 触发器配置](https://getwud.app/docs/configuration/triggers/)。本地静态检查不等于实际 Jenkins/WUD 链路已验证。

### 钉钉群更新通知（可选，一次性配置）

上传 `scripts/wud-dingtalk-notify.cjs` 到服务器同名路径 `/opt/asset-management/scripts/`，按本仓库示例把 `compose.wud.yaml` 中的通知钩子、`COMMAND_DINGTALK` 和 `WUD_DINGTALK` 环境变量合入服务器原 WUD 配置。保留原镜像、挂载和其他项目设置，不覆盖资产 `compose.yaml`，无需构建任何镜像。

使用钉钉群机器人的 Webhook，安全设置选“加签”；凭据不是资产系统钉钉登录的 Client Secret。在服务器原 `.env` 中追加下面三项，替换占位内容，不上传密钥：

```dotenv
WUD_DINGTALK_ENABLED=true
WUD_DINGTALK_WEBHOOK='https://oapi.dingtalk.com/robot/send?access_token=替换为机器人token'
WUD_DINGTALK_SECRET='替换为机器人的SEC加签密钥'
```

只重建 WUD 容器以加载配置，复用原卷；不直接启停业务服务，恢复后仍可能自动更新已授权项目：

```bash
cd /opt/asset-management &&
docker compose -p wud -f compose.wud.yaml config --quiet &&
docker compose -p wud -f compose.wud.yaml up -d --no-deps --no-build --pull never --wait --wait-timeout 180 wud
```

可选验证：以下命令只发一条测试消息，不访问 Docker API、不修改配置、不更新容器。不要使用 WUD 设置页的 `Test Trigger` 测试生产更新，它可能把示例版本真实写进 Compose。

```bash
docker exec wud node /opt/asset-management/scripts/wud-dingtalk-notify.cjs --test
```

启用后无需人工发送：只在已授权更新的完成、异常、回滚/中止钩子上通知，不重复发送“发现新版本”。完成通知以 WUD 更新流程结束为准，健康门禁依赖服务自身的探针及回滚配置；回滚通知不等于回滚成功。仓库查询、服务匹配等未进入更新流程的问题仍须查 WUD 日志，不由这些钩子通知。

钉钉拒绝、网络错误或超时只记日志，不改变业务更新结果；10 秒超时，不自动重发。关闭通知时钩子不发送，显式 `--test` 仍可验证渠道；凭据缺失时测试失败。收不到时查看 `docker logs --since 10m wud` 中的 `[wud-dingtalk]`。真实群投递和服务器钩子仍须现场验证。

### 手动镜像更新（备用）

原来的源码部署和手动镜像更新仍可使用，但不要与 WUD 并发更新同一服务。先在业务服务器暂停 WUD；这会暂停所有接入项目的自动更新，业务容器仍运行：

```bash
cd /opt/asset-management &&
docker compose -p wud -f compose.wud.yaml stop wud
```

备份后，修改资产 `compose.yaml` 中需要发布的 `image`，保留另一个服务版本。手动拉取私有镜像前，服务器须已登录 SWR 且有读取权限。然后在业务服务器执行，不重新编译源码：

```bash
cd /opt/asset-management &&
docker compose -p asset-management -f compose.yaml config --quiet &&
docker compose -p asset-management -f compose.yaml pull backend web &&
docker compose -p asset-management -f compose.yaml up -d --no-deps --no-build --pull never --wait --wait-timeout 180 backend web
```

`--no-deps` 不重建 MySQL，项目名及数据库、图片卷保持不变。不要执行 `down -v` 或 `--remove-orphans`。手动命令失败不会自动回滚；若退回旧版本，恢复 WUD 前须先排除故障标签，否则可能再次升级。恢复自动运行时，在 `/opt/asset-management` 执行 `docker compose -p wud -f compose.wud.yaml up -d --no-build --pull never --wait --wait-timeout 180 wud`。

## 备份与恢复

### 手动备份

在项目根目录执行，MySQL 和后端容器需保持运行。备份期间暂停业务写入，保证数据库与图片一致。

```bash
bash scripts/backup-mysql.sh
```

默认保存在 `backups/monthly/`，保留最近 12 份。每份包含同一时间戳的 `.sql.gz`、`.images.tar.gz` 和 `.sql.gz.sha256`；必须成套保存，并定期复制到其他设备。不要将 `.env` 或备份提交到代码仓库。

### 定时备份（可选）

在 Linux 项目根目录安装：

```bash
sudo bash scripts/install-monthly-backup-timer.sh
```

按服务器时区每月 1 日 02:30 触发，随机延迟最多 10 分钟；关机错过后补跑。安装脚本会将 `deploy/systemd/` 下的配置安装并启用。

### 恢复数据

**仅在确认需要恢复时执行：会覆盖数据库中的同名表和备份对应的图片文件。** 先停止业务写入并备份当前数据，确认目标环境及备份日期；使用与备份兼容的代码版本。下列命令在任一步失败时停止，不会自动恢复服务。

将 `BACKUP_NAME` 改为要恢复的备份文件名，bash restore-backup.sh：

```bash
(
  set -euo pipefail
  cd /opt/asset-management
  BACKUP_NAME='asset_management_YYYYMMDD_HHMMSS'
  BACKUP_DIR="$PWD/backups/monthly"

  (cd "$BACKUP_DIR" && sha256sum -c "$BACKUP_NAME.sql.gz.sha256")
  gzip -t "$BACKUP_DIR/$BACKUP_NAME.sql.gz"
  tar -tzf "$BACKUP_DIR/$BACKUP_NAME.images.tar.gz" >/dev/null

  docker compose stop web backend
  gzip -dc "$BACKUP_DIR/$BACKUP_NAME.sql.gz" | docker compose exec -T mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"'
  docker compose run --rm --no-deps -T --user root --entrypoint sh backend -c 'tar -xzf - -C /app/data && chown -R asset:asset /app/data/asset-images' < "$BACKUP_DIR/$BACKUP_NAME.images.tar.gz"
  docker compose up -d --wait
  docker compose ps
)
```
