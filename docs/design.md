# 技术文档

面向要改这份代码的人。运维操作看 [usage.md](usage.md)。

## 1. 目标与非目标

**目标**

- 开机到出画面，零人工干预
- 三块屏各自定位，互不干扰
- 配置的唯一真相在 git 仓库，改配置不用登录主机
- 单点故障（断网、页面挂了、浏览器崩了）不导致全黑

**非目标**

- 不做内容管理，页面长什么样是业务系统的事
- 不做远程监控和告警，出问题靠日志
- 不支持 Wayland
- 不支持热插拔显示器（插拔后要重启）

## 2. 分层

```
┌─────────────────────────────────────────┐
│ 配置层  git 仓库 → config/screens.conf   │  单一真相，版本可追溯
├─────────────────────────────────────────┤
│ 编排层  wall.sh                          │  读配置、算几何、管进程
├─────────────────────────────────────────┤
│ 会话层  GNOME on Xorg + openbox 无关      │  提供 X11 和窗口定位能力
├─────────────────────────────────────────┤
│ 显示层  Chromium --app + --window-position│  每屏一个实例
└─────────────────────────────────────────┘
```

编排层不关心页面内容，只负责"哪个地址该出现在哪块屏的哪个坐标上"。

## 3. 进程模型

```
wall.sh --supervise                    ← autostart 拉起，只做重启循环
  └─ wall.sh                           ← 主进程，持 .lock.d
       ├─ watch_screen DP-1            ← bash 子 shell，持 run/DP-1.pid
       │    └─ chromium                ← 独立 user-data-dir
       ├─ watch_screen HDMI-1
       │    └─ chromium
       └─ watch_screen DP-2
            └─ chromium
```

三层各自的职责：

| 层 | 管什么 | 死了会怎样 |
|---|---|---|
| `--supervise` | 主进程退出后 10 秒重启 | 无人重启，全停 |
| 主进程 | 拉配置、算几何、起子进程、轮询仓库 | 子进程成孤儿，supervise 重启后重新接管 |
| `watch_screen` | 守护一个固定 URL 的浏览器 | 那块屏黑，其他屏不受影响 |
| chromium | 渲染页面 | 5 秒后自动重开 |

**为什么 watch_screen 是独立子进程而不是主进程里跑循环**：主进程要能被单独 kill 掉重来，而不影响已经正常的屏幕。子进程独立后，主进程崩溃重启只需重新 attach（靠 PID 文件）。

**信号约定**

- 主进程收到 `TERM/INT/EXIT` → `stop_all` 后 `release_lock`
- `watch_screen` 收到 `TERM/INT` → 杀掉自己的 chromium 再退出，避免孤儿

`watch_screen` 的 trap 里引用了 `local bpid`，靠 bash 的 trap 在函数作用域内执行这一特性。这是能安全清理的关键，改成全局变量反而容易串。

## 4. 启动时序

```
t=0     GDM 自动登录 kiosk 用户
t≈5s    XDG autostart 执行 wall.sh --supervise
t≈5s    主进程：拿 .lock.d → 清残留 → git pull（最多重试 60s）
t≈7s    压制 GNOME（idle-delay / 锁屏 / 通知 / 热角 / DPMS / unclutter）
t≈7s    等 xrandr 就绪（最多 20s）
t≈7s    validate_conf
t≈7s    逐屏读几何 → fork watch_screen → 写 PID 文件
t≈8s    各 chromium 冷启动，全屏落位
t≈12s   进入 watch_repo 轮询循环
```

`--supervise` 里日志重定向到 `wall.log`，超过 5MB 轮转成 `.1`。

## 5. 配置热更新

### 5.1 检测

```bash
git fetch --quiet
new_head=$(git rev-parse '@{u}')
[ "$new_head" = "$last_head" ] && continue
```

只比 HEAD，不比对文件内容。`fetch` 是增量的，仓库只有一个小文件，开销可以忽略。

