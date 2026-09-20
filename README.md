# Dr.COM 校园网自动登录（Android）

一个**零第三方依赖**的安卓 App：只要连着校园 WiFi，它就在后台定时检查你是否掉线，一旦掉线自动重新认证，不必再手动打开认证页面。

> ## ⚠️ 适用范围声明（请先读这一段）
>
> **本项目的默认配置与全部实测验证，均在「福建农业职业技术学院」校园网完成。**
>
> - 默认网关 `172.16.80.3`、认证端口 `801`、运营商后缀规则（`@yd` / `@dx` / `@lt`）**都是该校环境的实测结果**。
> - **其它学校**：网关地址、端口、甚至认证协议版本都可能不同，需要你自己确认后修改配置；**本项目不保证在这些学校可用**。
> - 因此本项目的价值，主要是提供**同一套 Dr.COM 认证协议的一份可运行实现参考**——协议流程、参数与坑点都写在下方文档里，换环境照着改 `Config.kt` 的默认值即可。

---

## 功能特性

- **自动检查 + 自动重登**：WorkManager 周期任务定时查在线状态，掉线立即重新认证
- **开机自启**：开机 / 应用更新后自动恢复任务，不需要手动打开 App
- **国产 ROM 保活**：前台服务 + 常驻通知 + 网络变化回调，配合自启动白名单
- **SSID 过滤**：可指定只在某个校园 WiFi 下生效，避免在家或流量下空跑
- **内置状态面板与日志**：是否在线、上次检查时间、上次错误一目了然；日志滚动保留最近 200 行
- **零第三方依赖**：网络、JSON、存储全部使用平台 API，无 OkHttp / Retrofit / Gson
- **云端自动构建**：推送到 GitHub 即由 Actions 自动产出 APK，本地无需安装 Android SDK

---

## 技术栈

### 构建与 SDK

| 项 | 值 |
| --- | --- |
| 语言 | **Kotlin 1.9.24** |
| Android Gradle Plugin | **8.5.2** |
| Gradle | **8.9**（CI 由 `gradle/actions/setup-gradle@v4` 提供；**仓库内不含 gradle wrapper**） |
| JDK | **17（Temurin）** |
| compileSdk | **34**（Android 14） |
| targetSdk | **34** |
| minSdk | **26**（Android 8.0） |
| Java / Kotlin 目标 | **Java 17** |
| ViewBinding | 启用（`buildFeatures { viewBinding = true }`） |
| release 混淆 | 关闭（`isMinifyEnabled = false`） |
| namespace / applicationId | `com.drcom.autologin` |
| 版本号 | `versionName "1.0"` / `versionCode 1` |

### 依赖（共 5 个，无其它）

| 依赖 | 版本 | 用途 |
| --- | --- | --- |
| `androidx.core:core-ktx` | **1.13.1** | 核心扩展（`ContextCompat` 等） |
| `androidx.appcompat:appcompat` | **1.7.0** | Activity 基类、兼容性支持 |
| `com.google.android.material:material` | **1.12.0** | Material 3 主题、`SwitchMaterial` 等控件 |
| `androidx.constraintlayout:constraintlayout` | **2.1.4** | 布局 |
| `androidx.work:work-runtime-ktx` | **2.9.1** | WorkManager 周期任务 |

### CI/CD

| 项 | 值 |
| --- | --- |
| 平台 | GitHub Actions（`.github/workflows/build-apk.yml`） |
| 触发条件 | push 到 `main` / `master`；也支持 `workflow_dispatch` 手动触发 |
| Runner | `ubuntu-latest` |
| 步骤 | `actions/checkout@v4` → `actions/setup-java@v4`（temurin 17）→ `gradle/actions/setup-gradle@v4`（Gradle 8.9）→ **Decode signing keystore**（把 `SIGNING_KEYSTORE_BASE64` 解码成临时密钥库）→ `gradle assembleRelease --no-daemon --stacktrace` → **Verify APK**（`apksigner verify --verbose --print-certs` + `aapt2 dump badging`）→ `actions/upload-artifact@v4` |
| Artifact 名 | **`DrcomAutoLogin-APK`** |
| 产物路径 | **`app/build/outputs/apk/release/app-release.apk`** |
| 实测构建耗时 | 约 **1 分 20 秒**（首次含依赖下载约 2-3 分钟） |

### 签名

