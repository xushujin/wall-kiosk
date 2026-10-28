# 部署文档

从一台装了 Ubuntu 的空机器，到三块屏自动出画面。

## 1. 前置条件

| 项目 | 要求 | 说明 |
|---|---|---|
| 系统 | Ubuntu 22.04 / 24.04 LTS | 20.04 也能跑，但 GDM 配置路径略有差异 |
| 桌面 | GNOME | 其他桌面也可以，`gsettings` 部分会自动跳过 |
| 显示器 | 2 块以上 | 一块屏也能跑，只是没意义 |
| 账号 | 一个普通用户 | 建议专用账号（如 `kiosk`），不要用 root 跑图形程序 |
| 网络 | 能访问配置仓库 | 部署阶段需要，运行阶段断了不影响显示 |

先确认三块屏都被系统认出来了：

```bash
xrandr --query | grep ' connected'
```

输出类似：

```
DP-1 connected primary 1920x1080+0+0 (normal left inverted right x axis y axis) 527mm x 296mm
HDMI-1 connected 1920x1080+1920+0 (normal left inverted right x axis y axis) 527mm x 296mm
DP-2 connected 1920x1080+3840+0 (normal left inverted right x axis y axis) 527mm x 296mm
```

记下三个接口名（`DP-1` / `HDMI-1` / `DP-2`）和它们后面的 `+X+Y`，后面配置要用。如果这里只看到一块屏，先解决显卡/线材问题，跟本方案无关。

## 2. 系统准备

### 2.1 装依赖

```bash
sudo apt update
sudo apt install -y chromium-browser git x11-xserver-utils unclutter
```

Ubuntu 24.04 起 `chromium-browser` 是 snap 包。如果介意 snap 的启动延迟，可以换成 `apt install chromium` 或装 Google Chrome 后把脚本里的 `WALL_BROWSER` 改成 `google-chrome`。

### 2.2 关掉 Wayland

**这一步不能跳。** GNOME 在 Wayland 下会忽略应用自己指定的窗口位置，三个窗口会全部叠在主屏。

```bash
sudoedit /etc/gdm3/custom.conf
```

在 `[daemon]` 段加上：

```ini
[daemon]
WaylandEnable=false
AutomaticLoginEnable=true
AutomaticLogin=kiosk
```

`AutomaticLogin` 填你实际用的用户名。完整样例见 `system/gdm-custom.conf.example`。

部分版本还需要显式指定会话，在同一个文件里加 `DefaultSession=ubuntu-xorg`。如果改完重启后 `echo $XDG_SESSION_TYPE` 仍然输出 `wayland`，就是这一步没生效。

### 2.3 关掉锁屏和黑屏

自动登录之后如果还锁屏，等于没自动登录。程序启动时会自己设一遍，但建议先手动确认：

```bash
gsettings set org.gnome.desktop.screensaver lock-enabled false
gsettings set org.gnome.desktop.session idle-delay 0
```

## 3. 部署程序

```bash
git clone <本仓库> ~/wall-kiosk
cd ~/wall-kiosk
./install.sh git@your-git/wall-config.git
```

`install.sh` 做四件事：

1. 把 `bin/wall.sh` 复制到 `~/.wall/bin/`，加执行权限
2. 把配置模板复制到 `~/.wall/screens.conf`（仅当该文件不存在）
3. 如果给了仓库地址，`git clone` 到 `~/.wall/repo`
4. 写 `~/.config/autostart/wall.desktop`，注册开机自启

如果仓库已经有 `.git` 就跳过 clone，重装时不会覆盖。

## 4. 配置仓库

配置仓库只有 `config/screens.conf` 一个文件。格式：

```
接口名|地址|缩放|定时重启分钟
```

示例：

```
DP-1|https://bi.example.com/dashboard/sales|1.0|0
HDMI-1|https://bi.example.com/dashboard/production|1.0|120
DP-2|https://bi.example.com/dashboard/kanban|0.9|0
```

| 字段 | 必填 | 说明 |
|---|---|---|
| 接口名 | 是 | `xrandr` 里 `connected` 后面那个名字 |
| 地址 | 是 | 必须以 `http://` 或 `https://` 开头 |
| 缩放 | 否 | 留空为 100%，`0.9` 表示整体缩到 90% |
| 定时重启 | 否 | 单位分钟，`0` 或不填表示不重启 |

### 4.1 仓库凭据

主机每 30 秒 fetch 一次，必须免密。**推荐 SSH deploy key（只读）**：

```bash
ssh-keygen -t ed25519 -N "" -f ~/.ssh/wall_deploy
cat ~/.ssh/wall_deploy.pub
```

把公钥加到仓库的 Deploy Keys，勾选只读。然后确认能免密访问：