**为什么用轮询不用 webhook**：大屏主机在内网，没有公网入口，开 webhook 意味着要维护一个常驻 HTTP 服务加内网穿透，复杂度远高于每 30 秒一次 fetch。

### 5.2 应用

```
reload_from_repo
  ├─ reload_lock（目录锁，100 次 × 100ms 重试）
  ├─ 校验新配置 ── 失败 → 保持现状，返回
  ├─ 规范化成四列 → .screens.norm.new
  ├─ 空结果防御 ── 空 → 拒绝，返回
  ├─ cmp 与 .screens.norm ── 相同 → 什么都不做
  ├─ 逐屏 diff：旧行 vs 新行
  │    ├─ 相同        → 跳过（不闪）
  │    ├─ 新行为空    → stop_screen
  │    └─ 内容不同    → stop_screen + start_screen
  └─ mv .screens.norm.new → .screens.norm
```

### 5.3 为什么是增量不是整体重启

整体重启（kill 全部再全起）实现简单，但每次改一个地址三块屏全闪，运维改配置会有心理负担。

增量重启的代价是要维护一份"上一次生效的配置快照"（`.screens.norm`）。这份快照必须是**规范化后**的文本，否则改个空格、调个缩进都会误判成变更。

### 5.4 并发

主进程的轮询和手动 `--reload` 可能同时触发重载。两个进程共用 `screens.conf.new` 和 `.screens.norm.new` 两个临时文件，不加锁会出现：A 进程刚把临时文件 `mv` 走，B 进程读到空文件，判定"所有屏都从配置里移除了"，把三块屏全停掉。

这个 bug 在测试里真实复现过。修复方式是目录锁 + 空结果防御，双保险。

`reload_lock` 用 `mkdir` 的原子性而不是 `flock`：`flock` 在 macOS 上没有，本地无法验证。目录锁带持有者 PID，进程意外死亡后下一次能自愈。

### 5.5 快进失败

运维在配置仓库上 rebase 或 force push 之后，本地无法 `--ff-only` 合并。策略是 `reset --hard @{u}` 强制对齐。

这对配置仓库是安全的，因为主机侧从不产生本地提交。但对程序仓库不适用，所以两份代码分开。

## 6. 屏幕定位

`xrandr --query` 的输出：

```
DP-1 connected primary 1920x1080+0+0 (normal ...) 527mm x 296mm
```

正则提取接口名和 `+X+Y`：

```bash
^([A-Za-z0-9._-]+)\ connected\ (primary\ )?([0-9]+)x([0-9]+)\+(-?[0-9]+)\+(-?[0-9]+)
```

三个容易踩的点：

1. **负坐标**。屏幕排在主屏左侧时，xrandr 输出 `1920x1080+-1920+0`，`+` 后面跟负数。正则里 `-?` 不能省。
2. **带点的接口名**。DisplayPort MST 会产出 `DP-1.1` 这种名字，字符类要包含 `.`。
3. **不能用索引**。配置里写 `DP-1` 而不是 `0`，换线序后索引全乱。

拿到 `+X+Y` 后直接喂给 `--window-position`，配合 `--window-size` 和 `--start-fullscreen`。

**为什么用 `--app` + `--start-fullscreen` 而不是 `--kiosk`**：`--kiosk` 会全屏到"当前活动显示器"，在多屏下行为不确定；`--app` 模式先按 `--window-position` 创建窗口，`--start-fullscreen` 再把窗口全屏到它所在的那块屏，顺序可控。

## 7. 关键决策

### 为什么必须 X11

GNOME 在 Wayland 下不给客户端窗口定位的能力，这是 Wayland 协议层面的设计（安全考虑）。`--window-position` 会被静默忽略。

可选的绕法只有三条：