| 项 | 值 |
| --- | --- |
| 签名类型 | release 正式签名（非 debug 签名） |
| 密钥库 | PKCS12，RSA 2048，有效期 10000 天 |
| 证书主体 | `CN=TSS-Small-sunshine, OU=DrcomAutoLogin, O=TSS-Small-sunshine, L=Fuzhou, ST=Fujian, C=CN` |
| 签名方案 | **v1（JAR）+ v2 + v3 同时启用** |
| 凭据来源 | GitHub Actions Secrets（仓库内不含任何密钥材料） |

> 为什么同时开 v1：只签 v2 的 APK 在部分国产 ROM 上会被包解析器拒绝，报「解析软件包时出现问题 / packageInfo is null」。v1 兼容性最好，保留它没有副作用。

### 其它实现要点

| 项 | 实现方式 |
| --- | --- |
| 网络请求 | 平台 `java.net.HttpURLConnection`（**无** OkHttp / Retrofit） |
| JSON 解析 | Android 内置 `org.json`（**无** Gson / Moshi / kotlinx-serialization） |
| 数据存储 | 平台 `SharedPreferences` |
| 日志 | 应用私有目录下的日志文件，滚动保留最近 200 行，线程安全 |
| 签名 | release 正式签名（见上文「签名」小节）；**v1（JAR）+ v2 + v3** 同时启用，凭据经 GitHub Secrets 注入 |
| 实测 APK | 5.95 MB，896 个 ZIP 条目，含 `AndroidManifest.xml` / `classes.dex` / `resources.arsc` |
| 明文流量 | 认证全程 HTTP 明文，manifest 已开启 `usesCleartextTraffic="true"` |

---

## 架构说明

### 模块职责

源码共 9 个 Kotlin 文件，全部位于 `app/src/main/java/com/drcom/autologin/`。

| 文件 | 职责 |
| --- | --- |
| `Config.kt` | 配置数据类 + 默认值 + 选项常量（运营商、检查间隔） |
| `Prefs.kt` | `SharedPreferences` 读写配置 + 持久化运行状态 |
| `LogStore.kt` | 应用私有目录日志文件，滚动保留最近 200 行，线程安全 |
| `LoginEngine.kt` | **协议核心**：查在线 / 登录 / 本机 IP+MAC 探测 / JSONP 解析 / `runOnce` 完整流程 |
| `LoginWorker.kt` | WorkManager `Worker`；做 SSID 过滤后调用 `LoginEngine.runOnce`，恒返回 `Result.success()` |
| `Scheduler.kt` | 注册 / 取消周期任务、触发立即执行 |
| `BootReceiver.kt` | 接收 `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` → 注册任务 + 立即检查一次 |
| `KeepAliveService.kt` | 前台服务（`specialUse` 类型）+ 常驻静默通知 + 网络变化回调 |
| `MainActivity.kt` | 单页界面：状态面板 + 配置表单 + 日志查看 |

### 触发源 → 执行链路

```text
开机 / 应用更新 ──┐
周期任务(15/30/60/120min) ──┤
WiFi 网络恢复 ──┼──► LoginWorker ──► LoginEngine.runOnce()
用户点「立即登录」──┘                        │
                                            ├─► 查在线 (chkstatus)
                                            ├─► 未在线 → 登录 (portal/login)
                                            ├─► 写日志 LogStore
                                            └─► 写状态 Prefs ──► MainActivity 每 2 秒刷新显示
```

---

## 认证协议

### 1）查在线 —— 端口 80

```bash
GET http://{HOST}/drcom/chkstatus?callback=cb&jsVersion=4.X
```

```text
→ cb({"result":1,"uid":"...","v4ip":"192.168.x.x","olmac":"xxxxxxxxxxxx",...})
```

判断逻辑：

- `result == 1` → **已在线**
- `result == 0` → **未在线**
- 请求失败 → **网关不可达**（可能不在校园网内）

### 2）登录 —— 端口 801

```bash
GET http://{HOST}:801/eportal/portal/login
    ?callback=dr{随机数}
    &login_method=1
    &user_account={账号}{运营商后缀}
    &user_password={密码}
    &wlan_user_ip={本机IP}
    &wlan_user_ipv6=
    &wlan_user_mac={本机MAC}
    &wlan_ac_ip=
    &wlan_ac_name=
    &terminal_type=1
    &jsVersion=4.1.3
    &lang=zh-cn
    &v={随机数}
```

