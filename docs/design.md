# 技术文档

面向要改这份代码的人。运维操作看 [usage.md](usage.md)。

## 1. 目标与非目标

**目标**

- 开机到出画面，零人工干预
- 每块屏各自定位，互不干扰；屏数不固定（1~3 块都行）
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
│ 配置层  git 仓库 → config/screens.conf   │  单一真相；按主机分文件见 §5.6
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
| `--supervise` | 主进程退出后 10 秒重启，连崩退避到 30/60 秒 | 无人重启，全停 |
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
t≈5s    主进程：拿 .lock.d → 清残留 → git pull（有缓存配置时最多重试 10s，无缓存才等 60s）
t≈7s    压制 GNOME（idle-delay / 锁屏 / 通知 / 热角 / DPMS / unclutter）
t≈7s    等 xrandr 就绪（屏齐了立刻走；配置比实际多时 10s 后按实际启动；最多 30s）
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

整体重启（kill 全部再全起）实现简单，但每次改一个地址所有屏全闪，运维改配置会有心理负担。

增量重启的代价是要维护一份"上一次生效的配置快照"（`.screens.norm`）。这份快照必须是**规范化后**的文本，否则改个空格、调个缩进都会误判成变更。

### 5.4 并发

主进程的轮询和手动 `--reload` 可能同时触发重载。两个进程共用 `screens.conf.new` 和 `.screens.norm.new` 两个临时文件，不加锁会出现：A 进程刚把临时文件 `mv` 走，B 进程读到空文件，判定"所有屏都从配置里移除了"，把所有屏全停掉。

这个 bug 在测试里真实复现过。修复方式是目录锁 + 空结果防御，双保险。

`reload_lock` 用 `mkdir` 的原子性而不是 `flock`：`flock` 在 macOS 上没有，本地无法验证。目录锁带持有者 PID，进程意外死亡后下一次能自愈。

### 5.5 快进失败

运维在配置仓库上 rebase 或 force push 之后，本地无法 `--ff-only` 合并。策略是 `reset --hard @{u}` 强制对齐。

这对配置仓库是安全的，因为主机侧从不产生本地提交。但对程序仓库不适用，所以两份代码分开。

### 5.6 配置来源：屏数不固定

主机接几块屏没有定数（1~3 块都可能），所以配置不是"一个仓库一个文件"，而是按主机名分：

```
repo_conf()
  ├─ config/hosts/<hostname>.conf  存在 → 用它
  └─ config/screens.conf           否则 → 默认
```

**为什么是函数而不是启动时算一次**：`hosts/` 下的文件可能是运行中 push 过来的，启动时算一次就等于要求重启才生效，违背"改完 push 30 秒内生效"的约定。代价是每次重载多做一次 `-f` 判断，可以忽略。

**为什么用 hostname 而不是环境变量**：环境变量要写进 `wall.desktop`，而 `wall.desktop` 是 `install.sh` 生成的，改屏数就得改安装产物。`hostname` 是现成的，`install.sh` 也只需要 `hostname` 一条命令。

配置行数就是实例数，没有任何地方硬编码屏数：

- 启动：`apply_all` 按行遍历，`start_screen` 拿不到几何就 WARN 跳过
- 重载：`reload_body` 对「旧配置 ∪ 新配置」的接口名取并集逐个 diff，少了的停、多了的起
- 巡检：`reconcile` 只处理已有 PID 文件的屏，不会去猜

所以 1 行、2 行、3 行配置走的是同一条代码路径，没有"三屏专用"的分支。

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

独立 profile 的副作用是各屏的登录态互相隔离，需要分别登录。

### 为什么不用 Python

不是不能，是没必要。整个程序的核心是"读几行文本 + fork 进程 + 轮询"，bash 做这些很直接。引入 Python 意味着目标机多一个运行时依赖，而大屏机器通常装完就不管，依赖越少越好。

代价是 bash 的坑要自己踩，具体见第 9 节。

## 8. 状态文件

| 路径 | 作用 | 删了会怎样 |
|---|---|---|
| `~/.wall/screens.conf` | 生效配置（仓库的本地副本） | 下次启动报无可用配置 |
| `~/.wall/.screens.norm` | 上次生效配置的规范化快照 | 下一次重载会当成全量变更，全部重启 |
| `~/.wall/run/<接口>.pid` | 各屏 watch_screen 的 PID | 该屏脱离管理，stop 时杀不掉 |
| `~/.wall/run/<接口>.down` | 该屏页面当前不可达（探测失败过） | 恢复时不会自动重启那一屏 |
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

