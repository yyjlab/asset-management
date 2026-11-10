# AD 装机平台资产登记接口 v1

仅由 AD 后端调用，不给 PE 或普通员工分发服务凭据。默认流程为 PE 自动采集、自动送审，在 asset 核对批准后才生成编号和创建资产；不等待装系统、改名或加域。默认关闭；本机隔离 HTTP 联调不代表已连接生产环境。

## 一次性接入准备

1. 批准时需要唯一且启用的 `公司=微丰启`、`位置=公司`、`状态=当前可用`（仅缺失时兼容旧名“可领用”）及真实电脑分类。缺少资料不阻止先送审，不自动新建字典。
2. 旧库先备份并在测试库审核 `scripts/migrate-ad-deployment-descriptive-sn.sql`。该脚本仅移除 SN 单列唯一索引，不删改资产数据、资产编号/二维码索引。首次应用新实体映射不保证 Hibernate 会删除旧索引；禁止靠重启代替迁移。脚本尚未在真实 MySQL 执行验证。

接口登记表若出现 `Data too long for column 'request_json'`，备份后审核并执行 `scripts/migrate-ad-deployment-journal-text.sql`，只修正接口登记表的两个文本字段，不修改资产表。登记请求留档只保存操作人与采集时间，不重复保存硬件快照；完整内容指纹、首次响应及审计仍保留，旧记录可继续读取。该迁移尚未在真实 MySQL 验证。
3. 超级管理员在「设置 → 装机平台接入 → 接入设置」启用，保存后立即生效，无需修改 `.env` 或重启。默认仅 HTTPS；受信内网可选择「受信内网 HTTP / HTTPS」，阅读并勾选明文风险确认后保存。HTTP 会明文传输凭据和资产资料，此选项不自动判断内网或限制来源，需由网络配置限制外部访问。关闭后返回 `503 INTEGRATION_DISABLED`。
4. 在本页创建专用凭据，在 AD「电脑装机 → 资产登记 → asset 联动设置」填写 asset 地址和凭据，测试连接并开启自动上报。AD 无需分类或资产编号。凭据只交给 AD 后端，不交给 PE。HTTPS 仍验证证书；本次不修改生产代理配置。

## 自动送审与审核

日常打开 asset 左侧「装机电脑审核」，刷新不会跳回设置，也可收藏 `/?view=deployment-review`。接入开关和凭据仍在「设置 → 装机平台接入」。核对配置、补充型号、选择分类后点“批准并创建资产”，或填写原因“不批准”。批准使用现有发号器，按上海日期加4位流水生成12位编号，不提前占号，不自动领用，不覆盖已有资产。未采集信息留空，基础显示驱动不作真实显卡。

AD 服务凭据调用：
- `POST /api/integrations/ad-deployment/v1/intakes`：请求与下方旧接口的 `externalRegistrationId`、`capturedAt`、`operator`、`snapshot` 相同，但禁止 `assetTag`；`name` 为可选的真实可读名称，最多160字，例如 `LENOVO ThinkPad L13`，不替换 `snapshot.model` 中的固件型号代码。`categoryId` 可省略，`snapshot.model` 可为空，待审核时补齐；其余字段类型和长度沿用旧约定，上限32KiB。旧请求未带名称时仍兼容，不自动改写已保存的请求或已有资产名称。
- `GET /api/integrations/ad-deployment/v1/intakes/{externalRegistrationId}`：专用凭据只能按已知标识查询处理状态，不返回整份硬件配置或待审列表。按管理员明确授权，同一 AD 接入的新有效专用凭据可继续查询或重试旧记录；不改变最初调用身份，已撤销凭据失效，仍无审批权限。

收到首次送审或重复待审请求返回202：

```json
{"externalRegistrationId":"ad:instance:device:1","result":"pending_review","asset":null,"message":null}
```

批准后查询或重放返回200：

```json
{"externalRegistrationId":"ad:instance:device:1","result":"approved","asset":{"id":123,"assetTag":"202611100001","version":0},"message":"核对完成"}
```

拒绝后返回200，`result=rejected`、`asset=null`，`message` 为拒绝原因。相同标识带不同内容返回409 `IDEMPOTENCY_KEY_REUSED`，不替换原待审单。认证失败401，权限或HTTP限制403，字段失败422。AD 固定请求持久化重试，收到待审后定期只读查询；重复心跳、重进 PE 不新建待审单，终态不自动重开。

