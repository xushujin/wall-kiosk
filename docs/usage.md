# 使用文档

给日常运维看。装机器的事看 [deploy.md](deploy.md)。

## 1. 改大屏地址

不用登录大屏主机。

```bash
git clone git@your-git/wall-config.git
cd wall-config
vi config/screens.conf
git commit -am "调整销售看板地址"
git push
```

主机每 30 秒 fetch 一次，检测到新提交就自动生效。只有地址变的那块屏会重启（约 3 秒黑屏），其余屏不受影响。

### 配置格式

```
接口名|地址|缩放|定时重启分钟
```

```
DP-1|https://bi.example.com/dashboard/sales|1.0|0
HDMI-1|https://bi.example.com/dashboard/production|1.0|120
DP-2|https://bi.example.com/dashboard/kanban|0.9|0
```

- `#` 开头的行是注释，空行忽略
- 字段前后有空格没关系，会自动去掉
- 地址必须带 `http://` 或 `https://`
- 缩放留空等于 100%
- 定时重启填 `0` 或不填等于不重启。填了 `N` 的意思是「浏览器跑满 N 分钟就自动关掉重开」，
  给长时间挂着的看板用，防止内存越吃越多；重开那一下那块屏会黑几秒，不是故障

### 四种改动的效果

| 改动 | 结果 |
|---|---|
| 改某行的地址或缩放 | 只重启那块屏 |
| 删掉一行 | 那块屏的浏览器关掉，留黑屏 |
| 加一行 | 新起一个实例（前提是那块屏已连接） |
| 只改注释 | 什么都不重启 |

### 屏数变了怎么办

配置行数就是实际起几个实例，没有"必须是三块"的限制。

