#!/usr/bin/env bash
# 启动本地 ZLMediaKit（solo 调测配置）
#
# 配置：config-solo.ini
#   媒体节点标识 mediaServerId = zlmediakit-local
#   HTTP API 端口             = 8088（http://127.0.0.1:8088/index/api/...）
#   API secret                = 见 config-solo.ini [api].secret
#
# 用法：
#   Git Bash            ./run_solo.sh
#   Windows 资源管理器   请双击同目录的 run_solo.bat（.sh 双击默认不会执行）
#
# 前台运行：关窗口或按 Ctrl+C 即停止，日志直接打在标准输出。

set -u
cd "$(dirname "$0")" || exit 1

if [ ! -f MediaServer.exe ]; then
    echo "[ERROR] 当前目录没有 MediaServer.exe" >&2
    exit 1
fi

if [ ! -f config-solo.ini ]; then
    echo "[ERROR] 当前目录没有 config-solo.ini" >&2
    exit 1
fi

echo "=============================================="
echo " ZLMediaKit   config=config-solo.ini   log=1"
echo " HTTP API     http://127.0.0.1:8088"
echo " Ctrl+C 停止"
echo "=============================================="

exec ./MediaServer.exe -c config-solo.ini -l 1
