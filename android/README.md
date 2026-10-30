# 大屏配置台（Android）

Kotlin + Jetpack Compose 写的手机端配置台：连上配置仓库，看主机列表和每块屏的地址，改完提交。

和 `web/index.html` 功能等价，共用同一套解析/校验规则（见 `docs/console.md`）。

## 构建

```bash
cd android
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

需要 JDK 17+ 和 Android SDK（compileSdk 35）。Gradle 由 wrapper 固定在 8.9，不用另外装。

**用 Android Studio 打开 `android/` 这个目录，不是仓库根目录。** 根目录没有 `settings.gradle.kts`，
打开它会得到一个不同步 Gradle 的普通工程，改代码没有补全、也构建不了。

release 开了 R8（`isMinifyEnabled` + `isShrinkResources`）。不开的话 Compose 的 DEX 会原样打进包：
v1.0.0 没开时是 **4.9 MB**，v1.0.1 开了之后是 **963 KB**（DEX 从 14.3 MB 降到 1.7 MB）。
Compose 和 AndroidX 自带 consumer rules，不需要额外 keep。

| 项 | 值 |
|---|---|
| Kotlin | 2.0.21 |
| Compose BOM | 2024.10.01 |
| AGP | 8.7.2 |
| Gradle | 8.9 |
| minSdk / targetSdk | 26 / 35 |

装到手机：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 签名

两条通道，指向同一份 keystore，环境变量优先。

### 通道一：本地 `keystore.properties`

在 `android/` 下建 `keystore.properties`（**已在 .gitignore 里，不会进仓库**）：

```properties
storeFile=../keys/wall-console.jks
storePassword=你的口令
keyAlias=wall-console
keyPassword=你的口令
```

`storeFile` 相对 `android/` 解析。先生成一份：

```bash
mkdir -p keys
keytool -genkeypair -v \
  -keystore keys/wall-console.jks \
  -alias wall-console \
  -keyalg RSA -keysize 4096 -validity 10000
```

然后 `./gradlew assembleRelease` 就会自动签名。

### 通道二：CI 环境变量

| 变量 | 内容 |
|---|---|
| `KEYSTORE_FILE` | keystore 的路径（CI 里是 `keystore.jks`） |
| `KEYSTORE_PASSWORD` | 仓库口令 |
| `KEY_ALIAS` | 别名 |
| `KEY_PASSWORD` | key 口令 |

本地想用这条通道也一样：

```bash
KEYSTORE_FILE=../keys/wall-console.jks \
KEYSTORE_PASSWORD=xxx KEY_ALIAS=wall-console KEY_PASSWORD=xxx \
./gradlew assembleRelease
```

**两条通道必须用同一份 keystore。** 换密钥会导致已装的 App 无法覆盖升级（签名不一致，只能卸载重装）。

## 发版

走 GitHub Flow：功能分支 → PR 到 `main` → 合并 → 打 tag 发版。

**仓库里只有一条 workflow，只在推 tag 时触发。** push 到分支、发 PR 都不会跑 Actions。

```bash
git tag v1.2.3
git push origin v1.2.3
```

`release.yml` 会：校验签名材料 → 还原 keystore → 跑单测 → 构建 → `apksigner verify` 确认不是 debug 证书 → 建 Release 并挂上 `wall-kiosk-console-1.2.3.apk`。

测试放在发版流程里，是因为它是唯一的门：PR 阶段没有自动检查，打 tag 前的单测就是最后一道防线。

版本号规则：tag 去掉 `v` 就是 `versionName`；`versionCode` 用 `github.run_number`，保证每次都比上一版大。

需要在仓库 Settings → Secrets 里配好：

```
KEYSTORE_BASE64     # base64 -i keys/wall-console.jks | pbcopy
KEYSTORE_PASSWORD
KEY_ALIAS
KEY_PASSWORD
```

缺任何一个，Release 会直接失败，不会产出未签名的 APK。

**还有一个仓库设置必须改**：Settings → Actions → General → Workflow permissions
选 **Read and write permissions**。

原因是 workflow 里的 `permissions: contents: write` 只能**收缩**权限，不能**扩张**：
仓库默认给 `GITHUB_TOKEN` 的是只读，workflow 里写 `write` 也没用，建 Release 会 403。
不看日志很难猜，报错长这样：

```
Resource not accessible by integration
```

## 应用内更新

关于页 → 应用更新 → 检查更新。查的是发布页的 `releases/latest` 接口，不经过应用商店。

| 步骤 | 做什么 |
|---|---|
| 检查 | 按更新源依次请求 GitHub / Gitee，取最新 tag、更新说明、APK 附件地址 |
| 比较 | 按 semver 比 tag 和 `BuildConfig.VERSION_NAME`，比当前大才提示 |
| 下载 | `HttpURLConnection` 手动跟 302 到 CDN，写 `cacheDir/updates/`，进度节流后回调界面 |
| 校验 | 包名必须是本应用；API 28+ 还要比对签名证书，和已装版本不一致直接拒装 |
| 安装 | `FileProvider` 给出 `content://` 交给系统安装器（`file://` 在 targetSdk 24+ 会抛异常） |