开机时显示器上电慢，`xrandr` 一开始只报一两块屏，剩下的要等几秒才出现。原来的逻辑是「xrandr 能跑就开始」，会导致后几块永久缺失（要重启才恢复）。

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

**但死等配置行数是错的**，因为屏数不固定：配置写了 3 行、实际只接了 2 块（线松了、屏坏了），每次开机都要白等 30 秒，最后还是只起 2 块。加一条提前退出：

```bash
# 给显示器 10 秒上电时间，之后连上的屏数连续 2 轮不动就认为到齐了
if [ "$i" -ge 10 ] && [ "$have" -gt 0 ] && [ "$have" -eq "$last" ]; then
  warn "配置写了 $need 块屏，实际稳定在 $have 块，按实际数量启动"
  return 0
fi
```

`have -gt 0` 这个条件不能省：一块屏都没认出来说明显卡/驱动还没起来，这时候提前退出会让 `main` 直接 die 掉，比多等 20 秒糟糕得多。

代价是**超过 10 秒才上线的屏会被漏掉**（原来 30 秒内的都能等到）。这个取舍是有意的：显示器上电通常在 3~6 秒内完成枚举，10 秒足够；而"配置和实际对不上"是常态故障，每次开机多等 20 秒的代价更明显。真遇到上电特别慢的屏，把它从配置里去掉、改用 `config/hosts/` 分文件，`need` 就准了。

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

规范化结果为空（文件被截断、全是注释、写错分隔符）时，如果继续走 diff 逻辑，每一屏的「新行」都是空，判定为「已移除」，所有屏全停。

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

### 9.10 浏览器停在错误页时"看起来是活的"

这是最阴的一条。页面打不开时 chromium **不会退出**，它会显示自己的「无法访问此网站」错误页，进程照常活着：

```
$ ps -p $(cat ~/.wall/run/DP-1.pid)   # 在
$ tail ~/.wall/DP-1.log               # 干净
$ wall.sh --check                     # 加了探测之后这里才会标"页面不可达"
```

前两项都正常，实际那块屏是废的。而且它不会自愈：网络恢复、后端重启都不会让这个错误页自己刷新，除非有人去按 F5。开机时网络比主机起得慢，就会稳定复现这个状态。

光看 PID 治不了，必须探真实可达性。每轮巡检用 `curl` 探一次：

```bash
probe_url() {
  code=$(curl -s -o /dev/null -m 4 -w '%{http_code}' -I "$url")
  case "$code" in
    405 | 501) code=$(curl -s -o /dev/null -m 4 -w '%{http_code}' -r 0-0 "$url") ;;
  esac
  [[ "$code" =~ ^[23] || "$code" == "401" || "$code" == "403" ]]
}
```

**关键设计：只在「不通 → 通」的瞬间重启，不通的时候什么都不做。**

```
探测结果        动作
通，且无标记     什么都不做（正常）
不通，且无标记   记一个 .down 标记 + 一条 WARN
不通，已有标记   什么都不做（避免断网期间每 30 秒重启一次）
通，且有标记     删标记 + 重启这一屏（清掉错误页）
```

如果改成"探测不通就重启"，断网期间会变成每 30 秒重启一次浏览器，日志刷屏、CPU 白烧，而且重启完还是错误页。标记文件 `run/<接口>.down` 就是这个状态的载体，跟着 `stop_screen` 一起清理。

判断口径的取舍：`401/403` 算"通"。要登录的看板会一直返回 401，如果算成不通，标记会永远挂着，恢复时也没法触发重启——反正重启也治不好登录过期，不如不动。

**代价**：每轮轮询每块屏多一次 HTTP 请求，超时上限 4 秒。三块屏全断时一轮最多多花 12 秒，轮询周期从 30 秒变成 42 秒左右。这个延迟只影响配置热更新的响应速度，不影响画面。用 `WALL_PROBE=0` 可以整体关掉（`--doctor` 会提示代价）。

用 HEAD 而不是 GET 是为了不下载页面内容：一块 5MB 的看板每 30 秒下载三次是没必要的浪费。少数后端不支持 HEAD（405/501）才退回 GET，并且用 `-r 0-0` 只要 1 个字节。

### 9.11 开机时网络还没好，别死等 git

