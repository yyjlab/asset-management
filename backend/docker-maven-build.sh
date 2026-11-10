#!/bin/sh
set -eu

settings="$(dirname "$0")/docker-maven-settings.xml"
primary=https://repo.huaweicloud.com/repository/maven

maven() {
    mvn -B -ntp -s "$settings" \
        -Daether.connector.connectTimeout=10000 \
        -Daether.connector.requestTimeout=60000 \
        -Daether.connector.http.retryHandler.count=1 "$@"
}

download_and_run() {
    for repository in "$primary" https://maven.aliyun.com/repository/public https://repo.maven.apache.org/maven2; do
        echo "[maven] Repository: $repository"
        if MAVEN_REPOSITORY_URL="$repository" maven "$@"; then
            return 0
        fi
        echo "[maven] Attempt failed; trying next repository"
    done
    echo "[maven] All repository attempts failed" >&2
    return 1
}

case "${1:-}" in
    dependencies)
        download_and_run dependency:go-offline
        ;;
    package)
        echo "[maven] Packaging with cached dependencies, offline first"
        if MAVEN_REPOSITORY_URL="$primary" maven -o package -Dmaven.test.skip=true; then
            exit 0
        fi
        echo "[maven] Offline build failed; resolving remaining artifacts online"
        download_and_run package -Dmaven.test.skip=true
        ;;
    *)
        echo "Usage: $0 dependencies|package" >&2
        exit 2
        ;;
esac