几个需要知道的点：

- **更新源可以改**：设置页 → 应用更新。填 `owner/repo` 会依次试 GitHub 和 Gitee；
  填完整地址（`https://…/releases/latest`）用于自建发布服务。存在 `AppSettings.updateRepo`。
- **只认正式版**：`prerelease` 或 `draft` 的 Release 会被跳过，不会自动更新到预发布版。
- **Release 的说明就是更新说明**，`release.yml` 用 `generate_release_notes: true` 生成。
- **GitHub 接口未登录时每小时 60 次**，按出口 IP 算。被限流会明确报 403，过一会儿再试。
- **没有后台自动检查**：只有点「检查更新」才发请求。
- **装完进程会被系统杀掉**，重新打开就是新版本。

下载进度条是自绘的 `NeuProgressBar`（凹槽 + 强调色填充），按 1% 或 150ms 节流更新，
不叠加补间动画——下载进度本身是连续跳的，再加动画反而显得比实际慢。

## 测试

```bash
./gradlew :app:testDebugUnitTest
```

覆盖的是纯逻辑（不依赖 Android 运行时）：

| 文件 | 覆盖 |
|---|---|
| `ScreensConfTest` | 解析、CRLF、行级编辑保留注释、六类校验规则 |
| `SettingsCodecTest` | 设置文件编解码、转义、未知主题、未来版本标记、更新源往返 |
| `SettingsMigrationsTest` | v1 prefs → v2 文件 → v3 的迁移链、字段缺失、降级安装 |
| `PalettesTest` | 每个主题 id 都有配色、未知 id 退回默认 |
| `VersionTest` | semver 比较：v 前缀、段数补齐、预发布、build 元数据、非数字段 |
| `ReleaseParserTest` | 真实 GitHub 响应、Gitee 的字符串 assets、无 APK 附件、非 JSON 输入 |
| `UpdateSourcesTest` | `owner/repo` → 双平台地址、完整地址直用、留空回默认 |
| `DownloadProgressTest` | 百分比、总量未知、超过声明长度不越界 |
| `FormatSizeTest` | 字节/KB/MB/GB 换算，0 表示未知而不是空文件 |

`ReleaseParserTest` 用的是 2026-10-30 从
`https://api.github.com/repos/xushujin/wall-kiosk/releases/latest` 真实抓下来的响应，
不是手写的样例。为了让它在 JVM 上跑得起来，`testImplementation` 里引了 `org.json:json`
（android.jar 里那份是抛 `not mocked` 的桩）——**只在测试作用域，不进 APK**。

`ScreensConf` 的规则在三个地方各有一份（`bin/wall.sh`、`web/index.html`、这里的 `ScreensConf.kt`），
仓库根目录的 `node test/console.mjs` 会拿同一批配置去跑真实的 `wall.sh --check` 做交叉验证。
改规则时三处一起改。

## 结构

```
android/
├── app/src/main/java/com/wallkiosk/console/
│   ├── MainActivity.kt
│   ├── data/
│   │   ├── Models.kt            数据模型、HostConfig（dirty 由 baseText 推导）
│   │   ├── ScreensConf.kt       解析 / 行级编辑 / 校验 ← 与 wall.sh 对齐
│   │   ├── Settings.kt          AppSettings、编解码、显式迁移链（纯逻辑）
│   │   ├── SettingsStore.kt     文件读写 + 旧版 prefs 迁移
│   │   ├── RepoApi.kt           配置仓库读写（HttpURLConnection）
│   │   └── update/
│   │       ├── Version.kt           semver 比较（纯逻辑）
│   │       ├── UpdateApi.kt         更新源解析 + releases 接口 + 响应解析
│   │       ├── UpdateDownloader.kt  下载：手动跟 302、进度节流、可取消
│   │       └── ApkInstaller.kt      包名/签名校验 + FileProvider 安装
│   └── ui/
│       ├── theme/
│       │   ├── Palettes.kt      四套配色
│       │   ├── NeuTheme.kt      CompositionLocal、尺寸、字号
│       │   └── Components.kt    拟态组件：Surface/Button/TextField/ProgressBar/…
│       ├── ConsoleViewModel.kt  配置台的状态与动作
│       ├── UpdateViewModel.kt   更新状态机（检查 → 下载 → 校验 → 安装）
│       ├── App.kt               路由、Snackbar、更新对话框挂载
│       ├── SettingsScreen.kt    外观 + 仓库 + 更新源
│       ├── HostListScreen.kt
│       ├── HostDetailScreen.kt
│       ├── AboutScreen.kt       关于 + 检查更新入口
│       ├── UpdateDialog.kt      更新对话框与进度显示
│       └── Dialogs.kt           屏幕编辑/新建主机
├── app/src/main/res/xml/file_paths.xml   FileProvider 白名单（只有 cache/updates）
└── gradle/wrapper/              wrapper（jar + properties）
```