| 方案 | 代价 |
|---|---|
| 切 Xorg 会话 | 需要改 GDM 配置，全局生效 |
| 走 XWayland（`--ozone-platform=x11`） | Mutter 通常尊重 X11 的 geometry 请求，但不保证 |
| 换 sway/cage 这类可编程 compositor | 放弃 GNOME |

代码里默认带 `--ozone-platform=x11`，属于"先试最省事的"策略。真机验证不通过再切 Xorg。

### 为什么不用 systemd user service

GNOME 会话下的 `systemd --user` 常常拿不到 `DISPLAY` 和 `XAUTHORITY`，需要额外在 `gnome-session` 里挂 hook 去 `import-environment`，而且不同版本行为不一致。

XDG autostart（`~/.config/autostart/*.desktop`）天然在图形会话里启动，环境变量齐全。代价是没有 systemd 的 `Restart=always`，所以用 `--supervise` 自己实现重启循环。

### 为什么配置用管道分隔而不是 YAML

YAML 需要解析器。bash 里没有，要么装 `yq`，要么写 Python。而这个配置只有四个字段、没有嵌套，`IFS='|' read` 一行就够。

代价是可读性略差，靠文件头注释补。

### 为什么每屏独立 user-data-dir

Chromium 单实例模型：同一 `user-data-dir` 启动第二个进程时，会把参数转发给已有进程然后自己退出。结果就是第二个"实例"根本不存在，窗口位置和 kiosk 状态全部错乱。

独立 profile 的副作用是三块屏的登录态互相隔离，需要分别登录。

### 为什么不用 Python

不是不能，是没必要。整个程序的核心是"读几行文本 + fork 进程 + 轮询"，bash 做这些很直接。引入 Python 意味着目标机多一个运行时依赖，而大屏机器通常装完就不管，依赖越少越好。

代价是 bash 的坑要自己踩，具体见第 9 节。

## 8. 状态文件

| 路径 | 作用 | 删了会怎样 |
|---|---|---|
| `~/.wall/screens.conf` | 生效配置（仓库的本地副本） | 下次启动报无可用配置 |
| `~/.wall/.screens.norm` | 上次生效配置的规范化快照 | 下一次重载会当成全量变更，全部重启 |
| `~/.wall/run/<接口>.pid` | 各屏 watch_screen 的 PID | 该屏脱离管理，stop 时杀不掉 |
| `~/.wall/profiles/<接口>/` | 浏览器 profile | 登录态丢失 |
| `~/.wall/.lock.d/` | 主实例锁 | 无 |
| `~/.wall/.reload.lock.d/` | 重载互斥锁 | 无 |
| `~/.wall/wall.log` | 主日志 | 无 |

`.lock.d` 和 `.reload.lock.d` 都带 `pid` 文件，持有进程不存在时会被自动清理，所以强制断电后不需要人工干预。

## 9. 防御性设计

大屏机器装完就没人管，所以「出错时不要把事情弄得更糟」比「正常时跑得快」重要。下面每一条都是踩过或者差点踩到的。

### 9.1 凭据缺失不能让开机流程冻住

`git fetch` 在凭据没配好时会弹密码提示并阻塞。开机流程卡在这里，屏幕就永远是黑的。

```bash
export GIT_TERMINAL_PROMPT=0     # 不弹密码
export GIT_ASKPASS=/bin/true     # 需要密码时立刻返回空
export GIT_SSH_COMMAND="ssh -o BatchMode=yes -o StrictHostKeyChecking=accept-new"
```

`BatchMode=yes` 让 SSH 不交互（key 有 passphrase 时直接失败），`accept-new` 避免首次连接卡在 host key 确认。

### 9.2 强杀守护进程会留下全屏窗口

`stop_screen` 发 TERM 后等 4 秒，还没退就 `kill -9`。被 `-9` 杀掉的 `watch_screen` 没机会清理自己拉起的 chromium，那个窗口会继续全屏占着屏幕，新的实例反而起不来（profile 被锁）。

