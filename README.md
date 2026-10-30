# wall-kiosk

一台 Ubuntu 主机接 1~3 块显示器，开机自动把看板页面分别铺满每块屏，配置改在别的电脑上 push，主机自己跟上。

**屏数不固定**：配置里有几行就起几个 kiosk，1 块、2 块、3 块都能跑。接了 3 块但只写 2 行，就只起 2 个；写了 3 行但只接了 2 块，第 3 块跳过并记一条 WARN，不影响另外两块。

## 它解决什么

原来的流程是：开机 → 手动开浏览器 → 拖窗口到指定显示器 → 输地址 → F11。每块屏重复一遍，每次重启重来。

现在按开机键就行，剩下的它做：

- 自动登录进桌面，按配置拉起对应数量的 kiosk 实例，各自定位到 `xrandr` 给出的屏幕坐标
- 显示器比主机慢启动时，等到该出现的屏都认出来再开始（最多 30 秒；实际接的比配置少时，10 秒后按实际数量启动，不白等）
- 每 30 秒检查配置仓库，远端有新提交就只重启地址变了的那块屏
- 某块屏的浏览器崩了或被关掉，自动重开，连续崩会退避，不会刷爆日志
- 某块屏的守护进程被系统杀掉（比如 OOM），下一轮轮询自动拉起
- 断网时用上一次的配置继续显示，不黑屏
- 断网恢复后，停在"无法访问此网站"的屏会自动重启，不会一直挂着错误页
- 配置写错时拒绝应用，保持当前画面，日志指出哪一行有问题

## 异常场景

大屏装完就没人管，所以"出错时别把事情弄得更糟"比"正常时跑得快"重要。下面每一条都有对应代码，不是口头承诺。

| 场景 | 行为 |
|---|---|
| 开机时桌面还没起全 | `wait_xrandr` 等 X 响应，最多 30 秒；整轮失败由 `--supervise` 拉起重试 |
| 开机时网络没连上 | 有缓存配置就先亮屏（只等 10 秒 git），不等满 60 秒；之后每 30 秒自动跟上 |
| 页面打不开，浏览器停在错误页 | 进程还活着但画面是废的。每轮用 `curl` 探一次，恢复可达后自动重启那一屏；不可达期间**不会**反复重启 |
| 浏览器被关掉 / 崩溃 | 5 秒内重开；如果起来 15 秒内又退出（多半是地址打不开），退避 30 秒 |
| 升级提示、首次运行向导、崩溃恢复气泡 | 启动参数全部关掉，不会盖住看板 |
| 被 `kill -9` 后 profile 留下 `Singleton` 锁 | 下次启动前自动清掉，否则 chromium 会拒绝启动、那块屏永久黑 |
| 守护进程被 OOM 杀掉 | 每轮巡检发现 PID 不在就拉起，只动那一屏 |
| 连续崩溃（比如配置永久缺失） | `--supervise` 退避到 30/60 秒，不会每 10 秒刷一遍日志 |
| 断电后锁文件和 PID 文件残留 | 持有进程不存在就自动清理，不需要人工干预 |