原来的 `sync_config` 不管有没有缓存配置都重试 30 次 × 2 秒 = 60 秒。问题是**有缓存配置的时候这 60 秒是纯浪费**：屏幕本来可以立刻用上次的配置亮起来，配置晚 30 秒跟上完全没影响。

```bash
tries=30
[[ -f "$CONF" ]] && tries=5   # 有缓存就只等 10 秒
```

一次都没成功过（新机器第一次部署）才值得等满 60 秒，因为除了等没别的办法。

### 9.12 profile 里的 Singleton 锁

chromium 用 `user-data-dir` 里的 `SingletonLock` 保证单实例。被 `kill -9` 之后锁会留在磁盘上，下一次启动 chromium 有时会认为"已有实例在跑"，把参数转发过去然后自己退出。表现出来就是那块屏永久黑着，日志里只有一行"启动 0s 就退出"。

启动前确认没有活着的实例占着这个 profile，再清锁：

```bash
if ! pgrep -f -- "user-data-dir=$profile" >/dev/null 2>&1; then
  rm -f "$profile"/Singleton*
fi
```

`pgrep` 这个前置判断不能省：直接删锁会让同一个 profile 真的跑起两个实例，窗口位置和 kiosk 状态全乱。

### 9.13 supervise 的退避

`--supervise` 原来是固定 10 秒重启。如果故障是永久的（配置仓库没配、主机名写错），会变成每 10 秒一条日志 + 一次 60 秒的 git 重试，日志很快被刷满，把真正的线索埋掉。

按"子进程活了多久"判断：跑够 30 秒算正常退出，退避归零；连续快速退出 3 次退到 30 秒，6 次退到 60 秒。上限 60 秒是为了保证故障修好后最多一分钟就能自愈。

## 10. 故障模型

| 故障 | 表现 | 恢复方式 |
|---|---|---|
| 断网 | 用 `~/.wall/screens.conf` 继续显示 | 网络恢复后下一轮 fetch 自动跟上 |
| 断网时页面停在错误页 | 进程活着但画面是错误页 | 探测到恢复可达后自动重启该屏（§9.10） |
| 开机时网络比桌面晚 | 先按缓存配置亮屏，只等 10 秒 git | 自动（§9.11） |
| 仓库凭据失效 | 配置永不更新，日志无报错 | 修凭据后自动恢复；`--doctor` 能查出 |
| 配置语法错 | 拒绝应用，保持旧配置 | 修配置重新 push |
| 显示器未连接 | 该屏跳过，日志 WARN（带大小写提示） | 插好线重启 |
| 显示器慢启动 | `wait_xrandr` 最多等 30 秒 | 自动 |
| 浏览器崩溃 / 窗口被关 | 5 秒后重开 | 自动 |
| 页面打不开 | 浏览器启动后很快退出 | 退避 30 秒重试，自动 |
| 页面打不开但浏览器没退 | 停在 chromium 错误页 | 探测 + 恢复后重启（§9.10） |
| 升级提示 / 首次运行向导 | 启动参数已关掉，不会出现 | 无需处理 |
| profile 残留 Singleton 锁 | chromium 拒绝启动，该屏永久黑 | 启动前自动清锁（§9.12） |
| 守护进程被 OOM 杀 | 该屏黑，其他正常 | 巡检 30 秒内自动拉起 |
| 主进程被杀 | 子进程变孤儿继续显示 | supervise 10 秒后重启（连崩退避到 30/60 秒），重新接管 |
| 连续崩溃 | 日志刷屏 | supervise 退避到 30/60 秒（§9.13） |
| 锁残留 | 启动时提示"已有实例" | 自动清理（PID 不存在） |
| 磁盘满 | 日志写入失败，显示不受影响 | 日志有轮转，正常不会满 |
| 页面登录态过期 | 显示登录页 | **不能自动恢复**，需人工重新登录 |

设计上有一条原则：**任何单个环节失败都不应该导致所有屏同时黑掉**。所以配置校验失败时选择"保持旧配置"而不是"清空重来"，浏览器崩溃时只重启那一块。

## 11. 已知限制