## 规范对照

| 规范 | 落地 |
|---|---|
| 不引第三方 UI 库 | 依赖里没有 material3，界面全部用 `compose.foundation` + `compose.ui` 自己画 |
| 不引 DI 框架 | 没有 Hilt/Koin；`ConsoleViewModel` 直接 new `SettingsStore`，`RepoApi.of()` 静态构造 |
| 核心能力自己实现 | 拟态阴影（多层偏移近似）、设置存储与迁移、HTTP 调用（`HttpURLConnection`）都是手写 |
| 拟态风格 | `Modifier.neu()` 双色阴影 + 按压反向；圆角、内凹输入框统一由 `NeuDims` 控制 |
| 多套配色 / 全局生效 | `Palettes` 四套；主题 id 存在 `AppSettings.themeId`，`NeuTheme` 用 `CompositionLocal` 下发 |
| 同一个设置只有一套实现 | 主题只有 `settings.themeId` 一处；界面不另存 state |
| 存储方案 | `filesDir/settings.conf`，行式 key=value，带 `version=` |
| 显式迁移 / 禁止 destructive | `SettingsMigrations` 迁移链；版本比 App 新时**只读不回写**，不清空 |
| 关于页 | `AboutScreen`：应用名、版本、技术栈、支持范围、权限说明 |
| 双通道签名 | `keystore.properties` / 环境变量，见上文 |
| tag 触发 CI 发布 | `.github/workflows/release.yml` |
| 编译零警告 | `kotlin.compilerOptions.allWarningsAsErrors = true`，有警告直接构建失败 |
| 核心逻辑单测 | 9 个测试类，见上文（`org.json` 只在 `testImplementation`，不进 APK） |
| 不确定的明确标注 | 见下面「验证状态」 |

## 验证状态

**本地没有编译过**：开发环境没有 Android SDK、Gradle、kotlinc，`./gradlew` 跑不动。

**但发布流水线已经编译并跑通过了**。v1.0.0 和 v1.0.1 的 `testDebugUnitTest` 与 `assembleRelease` 都成功，说明：

| 项 | 状态 | 依据 |
|---|---|---|
| Kotlin 零警告编译 | ✅ | `allWarningsAsErrors` 已开，构建通过 |
| 4 个测试类全部通过 | ✅ | 构建前跑的就是 `testDebugUnitTest` |
| release 签名 | ✅ | 发布包证书指纹与本地 keystore 一致 |
| APK 可产出 | ✅ | v1.0.1 附件 963 KB，SHA-256 与 GitHub 记录一致 |
| R8 后包内容完整 | ✅ | 解包核对过包名、入口 Activity、应用名、主题、图标都在 |

**R8 之后的运行时行为没在真机验证过。** 如果装上后启动崩溃，先看 `adb logcat`，
最可能是某个类被裁掉，加一条 `-keep` 规则即可。

**仍然没验证的（只有真机能看）：**

1. 拟态阴影的实际观感——用多层偏移近似高斯模糊。边缘若出现明显台阶，调 `NeuDims.shadowSteps`
   （调大更细腻，绘制次数线性增加）
2. 四套配色在深色/浅色系统栏下的对比度
3. `Dialog` 里键盘弹出时内容是否被遮（用了 `imePadding`）
4. 真机上的滑动/点击手感

**应用内更新这块，明确没验证的：**

| 项 | 状态 | 说明 |
|---|---|---|
| 检查更新、解析真实 Release | ✅ 可离线验证 | 用真实抓取的 API 响应做单测 |
| 下载 + 进度条 | ❌ 未在真机跑过 | 逻辑有单测（百分比/未知长度/不越界），但没实际下过 |
| 签名一致性校验 | ❌ 未在真机跑过 | API 28+ 才有 `SigningInfo`；**API 26~27 只校验包名，不校验签名** |
| `FileProvider` 拉起系统安装器 | ❌ 未在真机跑过 | 依赖 manifest 里的 provider 配置，装不上会提示"没有找到能处理 APK 的应用" |
| "安装未知应用"权限引导 | ❌ 未在真机跑过 | 跳的是 `ACTION_MANAGE_UNKNOWN_APP_SOURCES`，各厂商 ROM 可能改过这个页面 |

这几项第一次真机验证时的顺序建议：先确认 `检查更新` 能拉到 v1.0.1（当前版本等于它，应显示"已是最新"），
再打一个更高版本的 tag，走完整的下载 → 校验 → 安装。