没法在脚本里解决的：**Google Chrome 的升级提示来自系统更新服务**，命令行掐不掉，见 [docs/deploy.md](docs/deploy.md#43-关掉浏览器升级提示)；**页面登录态过期**会显示登录页，重启也治不好，需要人工重新登录。

## 组成

```
┌──────────────────────────────────────────────┐
│ 配置仓库（在别的电脑上改）                      │
│   config/hosts/<主机名>.conf   ← 按主机分，优先  │
│   config/screens.conf         ← 默认          │
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

上图按 3 块屏画。实际是「配置里几行就几个 `watch_screen`」，1 块屏就只有一行。多台主机屏数不一样时，各自放一份 `config/hosts/<hostname>.conf`，共用一个配置仓库。

## 配套工具：配置台

改地址不用记格式、不用登录主机，也不用 git 命令。

| | 位置 | 说明 |
|---|---|---|
| 网页版 | `web/index.html` | 单文件，零依赖，起个静态服务就能用 |
| 手机 App | `android/` | Kotlin + Jetpack Compose，拟态界面，四套配色 |

连上配置仓库后，直接看到「哪台主机、几块屏、每块屏是什么地址」：

```
主机                          配置
──────────────────────────────────────────────────
默认配置    config/screens.conf    1 块屏
lobby       config/hosts/lobby.conf     3 块屏
floor2      config/hosts/floor2.conf    2 块屏
```

点进去改地址，提交后主机下一轮轮询（30 秒）自动生效，**只重启改动的那一屏**。

它走平台 REST API（GitHub / Gitea / GitLab），不碰 git 协议，所以**纯 SSH 的自建仓库用不了**——原因和替代方案见 [docs/console.md](docs/console.md)。改配置是行级的，注释和空行原样保留；校验规则和 `wall.sh` 一致，写错在提交前就被拦下。

App 的界面是自己画的（不引 material3、不引网络库、不用 DI 框架），构建、签名、发版见 [android/README.md](android/README.md)。

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
| [docs/console.md](docs/console.md) | 用配置台（网页/App）改配置、令牌怎么配 |
| [docs/design.md](docs/design.md) | 想知道为什么这么设计，或者要改代码 |

## 目录

```
wall-kiosk/
├── bin/wall.sh                    主程序，单文件，无外部依赖
├── templates/screens.conf.example 配置模板
├── system/gdm-custom.conf.example GDM 配置参考
├── install.sh                     安装入口
├── web/index.html                 配置台（单文件网页）
├── android/                       配置台（Kotlin + Compose）
├── .github/workflows/             只有 release.yml，推 tag 才触发
├── test/                          配置解析与仓库读写的测试
├── .gitignore                     运行时状态 / 真实配置 / 凭据不进版本库
└── docs/                          文档
```

## 测试

```bash
node test/console.mjs   # 解析 / 行级编辑 / 校验，并与 wall.sh --check 交叉验证
node test/api.mjs       # 仓库读写适配层（假 GitHub / GitLab API）
```

`console.mjs` 会把同一批配置同时喂给网页版的校验器和真实的 `bin/wall.sh --check`，
两边结论不一致就报错——这条是防止「页面说没问题、主机却不生效」的护栏。

## 依赖

目标机上只需要：`chromium`、`xrandr`、`git`、`bash`。没有 Python、没有 Node、没有数据库。

`curl` 是**强烈建议**装的：没有它就无法探测页面可达性，断网恢复后停在错误页的屏不会自愈。

`unclutter`（藏鼠标）和 `xset`（关屏保）是可选的，缺了能跑，但鼠标会停在大屏上。

## 硬约束

**必须是 X11 会话，不能是 Wayland。** GNOME 在 Wayland 下不允许应用自己指定窗口位置，`--window-position` 会被直接忽略，所有窗口会全部叠在主屏上。这是整个方案最容易被忽略的前提，装之前先确认 `echo $XDG_SESSION_TYPE` 输出 `x11`。

## 已验证 / 未验证

在 macOS 上用 mock 的 `xrandr` / `chromium` / `git` 跑通了完整链路：

- 1 块 / 2 块 / 3 块屏启动，负坐标解析、混合分辨率、带空格的配置
- 配置行数多于实际屏数（写 3 块只接 2 块）→ 10 秒后按实际数量启动，不再白等 30 秒
- 显示器慢启动（第 3 块 6 秒后才上线）→ 等到齐再启动，不会漏屏
- `config/hosts/<hostname>.conf` 优先于 `config/screens.conf`
- 页面不可达 → 只记一条日志、不重启（避免断网期间反复重启）；恢复可达 → 自动重启该屏
- 浏览器跑 20 秒后被关掉 → 5 秒后重开
- profile 里残留 `SingletonLock` → 启动前清掉，不影响启动
- 没有配置时 `--supervise` 连续快速退出 → 退避 10s→10s→30s，不刷屏
- 远端变更 → 只重启对应屏，其余 PID 不变
- 坏配置（重复接口名、缺 scheme、缩放非数字、定时重启非整数）被拦截，屏不受影响
- `kill -9` 守护进程 → 一个轮询周期内自动拉起，其余屏不受影响
- 主进程轮询与 `--reload` 并发触发，互斥锁生效
- 非法 `WALL_POLL` 回落到 30，不会忙等
- 接口名大小写写错时给出实际名字
- 带管道调用（`--reload | tail`）不挂住
- 正常退出后 PID 文件清空、锁释放、无孤儿进程

**没有在真实 Ubuntu + GNOME + 多显示器的机器上跑过。** Chromium 的实际落位、GNOME 的弹窗干扰、NVIDIA 多屏的接口命名、显示器慢启动时的实际等待时长，这四块需要真机验证。