超级管理员会话接口均位于 `/api/settings/ad-deployment`，写入需要现有 CSRF：
- `GET /intakes?page=0&state=pending_review`：每页20条，状态另可选 `approved`、`rejected`。
- `GET /intake-options`：返回启用的电脑分类。
- `POST /intakes/{id}/approve`：`revision`、`categoryId`、`name`（1到160字）、`model`（1到120字）必填；`cpu`、`memory`、`storage`、`graphicsCard` 各最多200字，`manufacturerSerialNumber` 最多160字，`note` 最多500字，可为空。返回审核记录及最终编号。
- `POST /intakes/{id}/reject`：`revision` 和1到500字 `note` 必填，不创建资产。

审批使用记录锁与现有发号锁，重复提交相同审批结果返回原资产；修改已处理的审核内容返回409，不重复建资产。新表 `ad_deployment_intakes` 存放待审及审核结果，不修改原资产字段；生产创建由既有数据库结构管理方式处理。本次未执行生产迁移。

下文 `/registrations` 是旧版“已确认编号后登记”的兼容接口。历史记录和队列不自动转换为新待审单，避免重复入库；新自动采集使用 `/intakes`。

## 网页管理入口

超级管理员进入「设置 → 装机平台接入」，可管理接入开关、传输方式、服务凭据和登记记录。接口已启用不代表 AD 已连通；尚无登记记录时页面明确提示未验证发送端。接入设置保存在现有 `system_settings` 表，随数据库保留；已有环境变量仅在从未通过网页保存时作为初始值，网页保存后优先使用数据库值。

凭据可设置名称和1到366天有效期、创建或确认撤销；密钥仅在创建弹窗显示一次，不写入浏览器持久存储，关闭后不能取回。登记记录每页20条，显示声明操作人、服务凭据及配置差异，不提供覆盖更新。只列已持久化结果，认证或校验失败请按接口响应排查。

新增只读管理接口（仅超级管理员会话；专用服务凭据不可访问）：
- `GET /api/settings/ad-deployment/status`：返回 `enabled`、`secureRequest`、`allowHttp`、`endpoint`、`lastReceivedAt`。
- `PUT /api/settings/ad-deployment/settings`：管理员会话及 CSRF 保护，请求含布尔值 `enabled`、`allowHttp`、`acknowledgeHttpRisk`；启用 HTTP 必须同时确认风险，否则返回 `400 HTTP_RISK_CONFIRMATION_REQUIRED`。变更写入审计，停用不删除资产或凭据。
- `GET /api/settings/ad-deployment/registrations?page=0`：返回 `items`、`page`、`totalPages`、`totalElements`；页码0到100000。记录含 `externalRegistrationId`、`assetTag`、`assetId`、`receivedAt`、`credentialId`、`declaredOperator`、`result`、`differences`、`warnings`，不返回完整硬件快照或密钥。

## 专用凭据

由已登录的 `SUPER_ADMIN` 使用现有管理员会话和 CSRF 防护管理，普通用户无权限。没有新增员工账号或自动领用账号。

| 方法 / 路径 | 用途 |
| --- | --- |
| `POST /api/settings/ad-deployment/credentials` | 签发，仅这次返回明文 token |
| `GET /api/settings/ad-deployment/credentials` | 列出名称、ID、有效期和撤销状态，不返回 token/hash |
| `DELETE /api/settings/ad-deployment/credentials/{id}` | 立即撤销后续请求；已取得事务锁的在途请求可先完成 |

签发请求（`name` 为1～80字符，`expiresAt` 必须在未来且不超过366天）：

```json
{"name":"AD 测试装机服务","expiresAt":"2027-01-01T00:00:00Z"}
```

响应示意，尖括号为占位符，不是可用凭据：

```json
{"credential":{"id":"<credential-id>","name":"AD 测试装机服务","createdAt":"2026-11-09T07:00:00Z","expiresAt":"2027-01-01T00:00:00Z","revokedAt":null,"active":true},"token":"<仅签发时显示的服务token>"}
```