所以强杀之后按 profile 路径兜底清一遍：

```bash
pkill -f -- "user-data-dir=$WALL_HOME/profiles/$output"
```

### 9.3 非法轮询间隔会打满 CPU

`WALL_POLL=abc` 时 `sleep abc` 立刻失败，`while :; do sleep abc; ...` 变成不带休眠的死循环，一个核直接跑满。入口处强制回落：

```bash
case "$POLL" in
  '' | *[!0-9]*) POLL=30 ;;
esac
[[ "$POLL" -gt 0 ]] || POLL=30
```

### 9.4 显示器比主机慢启动

开机时显示器上电慢，`xrandr` 一开始只报一两块屏，剩下两块要等几秒才出现。原来的逻辑是「xrandr 能跑就开始」，会导致后两块永久缺失（要重启才恢复）。

改成等配置里要求的屏数都出现，最多 30 秒：

```bash
need=$(normalize_conf "$CONF" | cut -d'|' -f1 | sort -u | wc -l)
for i in {1..30}; do
  have=$(xrandr --query | grep -cE '^[A-Za-z0-9._-]+ connected')
  [ "$have" -ge "$need" ] && return 0
  sleep 1
done
```

注意 `grep -c ' connected'` 会把 `DP-9 disconnected` 也算进去，必须锚定行首。

### 9.5 守护进程被 OOM 杀掉没人管

`--supervise` 只保证主进程活着。如果某块屏的 `watch_screen` 被 OOM killer 干掉，那块屏就永久黑着，直到下次配置变更或重启。

每轮轮询加一次巡检：PID 文件里的进程不在了就重新拉起。只处理有 PID 文件的屏，不碰「配置里有但从没起来过」的（比如显示器没接），避免每 30 秒刷一条 WARN。

### 9.6 连按两次 Ctrl+C

trap handler 执行期间如果再来一个信号，会重入 `stop_all`，两个清理流程同时删 PID 文件会互相干扰。handler 第一行先撤掉自己：

```bash
on_stop() {
  trap - EXIT TERM INT
  stop_all >/dev/null
  release_lock
  exit 0
}
```

### 9.7 空配置不能拿来停屏

规范化结果为空（文件被截断、全是注释、写错分隔符）时，如果继续走 diff 逻辑，每一屏的「新行」都是空，判定为「已移除」，三块屏全停。

```bash
if [[ ! -s "$NORM_NEW" ]]; then
  warn "解析出的配置为空，拒绝应用"
  return 1
fi
```

### 9.8 后台子进程不能继承调用方的 stdout

`wall.sh --reload | tail -3` 会永久挂住：`--reload` 起的 `watch_screen` 继承了管道的写端，`tail` 等不到 EOF。

修法是子进程输出重定向到文件：

```bash
watch_screen ... >>"$WALL_HOME/wall.log" 2>&1 &
```

### 9.9 参数非法时给出可执行的提示

接口名大小写写错（`dp-1` 而不是 `DP-1`）是最高频的配置错误，光说「未连接」等于没说。直接用 awk 做一次大小写不敏感比对，把真实名字打出来：

```
WARN: 屏幕 dp-1 未连接；接口名大小写写错了，实际是 DP-1
```

同理，`zoom` 写成 `abc`、`refresh` 写成 `两小时` 都会被 `validate_conf` 拦下，而不是等到 chromium 启动失败才发现。

## 10. 故障模型

