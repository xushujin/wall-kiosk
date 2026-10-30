# 配置台（网页版 + App）

用来不登录大屏主机，直接在电脑或手机上看「哪台主机、哪块屏、显示的是哪个地址」，改完提交。

两个前端，同一套逻辑：

| | 位置 | 什么时候用 |
|---|---|---|
| 网页版 | `web/index.html`，单文件零依赖 | 电脑上快速看一眼、批量改几块屏 |
| App | `android/`，Kotlin + Compose | 手机上改，现场调试时不用找电脑 |

## 1. 先说限制

**它走的是平台 REST API，不是 git 协议。**

| 配置仓库放在哪 | 能不能用 |
|---|---|
| GitHub / Gitee / GitHub Enterprise / Gitea / GitLab | 能用 |
| 只开了 SSH 的自建裸仓库 | **不能用** |

原因很实际：浏览器里跑不了 git；手机上跑 JGit 依赖重、而且要处理 SSH 私钥。走 HTTPS API 只需要一个 token，还能顺便做权限控制。

如果配置仓库只有 SSH，三条路：

1. 在 Gitea 上做一份镜像，大屏照旧从 SSH 拉，人从镜像看（推荐）
2. 用平台自带的网页文件编辑器（能用，但没有「主机 / 屏」这个视图）
3. 给裸仓库加个只读的 HTTP 出口

## 2. 令牌

| 平台 | 怎么建 | 需要的权限 |
|---|---|---|
| GitHub | Settings → Developer settings → Fine-grained tokens | 只选这一个仓库，`Contents: Read and write` |
| Gitee | 设置 → 私人令牌 → 生成新令牌 | 勾 `projects` |
| GitLab | Preferences → Access tokens | `read_repository` 只读；要改再加 `write_repository` |
| Gitea | 设置 → 应用 → 生成令牌 | 勾 `repository` |

只读令牌也能连上看，点提交时会报 403，不会静默失败。

**Gitee 的 token 是放在 URL 参数里的**（`?access_token=`），这是 Gitee v5 唯一支持的鉴权方式。
HTTPS 传输本身是加密的，但中间的代理或网关有可能把完整 URL 记进访问日志。
如果你的 Gitee 仓库走公网，建议给 token 设短有效期、用完即删。

## 3. 网页版

```bash
cd web
python3 -m http.server 8080
# 打开 http://localhost:8080/index.html
```

**别直接双击 html 打开。** `file://` 的源是 `null`，有些服务端会拒绝这种跨域请求，表现是「连接」点了没反应。页面检测到 `file://` 会提示。

CORS 情况（实测）：

- `api.github.com`：允许跨域，开箱能用
- `gitee.com/api/v5`：返回 `Access-Control-Allow-Origin: *`，开箱能用
- 自建 Gitea：要在 `app.ini` 的 `[cors]` 里把 `ALLOWED_ORIGINS` 配上你访问的地址
- GitLab：一般允许；自建的如果关了 CORS 会失败

浏览器控制台会打印具体原因，别只看界面上的提示。

## 4. App

```bash
cd android
./gradlew assembleDebug        # 产物在 app/build/outputs/apk/debug/
```

或者直接用 Android Studio 打开 `android/` 目录。构建、签名（本地 / CI 两条通道）、打 tag 发版的完整流程见 [android/README.md](../android/README.md)。

界面是拟态风格，四套配色（石墨 / 午夜 / 瓷白 / 沙褐），在「设置 → 外观」里切换，选完立刻全局生效。关于页里有版本号、技术栈、支持范围和权限说明。

**装过一次之后就能在 App 内升级**：关于页 → 应用更新 → 检查更新。它查的是本仓库的 Release，
下载有进度条，装之前会核对包名和签名。更新源默认是 `xushujin/wall-kiosk`，
国内网络慢的话在「设置 → 应用更新」里换成 Gitee 镜像或自建地址即可。

**token 存在 App 私有目录的 `settings.conf` 里，是明文。** 和网页版存 localStorage 一个性质。建议日常用只读令牌，需要改的时候再换带写权限的。

这个文件带 schema 版本号，升级 App 时会走显式迁移；如果文件版本比 App 还新（降级安装），App 会变成只读，不会把新字段冲掉。

## 5. 它到底改了什么

配置仓库的结构：

```
config/screens.conf            默认配置（没有单独 hosts 文件的主机用它）
config/hosts/<主机名>.conf      按主机名分的配置
```

**编辑是行级的**：只替换命中接口名的那一行，注释、空行、行的顺序全部原样保留。所以 `templates/screens.conf.example` 里那一大段说明注释不会被吃掉。

```
# 大厅三块屏            ← 不动
# 改完 push 30 秒内生效  ← 不动

DP-1|https://old/x|1.0|0  ← 只改这一行
HDMI-1|https://b/y|1.0|120
```

新增主机时会写一个带注释头的文件，并且**强制要求同时填第一块屏**：空配置文件会让那台主机启动时报「无可用配置」，而空文件一旦提交就已经进仓库了。

## 6. 校验规则

和 `bin/wall.sh` 的 `validate_conf` 完全一致，改之前先在客户端拦下来：

| 检查 | 提示 |
|---|---|
| 地址为空 | 缺少地址 |
| 地址不是 `http(s)://` 开头 | 地址不是 http(s)：xxx |
| 缩放不是数字 | 缩放不是数字：xxx |
| 定时重启不是整数 | 定时重启不是整数：xxx |
| 接口名重复 | 接口名重复（后一条会覆盖前一条） |
| 一条有效配置都没有 | 没有任何屏幕配置，主机启动时会报「无可用配置」 |

最后一条比 `wall.sh` 的 `validate_conf` 更严格（它不管空文件），但 `reload_body` 会因为「解析结果为空」拒绝应用，所以效果一样，只是提前拦。

这套规则有三份实现：`bin/wall.sh`、`web/index.html`、`android/.../ScreensConf.kt`。**改任何一处都要同步另外两处**，`test/console.mjs` 会拿同一批配置去跑真实的 `wall.sh --check` 做交叉验证，分叉了会直接报错。

```bash
node test/console.mjs   # 解析/编辑/校验 + 与 wall.sh 交叉验证
node test/api.mjs       # 仓库读写适配层（假 GitHub / GitLab API）
```

## 7. 并发冲突

网页版和 App 写文件时都会带上**读到的版本号**：

- GitHub：`sha` 乐观锁。别人在你编辑期间推了新提交，你的 PUT 会被拒（409），界面提示「提交失败」，刷新后重来
- GitLab：Files API 没有 sha 校验，后写的直接覆盖先写的。多人同时改同一个文件时要小心

大屏侧不会产生本地提交（它只 `pull --ff-only` 或 `reset --hard`），所以不用担心主机和人对冲。

## 8. 改完多久生效

提交进仓库后，主机下一轮 `git fetch`（默认 30 秒）就会拉到，并且**只重启地址变了的那一屏**，其余屏不闪。

想立刻生效，在主机上执行 `~/.wall/bin/wall.sh --reload`。

## 9. 和主机上看到的不一致？

配置台只读仓库，看不到运行状态。判断某块屏是不是真的在显示，要看主机上的：

```bash
~/.wall/bin/wall.sh --check     # 屏数、每屏地址、是否「页面不可达」
```

「配置里写了」和「屏上真的显示了」是两件事——中间还隔着 git 拉取、浏览器启动、页面加载。排查顺序见 [usage.md](usage.md) 第 5 节。