AD 使用受保护的服务配置/密钥存储保存 token。服务端仅存256位随机凭据的 SHA-256 摘要。可签发新凭据、切换 AD 配置、再撤销旧凭据，幂等记录不因轮换丢失。不得在 URL、日志、截图或异常回显中携带 token，不开启 Authorization/请求体调试日志。

## 连接检查

`GET /api/integrations/ad-deployment/v1/connection` 使用同一专用 Bearer 凭据，执行启用、HTTP/HTTPS 和凭据校验，返回 `{"receiver":"asset-management","protocolVersion":1,"categories":[{"id":12,"name":"笔记本电脑"}]}`。分类来自真实启用记录；默认字典和分类不足不阻止连接或送审，在批准时校验。它不创建资产、不保存登记记录、不返回资产或人员列表。AD 通过管理员及 CSRF 保护的 `POST /pc-deploy/api/asset-sync/probe` 代理调用，PE 不直接访问。

## 旧版兼容登记请求

`POST /api/integrations/ad-deployment/v1/registrations`

请求头：`Authorization: Bearer <token>`、`Content-Type: application/json`。无须员工会话或 CSRF token，也不能用管理员会话替代专用凭据。该 token 仅可提交登记或送审、查询本人送审结果及检查连接，不允许审批、读取完整资产、编辑/删除资产、修改字典、管理用户或签发凭据。整个 UTF-8 JSON 请求最多32KiB。

```json
{
  "externalRegistrationId": "ad-pe:5c62bc41-a36b-4bf6-9a27-254d936c18cf:v1",
  "assetTag": "001234567890",
  "categoryId": 12,
  "capturedAt": "2026-11-09T06:30:00Z",
  "operator": {"id": "wfq101755", "displayName": "装机管理员"},
  "snapshot": {
    "model": "ThinkPad T14 Gen 5",
    "cpu": "Intel Core Ultra 7 155U",
    "memory": "32 GB",
    "internalDisks": [
      {"model": "Internal SSD", "capacityBytes": 549755813888, "busType": "NVMe"}
    ],
    "graphicsCards": ["Intel Graphics"],
    "manufacturerSerialNumber": "SN-FROM-PE"
  }
}
```

示例 `categoryId=12` 不是固定字典 ID，发送端必须先由管理员配置实际 ID；不能照抄。可选顶层 `name` 为资产名称，缺省沿用实际采集的 `snapshot.model`。

| 字段 | 必填 / 限制 |
| --- | --- |
| `externalRegistrationId` | 必填，1～128字符；以字母或数字开头，仅 ASCII 字母、数字、`.`、`_`、`:`、`-`；不是任务安装ID，建议固定登记UUID＋显式版本 |
| `assetTag` | 必填，严格12位 ASCII 数字**字符串**；不接受 JSON 数字、空格、全角数字，保留前导零 |
| `categoryId` | 必填，正整数；创建时须为启用且 `COMPUTER` 类型的真实分类 |
| `name` | 可选，最多160字符；缺省使用实际型号，不使用虚构型号 |
| `capturedAt` | 必填，带时区的 ISO-8601 时间；不能超过服务端未来24小时 |
| `operator.id` / `operator.displayName` | 必填，各1～120字符；仅作为 **AD 服务声明的操作人**，不冒充 asset 登录身份，不成为领用人 |
| `snapshot.model` | 必填，1～120字符；未知、固件占位型号不能用来创建资产，应要求管理员核实 |
| `snapshot.cpu` / `snapshot.memory` | 可选字符串，各最多200字符；未采集到传 `null` 或省略，不猜测DDR代际等信息 |
| `snapshot.internalDisks` | 必填数组，0～32项；仅内置物理盘，不能发分区、卷或 U 盘；未识别到传 `[]` |
| `internalDisks[].model` | 可选字符串，最多120字符 |
| `internalDisks[].capacityBytes` | 必填，正整数，实际物理盘字节数，不发字符串或 GiB 小数 |
| `internalDisks[].busType` | 必填，`NVMe` / `SATA` / `ATA` / `SAS` / `SCSI` / `RAID` / `MMC`；不能将 USB/未知总线标成内置盘 |
| `snapshot.graphicsCards` | 必填数组，0～8项，每项1～200字符；未识别到传 `[]` |
| `snapshot.manufacturerSerialNumber` | 可选字符串，最多160字符；写入新资产 SN 字段和登记快照，不做身份匹配/合并 |