| 故障 | 表现 | 恢复方式 |
|---|---|---|
| 断网 | 用 `~/.wall/screens.conf` 继续显示 | 网络恢复后下一轮 fetch 自动跟上 |
| 仓库凭据失效 | 配置永不更新，日志无报错 | 修凭据后自动恢复；`--doctor` 能查出 |
| 配置语法错 | 拒绝应用，保持旧配置 | 修配置重新 push |
| 显示器未连接 | 该屏跳过，日志 WARN（带大小写提示） | 插好线重启 |
| 显示器慢启动 | `wait_xrandr` 最多等 30 秒 | 自动 |
| 浏览器崩溃 | 5 秒后重开 | 自动 |
| 页面打不开 | 浏览器启动后很快退出 | 退避 30 秒重试，自动 |
| 守护进程被 OOM 杀 | 该屏黑，其他正常 | 巡检 30 秒内自动拉起 |
| 主进程被杀 | 子进程变孤儿继续显示 | supervise 10 秒后重启，重新接管 |
| 锁残留 | 启动时提示"已有实例" | 自动清理（PID 不存在） |
| 磁盘满 | 日志写入失败，显示不受影响 | 日志有轮转，正常不会满 |

设计上有一条原则：**任何单个环节失败都不应该导致三块屏同时黑掉**。所以配置校验失败时选择"保持旧配置"而不是"清空重来"，浏览器崩溃时只重启那一块。

## 11. 已知限制

1. **不支持 Wayland**，需要改系统会话类型。
2. **不支持运行中热插拔显示器**。巡检只拉起进程，不重新扫描 xrandr；中途插上的屏要重启程序才认。
3. **刷新是重启浏览器**，会有 3 秒左右黑屏。真正无感需要走 CDP 的 `Page.navigate`，但那要引入 WebSocket 客户端。
4. **单机单配置**。多台主机需要改脚本支持 `WALL_HOST`。
5. **没有远程可观测性**。出问题只能上机器看日志。
6. **kiosk 模式下页面无法交互**，适合纯展示看板，不适合需要点击的场景。
7. **接口名变了不会自动迁移**。换线后 `DP-1` 变成 `DP-3`，需要人工改配置，脚本只会给出提示。

## 12. 扩展方向

按投入产出排序：

1. **本地错误页**。某屏起不来时用 `file://` 加载一个说明页，现场人员一眼看出是网络问题还是配置问题，比黑屏好排查。
2. **CDP 无感刷新**。启动时加 `--remote-debugging-port`，配置变更时发 `Page.navigate` 而不是杀进程重启，消除黑屏。
3. **心跳上报**。每 5 分钟往一个 HTTP 端点发一次状态，接入现有监控。
4. **多机配置**。`config/hosts/<hostname>.conf` 优先于 `config/screens.conf`。

## 13. 本地开发与测试

程序在 macOS 上也能跑逻辑，靠 mock 掉三个外部命令：

```bash
mkdir -p /tmp/t/bin
# mock xrandr：输出固定的三屏（记得带一个 disconnected 的，能测出计数 bug）
# mock chromium：exec sleep 600
# mock git：用状态文件模拟 HEAD 变化
export PATH=/tmp/t/bin:$PATH
export WALL_HOME=/tmp/t/home WALL_REPO=/tmp/t/repo WALL_POLL=3 XDG_SESSION_TYPE=x11
./bin/wall.sh --doctor
```

已验证的场景：

- 常规三屏 / 负坐标 / 混合分辨率 / 带空格的配置 / 未连接屏幕
- 远端变更 → 只重启对应屏，其余 PID 不变
- 坏配置（重复接口名、缺 scheme、zoom 非数字、refresh 非整数）被拦截，屏不受影响
- 主进程轮询与 `--reload` 并发触发，锁生效，无互斥破坏
- `kill -9` 守护进程后，巡检在一个轮询周期内重新拉起，其余两屏 PID 不变
- `WALL_POLL` 传非法值回落到 30
- 接口名大小写写错时给出实际名字
- 带管道调用（`--reload | tail`）不挂住
- 正常退出后 PID 文件清空、锁释放、无孤儿进程

未验证：真实 Chromium 的落位行为、GNOME 弹窗干扰、NVIDIA 多屏的接口命名、显示器慢启动时 `wait_xrandr` 的实际等待时长。