1. **不支持 Wayland**，需要改系统会话类型。
2. **不支持运行中热插拔显示器**。巡检只拉起进程，不重新扫描 xrandr；中途插上的屏要重启程序才认。
3. **刷新是重启浏览器**，会有 3 秒左右黑屏。真正无感需要走 CDP 的 `Page.navigate`，但那要引入 WebSocket 客户端。
4. **没有远程可观测性**。出问题只能上机器看日志。
5. **kiosk 模式下页面无法交互**，适合纯展示看板，不适合需要点击的场景。
6. **接口名变了不会自动迁移**。换线后 `DP-1` 变成 `DP-3`，需要人工改配置，脚本只会给出提示。
7. **配置行数必须和实际屏数对得上**。写多了那几块会跳过，写少了多出来的屏永远黑着——脚本不会自动补配置。
8. **主机名改了要同步改配置文件名**。`config/hosts/<hostname>.conf` 是按主机名匹配的，改名后找不到就退回默认的 `screens.conf`。
9. **页面登录态过期不能自动恢复**。登录页返回 200，探测认为是"通"的，重启只会再显示一次登录页。需要人工登录。
10. **探测只能判断"地址通不通"，不能判断"页面渲染对不对"**。前端报错、白屏、数据加载失败这类问题它看不见。

## 12. 扩展方向

按投入产出排序：

1. **本地错误页**。某屏起不来时用 `file://` 加载一个说明页，现场人员一眼看出是网络问题还是配置问题，比黑屏好排查。
2. **CDP 无感刷新**。启动时加 `--remote-debugging-port`，配置变更时发 `Page.navigate` 而不是杀进程重启，消除黑屏。
3. **心跳上报**。每 5 分钟往一个 HTTP 端点发一次状态，接入现有监控。
4. **配置行数自动对齐实际屏数**。目前少写一行那块屏就黑着，可以改成"配置里没有但 xrandr 里有的屏，用一个默认地址兜底"。

> `config/hosts/<hostname>.conf` 已在 §5.6 实现，不再是待办。

## 13. 本地开发与测试

程序在 macOS 上也能跑逻辑，靠 mock 掉四个外部命令（`xrandr` / `chromium` / `git` / `curl`）：

```bash
mkdir -p /tmp/t/bin
# mock xrandr：输出固定的 N 屏（记得带一个 disconnected 的，能测出计数 bug）
#   要测屏数变化，让 mock 从 $MOCK_STATE/now.txt 读，换文件就等于换屏数
# mock chromium：exec sleep 600
# mock git：用状态文件模拟 HEAD 变化
# mock curl：返回 200 或超时，用来测「页面不可达 → 恢复后重启」
export PATH=/tmp/t/bin:$PATH
export WALL_HOME=/tmp/t/home WALL_REPO=/tmp/t/repo WALL_POLL=3 XDG_SESSION_TYPE=x11
# 不想测探测就 export WALL_PROBE=0
./bin/wall.sh --doctor
```

已验证的场景：

- 1 块 / 2 块 / 3 块屏分别启动，实例数等于配置行数
- 配置 3 行、实际 2 块 → 10s 后按 2 块启动，日志有 `按实际数量启动`，不再白等 30s
- 显示器慢启动（第 3 块第 6 秒才上线）→ 等到齐，3 块都起来，没有漏屏
- `config/hosts/<hostname>.conf` 优先于 `config/screens.conf`，`--check` 显示实际选中的文件
- 页面不可达 → 只记一条 WARN、进程不重启；恢复可达 → 该屏自动重启（PID 变化），标记清除
- 浏览器跑 20 秒后被关掉 → 5 秒后重开
- profile 里预置 `SingletonLock`/`SingletonCookie` → 启动前被清掉，不影响启动
- 无配置时 `--supervise` 连续快速退出 → 退避 10s→10s→30s
- 负坐标 / 混合分辨率 / 带空格的配置 / 未连接屏幕
- 远端变更 → 只重启对应屏，其余 PID 不变
- 坏配置（重复接口名、缺 scheme、zoom 非数字、refresh 非整数）被拦截，屏不受影响
- 主进程轮询与 `--reload` 并发触发，锁生效，无互斥破坏
- `kill -9` 守护进程后，巡检在一个轮询周期内重新拉起，其余两屏 PID 不变
- `WALL_POLL` 传非法值回落到 30
- 接口名大小写写错时给出实际名字
- 带管道调用（`--reload | tail`）不挂住
- 正常退出后 PID 文件清空、锁释放、无孤儿进程

未验证：真实 Chromium 的落位行为、GNOME 弹窗干扰、NVIDIA 多屏的接口命名、显示器慢启动时 `wait_xrandr` 的实际等待时长。
