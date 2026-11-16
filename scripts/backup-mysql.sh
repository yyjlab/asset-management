#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKUP_DIR="${ASSET_BACKUP_DIR:-$PROJECT_DIR/backups/monthly}"
RETENTION_COUNT="${ASSET_BACKUP_RETENTION:-12}"
BACKUP_FILE="$BACKUP_DIR/asset_management_$(date +%Y%m%d_%H%M%S).sql.gz"
TEMP_FILE="$BACKUP_FILE.partial"
IMAGE_FILE="${BACKUP_FILE%.sql.gz}.images.tar.gz"
IMAGE_TEMP_FILE="$IMAGE_FILE.partial"
LOCK_FILE="$BACKUP_DIR/.backup.lock"

cd "$PROJECT_DIR"
install -d -m 700 "$BACKUP_DIR"

if ! command -v flock >/dev/null 2>&1; then
  echo "错误：宿主机缺少 flock（通常由 util-linux 提供）。" >&2
  exit 1
fi

exec 9>"$LOCK_FILE"
if ! flock -n 9; then
  echo "错误：已有一个资产数据库备份任务正在运行。" >&2
  exit 1
fi

if [[ ! "$RETENTION_COUNT" =~ ^[1-9][0-9]*$ ]]; then
  echo "错误：ASSET_BACKUP_RETENTION 必须是大于0的整数。" >&2
  exit 1
fi

if [[ -z "$(docker compose ps -q mysql)" ]]; then
  echo "错误：MySQL 容器不存在，请先确认资产系统已经启动。" >&2
  exit 1
fi

if [[ -z "$(docker compose ps -q backend)" ]]; then
  echo "错误：后端容器不存在，无法备份资产图片。" >&2
  exit 1
fi

BACKUP_GUARD_CREATED=false
cleanup() {
  local status=$?
  trap - EXIT
  if [[ "$BACKUP_GUARD_CREATED" == true ]]; then
    if ! docker compose exec -T backend sh -c 'rmdir /app/data/asset-images/.backup-in-progress'; then
      echo "错误：图片备份保护标记未能移除；请确认备份进程已结束后人工检查。图片不会被自动删除。" >&2
      status=1
    fi
  fi
  rm -f -- "$TEMP_FILE" "$IMAGE_TEMP_FILE"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# Defer physical image deletion until both snapshots are complete; asset edits remain available.
# An existing guard fails closed, including a guard left by an interrupted backup.
docker compose exec -T backend sh -c 'mkdir -p /app/data/asset-images; mkdir /app/data/asset-images/.backup-in-progress'
BACKUP_GUARD_CREATED=true

docker compose exec -T mysql sh -c \
  'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --quick --routines --triggers --events --hex-blob --default-character-set=utf8mb4 --set-gtid-purged=OFF --no-tablespaces "$MYSQL_DATABASE"' \
  | gzip -9 > "$TEMP_FILE"

test -s "$TEMP_FILE"
gzip -t "$TEMP_FILE"
docker compose exec -T backend sh -c 'tar --exclude=asset-images/.backup-in-progress -czf - -C /app/data asset-images' > "$IMAGE_TEMP_FILE"
gzip -t "$IMAGE_TEMP_FILE"
tar -tzf "$IMAGE_TEMP_FILE" >/dev/null
mv "$IMAGE_TEMP_FILE" "$IMAGE_FILE"
mv "$TEMP_FILE" "$BACKUP_FILE"

(
  cd "$BACKUP_DIR"
  sha256sum "$(basename "$BACKUP_FILE")" "$(basename "$IMAGE_FILE")" > "$(basename "$BACKUP_FILE").sha256"
)

mapfile -t BACKUP_FILES < <(printf '%s\n' "$BACKUP_DIR"/asset_management_*.sql.gz | sort)
if (( ${#BACKUP_FILES[@]} > RETENTION_COUNT )); then
  REMOVE_COUNT=$(( ${#BACKUP_FILES[@]} - RETENTION_COUNT ))
  for (( index=0; index<REMOVE_COUNT; index++ )); do
    rm -f -- "${BACKUP_FILES[$index]}" "${BACKUP_FILES[$index]}.sha256" "${BACKUP_FILES[$index]%.sql.gz}.images.tar.gz"
  done
fi

echo "备份完成：$BACKUP_FILE"
echo "图片备份：$IMAGE_FILE"
echo "校验文件：$BACKUP_FILE.sha256"
echo "保留策略：最近 $RETENTION_COUNT 份月度备份"