```bash
ssh -T git@github.com          # 或你的 git 服务器
git -C ~/.wall/repo ls-remote --heads origin
```

用 HTTPS 的话配 `git config --global credential.helper store`，手动 clone 一次输密码。**不要用带 token 的 URL 写进脚本**，token 会留在 `.git/config` 里。

凭据没配好的表现是：日志里没有任何报错，但配置永远不更新。`--doctor` 能查出来。

### 4.2 多台主机

一台主机一个配置文件，用 `hosts/` 分开：

```
wall-config/
└── config/
    ├── screens.conf          默认
    └── hosts/
        ├── lobby.conf        一楼
        └── floor2.conf       二楼
```

脚本当前只读 `config/screens.conf`，多机支持需要改 `wall.sh`（加 `WALL_HOST` 环境变量或按 `hostname` 选文件）。目录结构先留好，改的时候不用动仓库布局。

## 5. 首次启动验收

先别重启，手动跑一遍：

```bash
~/.wall/bin/wall.sh --doctor
```

输出应该是全 `[OK]`。有 `[!!]` 就按提示处理，别急着往下走。

自检通过后手动启动：

```bash
~/.wall/bin/wall.sh
```

应该看到三块屏各出一个页面，日志类似：

```
2026-10-28 12:00:13 [wall] 屏 DP-1 -> 1920x1080+0+0  https://...
2026-10-28 12:00:13 [wall] 屏 HDMI-1 -> 1920x1080+1920+0  https://...
2026-10-28 12:00:13 [wall] 屏 DP-2 -> 1920x1080+3840+0  https://...
2026-10-28 12:00:14 [wall] 已启动 3 个屏幕实例
2026-10-28 12:00:14 [wall] 开始监视配置仓库，每 30s 检查一次，当前版本 a1b2c3d
```

`Ctrl+C` 停掉，然后重启验证开机自启：

```bash
sudo reboot
```

如果显示器上电比主机慢，首次启动最多会等 30 秒才出画面——脚本在等配置里要求的屏数全部被 `xrandr` 认出来。这个等待是正常的，不是卡住。

### 验收清单

- [ ] `echo $XDG_SESSION_TYPE` 输出 `x11`
- [ ] `~/.wall/bin/wall.sh --doctor` 全部 `[OK]`
- [ ] 开机后不停在登录界面，直接进桌面
- [ ] 三块屏各自显示正确的页面，没有叠在一屏上
- [ ] 鼠标指针自动隐藏
- [ ] 放置 15 分钟不黑屏、不锁屏
- [ ] 在别的电脑改一条地址并 push，30 秒内那块屏刷新
- [ ] `~/.wall/bin/wall.sh --check` 显示三个屏都是"运行中"，且「配置校验 通过」

## 6. 常见部署问题

| 现象 | 原因 | 处理 |
|---|---|---|
| 三个窗口全叠在主屏 | 还在 Wayland 会话 | 改 `/etc/gdm3/custom.conf`，`WaylandEnable=false`，重启 |
| 开机停在登录界面 | 没配自动登录 | 同上文件加 `AutomaticLoginEnable=true` 和 `AutomaticLogin=用户名` |
| 某块屏全黑，日志说"未连接" | 配置里的接口名和实际不符 | `xrandr --query` 看真实名字；换过线或接口名会变 |
| 日志说"大小写写错了，实际是 DP-1" | 配置里写成 `dp-1` | 按提示改成大写，push |
| 开机后要等半分钟才出画面 | 显示器上电比主机慢 | 正常，脚本在等 `xrandr` 认全屏 |
| 改配置不生效，日志无报错 | git 凭据没配好，fetch 一直失败 | `wall.sh --doctor`，看仓库那行 |
| 改配置不生效，日志说"未通过校验" | 配置有语法错误 | 看上面几行 WARN 的具体提示 |
| 页面出来了但位置偏了 | 缩放或分辨率不匹配 | 检查配置里的接口名，或临时在 `--check` 里看几何对不对 |
| 15 分钟后黑屏 | 屏保没关 | `gsettings set org.gnome.desktop.session idle-delay 0` |
| 鼠标停在屏幕中间 | 没装 unclutter | `sudo apt install unclutter` |
| 日志里反复"启动 Ns 就退出" | 地址打不开或需要登录 | 手动用浏览器打开那个地址看 |
| 提示"已有实例在运行" | 上次没正常退出 | `wall.sh --stop` 再启动 |

## 7. 卸载

```bash
~/.wall/bin/wall.sh --stop
rm -f ~/.config/autostart/wall.desktop
rm -rf ~/.wall
```

系统层面改过的东西（GDM 的 Wayland、自动登录）需要手动还原。