字符串去除首尾空格，禁止控制字符；缺失的可选字符串、`null`、空白字符串等价。严格拒绝未知字段和重复 JSON 属性。不能发送公司、位置、价格、订单、领用人、附件、状态或入域状态等覆盖字段。

内存保留发送端的实际文本。物理盘按型号/容量/总线生成稳定排序文本，容量由字节数换算 GiB（保留最多2位小数）；显卡名称排序汇总。汇总超过现有 `storage` / `graphicsCard` 200字符限制返回422，不静默截断；完整结构同时存入外部登记快照。基础显示/呈现驱动（如 `Microsoft Basic Display Adapter`、`Microsoft 基本显示适配器`、Standard VGA）忽略并返回 `BASIC_DISPLAY_DRIVER_IGNORED`，不能当作真实显卡；没有真实显卡时资产字段留空。

## 按资产编号处理

- 不存在：使用现有 `AssetService` 创建；公司、位置、状态引用上述实际字典；领用人、价格、订单、附件和入域字段留空，不自动领出。未知硬件字段留空。允许不同资产编号带相同 SN。
- 已存在：只比较型号、CPU、内存、硬盘汇总、真实显卡及电脑类型，不更改任何资产字段。SN 不参与一致性判定，也不因它相同去查找其他资产。
- 比较只忽略首尾空格和大小写，不宣称不同硬件描述一定等价。例如 `16GB DDR4` 与 `16 GB` 会返回差异，交给管理员核对；不会猜测后覆盖。
- 已有值与本次未采集值不一致时也返回差异，不将缺失信息冒充“一致”。已有资产非电脑类型时返回类型差异。

### 新增：HTTP 201

```json
{
  "externalRegistrationId":"ad-pe:5c62bc41-a36b-4bf6-9a27-254d936c18cf:v1",
  "source":"AD 装机平台","result":"created",
  "asset":{"id":123,"assetTag":"001234567890","version":0},
  "differences":[],"warnings":[],"replayed":false,"observedAt":"2026-11-09T07:00:00Z"
}
```

### 已存在且一致：HTTP 200

结构同上，`result` 为 `already_exists`，返回已有资产ID、编号和所比较版本；`differences=[]`。这是对硬件配置的比较，不表示领用/维修/报废状态可以变为可用。

### 配置冲突：HTTP 409

```json
{
  "externalRegistrationId":"ad-pe:5c62bc41-a36b-4bf6-9a27-254d936c18cf:v2",
  "source":"AD 装机平台","result":"configuration_conflict",
  "asset":{"id":123,"assetTag":"001234567890","version":8},
  "differences":[{"field":"memory","existing":"16 GB","incoming":"32 GB"}],
  "warnings":[],"replayed":false,"observedAt":"2026-11-09T07:05:00Z"
}
```

差异字段为 `model`、`cpu`、`memory`、`storage`、`graphicsCard` 或 `assetProfile`；前后值可能为 `null`。不提供确认覆盖接口，AD 第一版应显示“等待管理员核对”，不能把409当作登记成功，也不能自动改资产编号避开冲突。

### 其他错误

统一错误结构示意：

```json
{"status":422,"code":"VALIDATION_FAILED","message":"assetTag: 必须是保留前导零的12位数字字符串","fields":["assetTag"]}
```

