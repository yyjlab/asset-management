#!/usr/bin/env bash
set -Eeuo pipefail

# 使用前先备份数据库和图片，并更新 compose.jenkins.yaml 中的镜像标签。
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_DIR"

if [[ ! -f compose.yaml || ! -f compose.jenkins.yaml ]]; then
  echo "错误：项目目录中需要 compose.yaml 和 compose.jenkins.yaml。" >&2
  exit 1
fi

COMPOSE=(docker compose -p asset-management -f compose.yaml -f compose.jenkins.yaml)

echo "[1/4] 检查 Compose 配置"
"${COMPOSE[@]}" config --quiet

echo "[2/4] 拉取前后端镜像"
"${COMPOSE[@]}" pull backend web

echo "[3/4] 更新前后端，保留 MySQL 和数据卷"
"${COMPOSE[@]}" up -d --no-deps --no-build --pull never --wait --wait-timeout 180 backend web

echo "[4/4] 显示服务状态"
"${COMPOSE[@]}" ps