| 场景 | 怎么做 |
|---|---|
| 从 3 块减到 2 块（拆了一块屏） | 删掉那行，push。那块屏的浏览器 30 秒内关掉 |
| 从 2 块加到 3 块（新接一块） | 先 `xrandr --query \| grep connected` 拿到新接口名，加一行 push |
| 临时少了一块（线松了） | 不用改配置。那块屏跳过并记 WARN，另外的照常显示；插回去重启程序才认（不支持热插拔） |
| 多台主机屏数不同 | 用 `config/hosts/<hostname>.conf`，见 [deploy.md](deploy.md#42-多台主机--屏数不同) |

屏数对不上时，`--check` 会直接告诉你：

```
[wall] WARN: 屏数       配置 3 块 / 实际连接 2 块，不一致的屏会被跳过
```

### 配置写错了会怎样

主机会拒绝应用，保持原来那版配置继续显示。日志里会写清楚哪一行有问题：

```
WARN: 配置 [DP-1] 的地址不是 http(s)：a.test/sales
WARN: 新配置未通过校验，保持现有配置，不重启任何屏幕
```

所以推错配置不会把大屏搞黑，但也不会生效。改完记得确认。

## 2. 命令速查

程序在 `~/.wall/bin/wall.sh`，建议加个别名：

```bash
alias wall=~/.wall/bin/wall.sh
```

| 命令 | 用途 |
|---|---|
| `wall.sh` | 前台启动，日志直接打在终端，调试用 |
| `wall.sh --doctor` | 环境自检，部署后和新装机器第一件事 |
| `wall.sh --check` | 看屏幕几何、配置解析、运行状态 |
| `wall.sh --reload` | 立即拉一次仓库，不用等 30 秒 |
| `wall.sh --stop` | 停掉所有屏 |
| `wall.sh --supervise` | 外层守护，开机自启用这个 |

平时开机自启跑的是 `--supervise`，日志写进 `~/.wall/wall.log`。

## 3. 看状态

```bash
~/.wall/bin/wall.sh --check
```

输出（省略了每行开头的时间戳和 `[wall]` 前缀）：

```
运行时目录 /home/kiosk/.wall
配置仓库   /home/kiosk/.wall/repo
轮询间隔   30s
仓库版本   a1b2c3d
仓库配置   config/screens.conf
生效配置   /home/kiosk/.wall/screens.conf

已连接显示器：
  DP-1 connected primary 1920x1080+0+0 ...
  HDMI-1 connected 1920x1080+1920+0 ...
  DP-2 connected 1920x1080+3840+0 ...
屏数       配置 3 块 / 实际连接 3 块

配置解析：
  DP-1     0 0 1920 1080      https://bi.example.com/sales
  HDMI-1   1920 0 1920 1080   https://bi.example.com/production
  DP-2     3840 0 1920 1080   https://bi.example.com/kanban

配置校验   通过

运行状态：
  DP-1     pid=12345   运行中
  HDMI-1   pid=12367   运行中
  DP-2     pid=12389   运行中
```

`仓库配置` 那行是实际生效的配置来源。多台主机用 `config/hosts/` 分文件时，这里会显示 `config/hosts/<主机名>.conf`。

`--check` 会顺带做一遍配置校验，有问题直接在这里显示，不用等下次热更新。

「未连接」说明配置里的接口名和实际对不上，「已退出」说明那个屏的守护进程挂了（30 秒内会自动拉起）。

## 4. 日志

| 文件 | 内容 |
|---|---|
| `~/.wall/wall.log` | 主日志：启动、配置变更、错误 |
| `~/.wall/DP-1.log` | 该屏浏览器的 stderr，页面白屏时看这个 |
| `~/.wall/wall.log.1` | 上一个主日志（超过 5MB 轮转） |

```bash
tail -f ~/.wall/wall.log
tail -50 ~/.wall/DP-1.log
```

`wall.log` 超过 5MB 轮转，浏览器日志超过 2MB 截断只留最后 500 行。两块都不会无限长。

## 5. 故障排查

### 某块屏全黑

```bash
~/.wall/bin/wall.sh --check
```

看那块屏的状态：

- **配置解析里显示「未连接」** → 接口名不对，或者线松了。`xrandr --query` 看实际名字
- **运行状态里显示「已退出」** → 浏览器起不来，看 `~/.wall/<接口名>.log`
- **运行状态里显示「进程在跑，但页面不可达」** → 浏览器活着但停在错误页，见下一节
- **运行状态里根本没有这一行** → 配置里没有这一屏，或者启动时就跳过了

日志里如果有这么一行，说明只是大小写写错了：

```
WARN: 屏幕 dp-1 未连接；接口名大小写写错了，实际是 DP-1
```

### 屏幕停在「无法访问此网站」

这是最容易误判的一种：**进程活着、日志干净、看 PID 一切正常，但画面是废的**。所以 `--check` 会单独把它标出来：

```
运行状态：
  DP-1     pid=12345   运行中
  DP-2     pid=12389   进程在跑，但页面不可达（可能停在错误页）
```

处理方式取决于原因：

| 原因 | 会不会自己好 |
|---|---|
| 断网 / 后端重启 | **会**。探测到地址恢复可达后，那一屏自动重启，不用管 |
| 地址写错（404） | 不会。改配置 push，热更新会重启那一屏 |
| 需要登录，登录态过期 | 不会。得人工登录一次，见本文第 8 节 |

不想等轮询（默认 30 秒）可以手动推一把：

```bash
~/.wall/bin/wall.sh --reload
```

如果日志里反复出现「页面不可达 → 恢复可达 → 重启」，说明网络在抖，或者后端不稳定，不是脚本的问题。

### 浏览器升级弹窗盖住了看板

正常不会出现——启动参数已经把首次运行向导、升级提示、崩溃恢复气泡都关掉了。如果还是弹了：

- 用的是 **Google Chrome** 而不是 Chromium？它的升级提示来自系统更新服务，参数管不到，按 [deploy.md 4.3](deploy.md#43-关掉浏览器升级提示) 处理
- 弹窗不是浏览器的（比如 Ubuntu 的"软件更新可用"），那是系统通知，脚本已经用 `gsettings` 关了横幅；仍然弹就 `sudo systemctl disable --now unattended-upgrades`

### 某块屏的进程被系统杀掉了

比如内存吃紧时 OOM killer 干掉了守护进程。不用管，主进程每轮轮询都会巡检一次，30 秒内自动拉起：

```
屏 DP-2 的守护进程已消失，重新拉起
```

如果这条日志反复出现（每隔半分钟一次），说明有东西在稳定杀它，看 `dmesg -T | grep -i oom` 确认。

### 改配置一直不生效

按顺序查：

```bash
# 1. 仓库能不能拉到
~/.wall/bin/wall.sh --doctor          # 看「配置仓库可免密访问」那行

# 2. 立即拉一次看反应
~/.wall/bin/wall.sh --reload

# 3. 看日志说了什么
tail -30 ~/.wall/wall.log
```

四种典型日志：

| 日志 | 含义 |
|---|---|
| 什么都没有 | fetch 一直失败（凭据问题），或者主进程没在跑 |
| `主进程未在运行` | `--reload` 时主进程不在，起的实例没人守护 |
| `配置内容无变化` | 拉到新提交了，但 `screens.conf` 没变（改的是别的文件） |
| `新配置未通过校验` | 配置有语法错误，看上面几行的具体提示 |

### 页面白屏或报错

跟脚本无关，是页面本身的问题。先手动打开那个地址：

```bash
chromium "https://bi.example.com/sales"
```

能打开就是页面或网络问题，打不开就是地址错了或后端挂了。地址打不开时脚本会退避 30 秒重试，不会疯狂重启。

### 提示「已有实例在运行」

上次没正常退出，锁没释放。但如果锁的持有进程已经不在了，脚本会自动清理。还提示就是真的有另一个实例在跑：

```bash
~/.wall/bin/wall.sh --stop
~/.wall/bin/wall.sh
```

### 所有窗口叠在一块屏上

几乎都是 Wayland 的问题：

```bash
echo $XDG_SESSION_TYPE
```

不是 `x11` 就去改 `/etc/gdm3/custom.conf`，见 [deploy.md](deploy.md#22-关掉-wayland)。

### 鼠标指针停在大屏上

```bash
sudo apt install unclutter
~/.wall/bin/wall.sh --stop
~/.wall/bin/wall.sh
```

### 屏幕自己黑了

```bash
gsettings set org.gnome.desktop.session idle-delay 0
gsettings set org.gnome.desktop.screensaver lock-enabled false
```

程序启动时会自动设这两项，但如果有人手动改过，需要重跑一次。

## 6. 重启与关机

正常关机直接按电源键或 `sudo reboot`，脚本会收到 TERM 信号，先杀掉浏览器再退出，不会留孤儿进程。

强制重启（长按电源）可能留下锁文件和 PID 文件，下次启动时会自动清理。

只重启大屏程序不重启机器：

```bash
~/.wall/bin/wall.sh --stop
~/.wall/bin/wall.sh --supervise &
```

## 7. 换显示器或调整布局

换线之后接口名可能变（`DP-1` 变成 `DP-3`），需要改配置。

```bash
xrandr --query | grep ' connected'
```

拿到新的接口名和 `+X+Y`，改配置仓库里的 `screens.conf`，push。如果新布局和旧的差别大，可能需要在系统的「显示设置」里先排好位置。

## 8. 需要登录态的页面

第一次需要在主机上手动登录一次，之后靠 `~/.wall/profiles/<接口名>/` 里的浏览器配置保持会话。

```bash
~/.wall/bin/wall.sh --stop
chromium --user-data-dir=~/.wall/profiles/DP-1 "https://需要登录的地址"
# 手动登录，然后关掉窗口
~/.wall/bin/wall.sh
```

每个屏的 profile 是独立的，多块屏如果访问同一个需要登录的系统，要分别登录。

删掉 profile 目录等于退出登录：

```bash
rm -rf ~/.wall/profiles/DP-1
```
