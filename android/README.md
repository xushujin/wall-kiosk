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

走 GitHub Flow：功能分支 → PR 到 `main`（CI 只跑测试）→ 合并 → 打 tag 发版。

**APK 只在推 tag 时构建。** PR 和 push main 都不会产出 APK，`ci.yml` 里只有单测。
（单测会编译整个 debug variant，所以编译错误照样在 PR 阶段就拦住。）

```bash
git tag v1.2.3
git push origin v1.2.3
```

`release.yml` 会：校验签名材料 → 还原 keystore → 跑单测 → 构建 → `apksigner verify` 确认不是 debug 证书 → 建 Release 并挂上 `wall-kiosk-console-1.2.3.apk`。

测试放在发版流程里，是因为 tag 是唯一的构建入口：万一有人绕过 PR 直接打 tag，PR 上的检查就等于没跑。

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

## 测试

```bash
./gradlew :app:testDebugUnitTest
```

覆盖的是纯逻辑（不依赖 Android 运行时）：

| 文件 | 覆盖 |
|---|---|
| `ScreensConfTest` | 解析、CRLF、行级编辑保留注释、六类校验规则 |
| `SettingsCodecTest` | 设置文件编解码、转义、未知主题、未来版本标记 |
| `SettingsMigrationsTest` | v1 SharedPreferences → v2 文件迁移、字段缺失、降级安装 |
| `PalettesTest` | 每个主题 id 都有配色、未知 id 退回默认 |

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
│   │   └── RepoApi.kt           仓库读写（HttpURLConnection）
│   └── ui/
│       ├── theme/
│       │   ├── Palettes.kt      四套配色
│       │   ├── NeuTheme.kt      CompositionLocal、尺寸、字号
│       │   └── Components.kt    拟态组件：Surface/Button/TextField/Dialog/TopBar/…
│       ├── ConsoleViewModel.kt  状态与动作
│       ├── App.kt               路由、Snackbar
│       ├── SettingsScreen.kt    外观 + 仓库
│       ├── HostListScreen.kt
│       ├── HostDetailScreen.kt
│       ├── AboutScreen.kt
│       └── Dialogs.kt
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
| 核心逻辑单测 | 4 个测试类，见上文 |
| 不确定的明确标注 | 见下面「未验证项」 |

## 未验证项

**这份代码没有在本机编译过。** 开发环境没有 Android SDK、Gradle、kotlinc，
`./gradlew --version` 也因为网络限速跑不动（下载 Gradle 发行包只有几 KB/s）。

已经做的检查：

- 逐文件人工审查（导入、签名、参数顺序、类型）
- 括号平衡检查
- 依赖清单确认无 material3 / okhttp
- 逻辑与 `web/index.html` 逐函数对照

**还没做、需要你在第一次构建时确认的：**

1. `./gradlew assembleDebug` 是否零警告通过（`allWarningsAsErrors` 已开，有警告会直接失败）
2. `./gradlew :app:testDebugUnitTest` 是否全绿
3. 拟态阴影的实际观感——用多层偏移近似高斯模糊，**没有在真机上看过**。
   如果边缘出现明显台阶，调 `NeuDims.shadowSteps`（调大更细腻，但绘制次数线性增加）
4. 四套配色在深色/浅色系统栏下的对比度
5. `Dialog` 里键盘弹出时内容是否被遮（用了 `imePadding`，但没在真机验证）

这些都属于"编译/真机才能确认"的范围，不敢写成通过。
