# 资产管理系统

面向公司内部的资产管理工具，使用 React 19.2.8 + Spring Boot 4.1.0 + MySQL 8.4.11 + Java 21（运行环境 21.0.11）+ TypeScript 7.0.2 + Vite 8.2.1 + Maven 3.9.11，通过 Docker ComposeV2 部署。

![pasted-image.png · 814](https://img.cdn1.vip/i/6aaa56c5b470d_1789548229.webp)

## 核心功能

- 资产新增、编辑、克隆、领用、归还和删除，自动生成资产编号。
- 电脑与显示器绑定、随附配件、图片及备注管理。
- 二维码标签和扫码查看，CSV 模板下载与导入校验。
- 用户管理、操作日志查询与日志 CSV 导出。

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

让钉钉应用管理员添加与 `DINGTALK_LOGIN_REDIRECT_URI` 完全一致的回调地址：`http://asset.wfq.com/api/v1/auth/dingtalk/callback`。浏览器须能访问该内网地址及 `login.dingtalk.com`，后端须能访问 `api.dingtalk.com`；正式使用建议 HTTPS。外层反向代理也应关闭该回调路径的访问日志，避免记录临时授权码。

首次用现有账号密码登录，点击右上角头像 → “绑定我的钉钉”，完成授权后即可使用登录页的“钉钉登录”。不会自动创建账号、按姓名匹配或增加权限；绑定记录只新增到用户表，不删除资产数据。同一个钉钉身份只能绑定一个账号，暂不提供换绑功能。

回调 state 与当前浏览器会话绑定，有效期默认10分钟且只能使用一次。未绑定、取消授权、会话过期及服务超时会返回提示。真实钉钉授权与内网部署需要现场联调，自动测试不能代替该验证。

### 数据保留

| 内容 | 保存位置 |
|---|---|
| 资产、用户、头像、日志和设置 | `asset-management_mysql_data` 数据卷 |
| 资产图片文件 | `asset-management_asset_images` 数据卷 |

- 保持 Compose 项目名和数据卷配置不变，正常重复部署会复用现有数据。
- 不要执行 `docker compose down -v` 或删除上述数据卷。
- 初始化密码仅用于首次创建账号；修改 `.env` 不会重置已有管理员或数据库密码。
- 新代码可能更新数据库结构，不能仅靠换回旧代码恢复数据库。

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