```text
→ dr{随机数}({"result":1,"msg":"...","ret_code":0})
```

判断逻辑：`result == 1` → **登录成功**。

### 要点

- **运营商后缀拼在 `user_account` 里**：移动 `@yd` / 电信 `@dx` / 联通 `@lt` / 空 = 校园用户。
- `login_method=1`、`terminal_type=1`、`jsVersion=4.1.3` 是**固定必填**，缺任意一个都会认证失败（实测）。
- **IP / MAC 探测**：本机 IP 优先从 `chkstatus` 响应的 `v4ip` 取，失败则用 `DatagramSocket` connect 到网关后读 `localAddress`；MAC 优先取 `olmac`，失败则用占位 `000000000000`。
- 全程 **HTTP 明文**（Android 9+ 需要 `usesCleartextTraffic="true"`，本工程已开启）。

---

## 目录结构

```text
android/
├── .github/workflows/build-apk.yml   # GitHub Actions 自动编译脚本（最重要，别漏传）
├── app/
│   ├── build.gradle.kts              # App 构建配置：包名、SDK 版本、依赖
│   ├── proguard-rules.pro            # 混淆规则（release 未启用混淆）
│   └── src/main/
│       ├── AndroidManifest.xml        # 权限与组件声明（网络 / 开机自启 / 通知 / 定位 / 前台服务）
│       ├── java/com/drcom/autologin/  # 9 个 Kotlin 源文件（见「架构说明」）
│       └── res/                       # 界面布局、文案、颜色、图标
├── build.gradle.kts                  # 顶层：AGP 8.5.2 + Kotlin 1.9.24
├── settings.gradle.kts               # 仓库源与模块声明
├── gradle.properties                 # JVM 参数等
├── .gitignore
├── README.md                         # 本文件
└── docs/                             # 详细文档（编译 / 保活 / 排错）
```

---

## 快速开始

### 路径 A：直接用现成 APK（不写代码）

1. 打开仓库的 `Actions` 标签页 → 点进 `Build APK` 任务 → 拉到页面底部 `Artifacts` 区域 → 下载 **`DrcomAutoLogin-APK`**（得到 zip，解压出 `app-release.apk`）。
2. 把 APK 传到手机安装（需按提示允许「安装未知应用」）。
3. 打开 App，填上网账号 / 密码 / 运营商，点「保存配置」→ 点「立即登录」验证。
4. 打开「自动检查」，然后按 [02 - 国产 ROM 保活指引](docs/02-国产ROM保活指引.md) 配好自启动与省电白名单。

> Artifact 下载链接有效期约 90 天，过期后到 `Actions` 点一次 `Run workflow` 重新编译即可。安装、权限、界面各项含义见 [03 - 使用与排错](docs/03-使用与排错.md)。

### 路径 B：自己编译（把源码推到自己的 GitHub 仓库）

1. 在 GitHub 新建一个空仓库（**不要**勾选 `Add a README file`，避免冲突文件）。
2. 把 `android` 目录**里面的内容**推上去，三种方式任选：
   - **网页上传**：进入空仓库 → `Add file` → `Upload files`，把内容拖进上传区。⚠️ 必须包含隐藏目录 `.github`，否则自动编译不会运行。
   - **GitHub Desktop**：`File` → `Add local repository` → 选择 `android` 目录 → `Publish repository`。
   - **git 命令行**：
     ```bash
     cd <android 目录>
     git init
     git add .
     git commit -m "Initial commit"
     git branch -M main
     git remote add origin https://github.com/<你的用户名>/<仓库名>.git
     git push -u origin main
     ```
3. 推送后 GitHub Actions 自动开始编译（可在 `Actions` 标签页看进度）。
4. 编译完成后，按「路径 A」第 1 步下载 APK。

> ⚠️ 无论用哪种方式，都必须确保 `.github/workflows/build-apk.yml` 上传成功，否则不会有自动编译。完整流程与「构建失败怎么办」见 [01 - 编译 APK](docs/01-编译APK.md)。

---

