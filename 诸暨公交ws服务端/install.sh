#!/bin/bash

set -e

SERVICE_NAME="zhuji-bus-wsserver"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CURRENT_USER=$(whoami)

# 检测当前平台对应的二进制文件名
detect_binary() {
    local os=$(uname -s | tr '[:upper:]' '[:lower:]')
    local arch=$(uname -m)

    case "$arch" in
        x86_64)
            arch="amd64"
            ;;
        aarch64|arm64)
            arch="arm64"
            ;;
        *)
            echo "错误：不支持的架构：$arch"
            exit 1
            ;;
    esac

    case "$os" in
        linux)
            BINARY_NAME="zhuji-bus-wsserver-linux-${arch}"
            ;;
        mingw*|cygwin*|msys*)
            os="windows"
            BINARY_NAME="zhuji-bus-wsserver-windows-${arch}.exe"
            ;;
        darwin)
            os="macos"
            BINARY_NAME="zhuji-bus-wsserver-macos-${arch}"
            ;;
        *)
            echo "错误：不支持的操作系统：$os"
            exit 1
            ;;
    esac

    echo "检测到平台：${os}/${arch}"
    echo "将使用二进制文件：$BINARY_NAME"

    BINARY_SOURCE="${SCRIPT_DIR}/${BINARY_NAME}"
}

SYSTEMD_SERVICE="/etc/systemd/system/${SERVICE_NAME}.service"

check_architecture() {
    ARCH=$(uname -m)
    if [ "$ARCH" != "aarch64" ] && [ "$ARCH" != "arm64" ] && [ "$ARCH" != "x86_64" ]; then
        echo "错误：此脚本不支持当前架构：$ARCH，请下载对应架构的文件。"
        exit 1
    fi
    echo "架构检查通过：$ARCH"
}

check_root() {
    if [ "$EUID" -ne 0 ]; then
        echo "错误：此脚本必须以 root 身份运行"
        exit 1
    fi
}

check_binary() {
    if [ ! -f "$BINARY_SOURCE" ]; then
        echo "错误：未在 $BINARY_SOURCE 找到二进制文件"
        echo "请先将 $BINARY_NAME 复制到脚本目录"
        exit 1
    fi
    if [ ! -x "$BINARY_SOURCE" ]; then
        chmod +x "$BINARY_SOURCE"
    fi
}

# 检查资源目录是否存在
check_resources() {
    local missing=0
    for dir in static web config; do
        if [ ! -d "${SCRIPT_DIR}/$dir" ]; then
            echo "警告：目录 ${SCRIPT_DIR}/$dir 不存在，服务可能无法正常运行"
            missing=1
        fi
    done
    if [ $missing -eq 1 ]; then
        echo "请确保 static/、web/、config/ 目录已复制到脚本目录"
    fi
}

install_service() {
    detect_binary
    check_root
    check_architecture
    check_binary
    check_resources

    cat > "$SYSTEMD_SERVICE" << EOF
[Unit]
Description=Zhuji Bus WebSocket Server
After=network.target

[Service]
Type=simple
User=$CURRENT_USER
WorkingDirectory=$SCRIPT_DIR
ExecStart=$BINARY_SOURCE
Restart=always
RestartSec=5
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF

    systemctl daemon-reload
    echo "服务安装成功：$SYSTEMD_SERVICE"
    echo "服务用户：$CURRENT_USER"
    echo "工作目录：$SCRIPT_DIR"
    echo "二进制文件：$BINARY_SOURCE"
}

uninstall_service() {
    check_root

    if systemctl is-active --quiet "$SERVICE_NAME" 2>/dev/null; then
        echo "正在停止服务..."
        systemctl stop "$SERVICE_NAME"
    fi

    if systemctl is-enabled --quiet "$SERVICE_NAME" 2>/dev/null; then
        echo "正在禁用服务..."
        systemctl disable "$SERVICE_NAME"
    fi

    if [ -f "$SYSTEMD_SERVICE" ]; then
        rm -f "$SYSTEMD_SERVICE"
        systemctl daemon-reload
        echo "服务卸载成功"
    else
        echo "服务未找到，无需卸载"
    fi
}

enable_on_boot() {
    check_root
    systemctl enable "$SERVICE_NAME"
    echo "已设置开机启动"
}

disable_on_boot() {
    check_root
    systemctl disable "$SERVICE_NAME"
    echo "已取消开机启动"
}

start_service() {
    check_root
    systemctl start "$SERVICE_NAME"
    echo "服务已启动"
}

stop_service() {
    check_root
    systemctl stop "$SERVICE_NAME"
    echo "服务已停止"
}

restart_service() {
    check_root
    systemctl restart "$SERVICE_NAME"
    echo "服务已重启"
}

status_service() {
    systemctl status "$SERVICE_NAME"
}

view_logs() {
    journalctl -u "$SERVICE_NAME" -n 50 -f
}

show_usage() {
    echo "用法：$0 {install|uninstall|enable|disable|start|stop|restart|status|logs}"
    echo ""
    echo "命令："
    echo "  install   - 安装 systemd 服务"
    echo "  uninstall - 卸载 systemd 服务"
    echo "  enable    - 启用开机启动"
    echo "  disable   - 禁用开机启动"
    echo "  start     - 启动服务"
    echo "  stop      - 停止服务"
    echo "  restart   - 重启服务"
    echo "  status    - 查看服务状态"
    echo "  logs      - 查看服务日志"
}

main() {
    if [ $# -eq 0 ]; then
        echo "Zhuji Bus WebSocket Server 安装脚本"
        echo "====================================="
        echo ""
        echo "此脚本将帮助您安装/卸载 zhuji-bus-wsserver 服务"
        echo ""
        read -p "请选择 (i)安装 或 (u)卸载？[i/u]: " choice

        case "$choice" in
            i|I)
                install_service
                echo ""
                read -p "是否设置开机启动？[y/n]: " enable_choice
                case "$enable_choice" in
                    y|Y) enable_on_boot ;;
                    n|N) echo "已跳过设置开机启动" ;;
                esac
                echo ""
                read -p "是否立即启动服务？[y/n]: " start_choice
                case "$start_choice" in
                    y|Y) start_service ;;
                    n|N) echo "服务未启动" ;;
                esac
                ;;
            u|U)
                uninstall_service
                ;;
            *)
                echo "无效选择"
                exit 1
                ;;
        esac
    else
        case "$1" in
            install) install_service ;;
            uninstall) uninstall_service ;;
            enable) enable_on_boot ;;
            disable) disable_on_boot ;;
            start) start_service ;;
            stop) stop_service ;;
            restart) restart_service ;;
            status) status_service ;;
            logs) view_logs ;;
            *) show_usage ;;
        esac
    fi
}

main "$@"
