#!/usr/bin/env bash
#
# install.sh - 在目标 Ubuntu 机器上安装多屏大屏编排
#
#   ./install.sh                        只装脚本和自启，配置仓库稍后手动 clone
#   ./install.sh git@github.com:xx/wall-config.git   同时 clone 配置仓库
#
set -euo pipefail

SRC="$(cd "$(dirname "$0")" && pwd)"
WALL_HOME="${WALL_HOME:-$HOME/.wall}"
REPO_URL="${1:-}"

# 先把缺的东西说清楚，别装完了 --doctor 才报错
missing=""
for c in git xrandr; do
  if ! command -v "$c" >/dev/null 2>&1; then
    missing="$missing $c"
  fi
done
found_browser=""
for b in chromium chromium-browser google-chrome google-chrome-stable; do
  if command -v "$b" >/dev/null 2>&1; then
    found_browser="$b"
    break
  fi
done
if [[ -z "$found_browser" ]]; then
  missing="$missing chromium"
fi

if [[ -n "$missing" ]]; then
  printf '!! 缺少命令：%s\n' "$missing"
  printf '   sudo apt install -y chromium-browser git x11-xserver-utils unclutter curl\n\n'
fi

printf '==> 安装到 %s\n' "$WALL_HOME"
mkdir -p "$WALL_HOME/bin" "$WALL_HOME/profiles"

install -m 0755 "$SRC/bin/wall.sh" "$WALL_HOME/bin/wall.sh"

if [[ -f "$WALL_HOME/screens.conf" ]]; then
  printf '    生效配置已存在，保留不动\n'
else
  install -m 0644 "$SRC/templates/screens.conf.example" "$WALL_HOME/screens.conf"
  printf '    已放置初始配置模板，真实地址请改配置仓库里的 config/screens.conf\n'
fi

if [[ -n "$REPO_URL" ]]; then
  if [[ -d "$WALL_HOME/repo/.git" ]]; then
    printf '==> 配置仓库已存在，跳过 clone\n'
  else
    printf '==> 克隆配置仓库\n'
    git clone "$REPO_URL" "$WALL_HOME/repo"
    # 每台主机接几块屏不一定一样，优先用按主机名分的配置
    host_conf="$WALL_HOME/repo/config/hosts/$(hostname 2>/dev/null).conf"
    if [[ -f "$host_conf" ]]; then
      cp -f "$host_conf" "$WALL_HOME/screens.conf"
      printf '    已使用本机配置 config/hosts/%s\n' "$(basename "$host_conf")"
    elif [[ -f "$WALL_HOME/repo/config/screens.conf" ]]; then
      cp -f "$WALL_HOME/repo/config/screens.conf" "$WALL_HOME/screens.conf"
    fi
  fi
fi

AUTOSTART="$HOME/.config/autostart"
mkdir -p "$AUTOSTART"
cat >"$AUTOSTART/wall.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=Wall Kiosk
Comment=多屏大屏自动编排
Exec=$WALL_HOME/bin/wall.sh --supervise
X-GNOME-Autostart-enabled=true
X-GNOME-Autostart-Delay=5
Terminal=false
NoDisplay=true
EOF
printf '==> 已注册开机自启 %s\n' "$AUTOSTART/wall.desktop"

if [[ -d "$SRC/docs" ]]; then
  mkdir -p "$WALL_HOME/docs"
  cp -f "$SRC/docs/"*.md "$WALL_HOME/docs/" 2>/dev/null || true
  cp -f "$SRC/README.md" "$WALL_HOME/docs/README.md" 2>/dev/null || true
  printf '==> 文档已复制到 %s\n' "$WALL_HOME/docs"
fi

cat <<'TIP'

------------------------------------------------------------------------
改配置不用登录这台机器：
  在任意电脑上改仓库里的 config/screens.conf（多台主机用 config/hosts/<主机名>.conf），
  push 之后主机每 30 秒自己 fetch 一次，检测到新提交就只重启地址变了的那块屏。
  想立刻生效可以在主机上跑 ~/.wall/bin/wall.sh --reload

文档（也可以看源码目录里的）：
  ~/.wall/docs/README.md    总览
  ~/.wall/docs/deploy.md    部署
  ~/.wall/docs/usage.md     日常使用与排错
  ~/.wall/docs/design.md    技术设计
  ~/.wall/docs/console.md   配置台（网页 / App）

还有两件事必须手动改，否则窗口位置不可控：

1) 关掉 Wayland（GNOME 在 Wayland 下不允许应用自己指定窗口位置）
     sudoedit /etc/gdm3/custom.conf
   在 [daemon] 段加：
     WaylandEnable=false

2) 打开自动登录，避免开机停在登录界面
   同一个文件里加：
     AutomaticLoginEnable=true
     AutomaticLogin=<你的用户名>

改完 sudo reboot 验证。

部署后第一件事：
  ~/.wall/bin/wall.sh --doctor    环境自检，有 [!!] 先处理

其他命令：
  ~/.wall/bin/wall.sh --check     看屏幕几何、配置解析、运行状态
  ~/.wall/bin/wall.sh --reload    立即拉一次仓库并按差异重启
  ~/.wall/bin/wall.sh --stop      停掉所有屏
  xrandr --query                  确认每块屏的接口名和 +X+Y 偏移
  echo $XDG_SESSION_TYPE          必须是 x11
  tail -f ~/.wall/wall.log        运行日志（自启模式下）
------------------------------------------------------------------------
TIP
