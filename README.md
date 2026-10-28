# wall-kiosk

一台 Ubuntu 主机带三块显示器，开机自动把三个看板页面分别铺满三块屏，配置改在别的电脑上 push，主机自己跟上。

## 它解决什么

原来的流程是：开机 → 手动开浏览器 → 拖窗口到指定显示器 → 输地址 → F11。三块屏重复三遍，每次重启重来。

现在按开机键就行，剩下的它做：

- 自动登录进桌面，拉起三个 kiosk 实例，各自定位到 `xrandr` 给出的屏幕坐标
- 显示器比主机慢启动时，等到三块屏都被认出来再开始（最多等 30 秒）
- 每 30 秒检查配置仓库，远端有新提交就只重启地址变了的那块屏
- 某块屏的浏览器崩了自动重开，连续崩会退避，不会刷爆日志
- 某块屏的守护进程被系统杀掉（比如 OOM），下一轮轮询自动拉起
- 断网时用上一次的配置继续显示，不黑屏
- 配置写错时拒绝应用，保持当前画面，日志指出哪一行有问题

## 组成

```
┌──────────────────────────────────────────────┐
│ 配置仓库（在别的电脑上改）                      │
│   config/screens.conf                        │
└────────────────┬─────────────────────────────┘
                 │ git fetch 每 30s
┌────────────────▼─────────────────────────────┐
│ 大屏主机                                      │
│   wall.sh 主进程                              │
│     ├─ watch_screen DP-1    → chromium 全屏   │
│     ├─ watch_screen HDMI-1  → chromium 全屏   │
│     └─ watch_screen DP-2    → chromium 全屏   │
└──────────────────────────────────────────────┘
```

## 快速开始

```bash
# 1. 拿到代码
git clone <本仓库> ~/wall-kiosk

# 2. 装
cd ~/wall-kiosk
./install.sh git@your-git/wall-config.git

# 3. 改系统配置（禁 Wayland + 自动登录）
sudoedit /etc/gdm3/custom.conf      # 见 docs/deploy.md
sudo reboot

# 4. 验证
~/.wall/bin/wall.sh --doctor
```

## 文档

| 文档 | 什么时候看 |
|---|---|
| [docs/deploy.md](docs/deploy.md) | 第一次装，或者换机器重装 |
| [docs/usage.md](docs/usage.md) | 日常改地址、排查"为什么这块屏黑了" |
| [docs/design.md](docs/design.md) | 想知道为什么这么设计，或者要改代码 |

## 目录

```
wall-kiosk/
├── bin/wall.sh                    主程序，单文件，无外部依赖
├── templates/screens.conf.example 配置模板
├── system/gdm-custom.conf.example GDM 配置参考
├── install.sh                     安装入口
└── docs/                          文档
```

## 依赖

目标机上只需要：`chromium`、`xrandr`、`git`、`bash`。没有 Python、没有 Node、没有数据库。

`unclutter`（藏鼠标）和 `xset`（关屏保）是可选的，缺了能跑，但鼠标会停在大屏上。

## 硬约束

**必须是 X11 会话，不能是 Wayland。** GNOME 在 Wayland 下不允许应用自己指定窗口位置，`--window-position` 会被直接忽略，三个窗口会全部叠在主屏上。这是整个方案最容易被忽略的前提，装之前先确认 `echo $XDG_SESSION_TYPE` 输出 `x11`。

## 已验证 / 未验证

在 macOS 上用 mock 的 `xrandr` / `chromium` / `git` 跑通了完整链路：

- 三屏启动、负坐标解析、混合分辨率、带空格的配置
- 远端变更 → 只重启对应屏，其余 PID 不变
- 坏配置（重复接口名、缺 scheme、缩放非数字、定时重启非整数）被拦截，屏不受影响
- `kill -9` 守护进程 → 一个轮询周期内自动拉起，其余屏不受影响
- 主进程轮询与 `--reload` 并发触发，互斥锁生效
- 非法 `WALL_POLL` 回落到 30，不会忙等
- 接口名大小写写错时给出实际名字
- 带管道调用（`--reload | tail`）不挂住
- 正常退出后 PID 文件清空、锁释放、无孤儿进程

**没有在真实 Ubuntu + GNOME + 三显示器的机器上跑过。** Chromium 的实际落位、GNOME 的弹窗干扰、NVIDIA 多屏的接口命名、显示器慢启动时的实际等待时长，这四块需要真机验证。