| HTTP / code | 发送端处理 |
| --- | --- |
| `401 AUTHENTICATION_FAILED` | 缺少/错误/过期/撤销凭据，停止并提示管理员，不反复重试 |
| `403 HTTPS_REQUIRED` / `FORBIDDEN` | HTTPS/代理链或调用范围不正确；仅管理员可在网页明确批准受信内网 HTTP，发送端不得自动降级 |
| `400 MALFORMED_JSON` | JSON 错误或重复属性，修复请求 |
| `413 PAYLOAD_TOO_LARGE` / `415 UNSUPPORTED_MEDIA_TYPE` | 缩减请求 / 使用 JSON |
| `422 VALIDATION_FAILED` / `CATEGORY_INVALID` | 修复必填、长度、类型、硬件或分类信息 |
| `409 DEFAULT_DICTIONARY_INVALID` | 管理员修复缺失、不唯一、已停用的默认字典，未自动创建 |
| `409 IDEMPOTENCY_KEY_REUSED` | 同标识不同内容；停止，不覆盖原结果；核实后使用新的明确版本 |
| `409 ASSET_RULE_REJECTED` | 现有资产业务规则拒绝，核对提示；少数旧规则错误可能仍返回400 |
| `409 CONCURRENT_RETRY` | 有限退避，保持原标识、内容重试；不生成新编号 |
| `410 ASSET_NO_LONGER_EXISTS` | 原登记资产被删除/替换；禁止由旧请求重新创建，人工处理 |
| `503 INTEGRATION_DISABLED` / `REGISTRATION_NOT_READY` | 接口未启用/初始化未完成；核对测试环境准备情况 |
| `503 SN_SCHEMA_MIGRATION_REQUIRED` | 旧 SN 唯一索引未迁移，未创建资产；由管理员审核迁移 |
| `500` 或网络超时 | 保留同一登记标识和内容后有限重试，不把超时认定为未入库 |

## 幂等和审计

- 每次登记先在 AD 持久化外部登记标识和固定快照，再发送。超时重试不能重新采集、变更操作人/采集时间或另造标识；真正的新采集必须明确新版本。
- 同标识、规范化后相同内容返回首次已提交结果，`replayed=true`，保留原 HTTP 状态（包括201、200、配置差异409）和 `observedAt`。返回的是当时比较的快照，不是假装实时查询。
- 同标识不同资产编号、配置、操作人或采集时间返回 `IDEMPOTENCY_KEY_REUSED`。JSON 对象属性顺序、外部空白不影响；数组顺序和字符串内部空格仍属于内容。
- 登记幂等键全局属于本 AD 接入，不绑定某个凭据，不因凭据轮换失效；无自动过期清理。已删除/替换的原资产返回410，不重新创建。
- 字段校验失败、字典错误、未启用、鉴权失败及回滚事务不占用幂等键；修复后可提交。正常资产创建、登记记录和审计同事务提交；使用现有数据库分配锁，另以资产编号唯一约束处理普通创建的并发竞态。
- 记录固定来源 `AD 装机平台`，真实服务凭据ID/名称、发送端声明的操作人、采集时间、完整硬件快照和差异。审计身份为 `INTEGRATION`，不伪装成人员用户；重放不重复写新增/登记审计。
- 旧的手工创建和 CSV 仍保留服务层 SN 唯一校验，并通过现有分配锁保护；它们不能被用作本接口的重试或 SN 合并入口。新接口允许重复 SN 后，后续手工编辑涉及这些重复 SN 的记录仍可能被旧校验拦截，本版未扩大修改其业务规则。

## 本地验证

在 `backend` 目录执行，使用测试配置及内存 H2，不连接生产 MySQL：

```powershell
.\mvnw.cmd -o -Dtest=DeploymentRegistrationApiTest test
.\mvnw.cmd -o package
```

验证以本机隔离测试为准：包含真实 AD Python 经 HTTP 自动送审、丢响应重试、批准发号、拒绝与结果回传，以及浏览器审核与亮暗主题桌面/手机布局。无新增或升级依赖。未验证生产网络、真实 MySQL 或物理 PE，未执行生产迁移。

可选跨仓库 HTTP 回归使用 `DeploymentHttpIntegrationTest`；提供 Maven 参数 `-Dad.sender.python=<具备AD装机依赖的Python路径>`、`-Dad.sender.repo=<ad-management路径>` 后运行。该测试仅启动本机随机端口和 H2 内存库；不提供参数时跳过跨仓库测试，不影响普通构建。

参考 [Spring Security 独立认证链](https://github.com/spring-projects/spring-security)（Apache-2.0）和 [Stripe 幂等参数校验](https://github.com/stripe/stripe-java/blob/master/src/main/java/com/stripe/net/RequestOptions.java)（MIT），不引入 OAuth 服务/支付SDK，不照搬支付失败缓存或24小时删除幂等键的做法。真实 MySQL 迁移、TLS代理和 AD 发送端联调需要在测试环境另行验证；本次不部署生产。