## 配置项说明

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| 认证服务器 | `172.16.80.3` | 校园网关地址 |
| 端口 | `801` | 认证端口 |
| 上网账号 | 空 | 学号 / 手机号，**不带**运营商后缀 |
| 运营商 | 校园用户 | 校园用户（无后缀）/ 移动 `@yd` / 电信 `@dx` / 联通 `@lt` |
| 密码 | 空 | 上网密码 |
| 自动检查 | 开 | 是否启用周期任务 |
| 检查间隔 | 30 分钟 | 可选 15 / 30 / 60 / 120 分钟（**WorkManager 系统最小 15 分钟**） |
| 仅在指定 WiFi 下生效 | 空 | SSID 逗号分隔；空 = 不限；**读取 SSID 需要定位权限**（Android 10+） |
| 常驻通知保活 | 关 | 前台服务保活，**国产 ROM 建议开启** |

---

## 后台保活（国产 ROM 必读）

安卓系统会在你退到桌面、锁屏或内存紧张时冻结甚至杀掉后台 App，国产 ROM 尤为激进。想让自动登录真正生效，这四件事必须做：

1. **自启动白名单**：允许本 App 自启动。
2. **省电策略**：设为「无限制 / 不优化」，并加入电池优化白名单（App 内「打开电池优化设置」按钮可直达）。
3. **允许通知**：Android 13 及以上必须授权通知权限，否则常驻通知起不来。
4. **最近任务锁定**：在多任务界面给本 App 加锁，防止被一键清理掉。

各品牌（小米 / 华为 / OPPO / vivo 等）的具体设置路径见 [02 - 国产 ROM 保活指引](docs/02-国产ROM保活指引.md)。

---

## 常见问题

| 问题 | 一句话答案 |
| --- | --- |
| 一直显示「网关不可达」 | 当前不在校园网内，或网关地址填错了（默认 `172.16.80.3`） |
| 登录失败 / 提示密码错误 | 多半是运营商选错了：校园用户**不要**加后缀，移动 `@yd`、电信 `@dx`、联通 `@lt` |
| 显示已在线却打不开网页 | 会话可能未真正通网，手动断开 WiFi 重连，或点一次「立即登录」 |
| App 过一会儿就不动了 | 保活没配好，按 [02 - 国产 ROM 保活指引](docs/02-国产ROM保活指引.md) 配自启动与省电白名单 |
| 改了配置没生效 | 改完必须点「保存配置」，再点一次「立即登录」 |
| 日志在哪里看 | App 主界面底部的「运行日志」区域 |

更多问题（含每个配置项的含义）见 [03 - 使用与排错](docs/03-使用与排错.md)。

---

## 安全说明

- **明文 HTTP**：Dr.COM 认证全程走 HTTP，没有 TLS，链路可被监听或抓包，请勿在不可信网络下使用。
- **密码存储**：密码保存在 App 私有目录的 `SharedPreferences` 中，**未额外加密**；manifest 已设 `android:allowBackup="false"`，其它 App 无法读取。
- **正式签名**：发布的是 release 构建，由 PKCS12 正式密钥库签名（v1 + v2 + v3 同时启用）；密钥材料只存在于 GitHub Actions Secrets，仓库内不含任何密钥文件。
- **仓库无内置凭据**：本仓库不含任何真实账号、密码或学号，使用者需自行填写自己的凭据。

## 免责声明

本项目**仅供个人学习，以及为自有账号做正常的校园网上网认证**使用。

- 请遵守所在学校的网络管理规定。
- **不得**用于任何未授权用途，包括但不限于：使用他人账号、批量认证、规避学校网络管理策略。
- 使用本项目的风险由使用者自行承担。

---

## 文档索引

| 文档 | 内容 | 什么时候看 |
| --- | --- | --- |
| [01 - 编译 APK](docs/01-编译APK.md) | 怎么让 GitHub 帮你打包、怎么下载、构建失败怎么办 | 走「路径 B」时 |
| [02 - 国产 ROM 保活指引](docs/02-国产ROM保活指引.md) | 小米 / 华为 / OPPO / vivo 等机型的后台保活设置（**最重要**） | 装好 App 之后的必做一步 |
| [03 - 使用与排错](docs/03-使用与排错.md) | 界面逐项说明、运营商怎么选、登录失败怎么查 | 用起来之后遇到问题 |

## 版本记录

| 版本 | 说明 |
| --- | --- |
| `v1.0` | 首个版本（`versionCode 1`）。含周期自动检查、开机自启、前台服务保活、SSID 过滤、内置状态面板与日志 |

> 本版本为初始发布，目前尚无后续补丁版本。
