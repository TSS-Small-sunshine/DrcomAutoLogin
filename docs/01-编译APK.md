# 01 - 怎么编译出 APK

> ⚠️ **适用范围**：本项目默认配置与实测环境为**福建农业职业技术学院**校园网；其它学校需自行确认网关地址与协议。

这份文档讲的是：怎么把一个源码工程变成手机上能装的 **APK 安装包**。

读完你会知道：

1. 为什么不用在自己电脑上装一堆开发工具
2. GitHub 是怎么帮你自动打包的
3. 怎么手动点一下重新打包
4. 怎么看编译进度、怎么下载成果
5. **编译失败了怎么办**
6. 以后改了代码怎么更新 App

---

## 一、为什么用 GitHub Actions 编译

手机上要装的东西叫 APK，它必须由 Android 官方工具链打包出来。这套工具链很大：

| 需要的东西 | 大概体积 |
| --- | --- |
| Android SDK（含 platform 34、build-tools） | 3-5 GB |
| JDK 17 | 约 300 MB |
| Gradle 8.9 + 依赖缓存 | 1 GB 以上 |

也就是说，为了打一个几 MB 的 App，你电脑上要装 5 GB 左右的东西，还得配环境变量、同意 SDK 许可协议——对普通用户来说非常折腾。

**GitHub Actions 提供免费的云端编译机器**：你只要把源码传上去，GitHub 会在它自己的服务器上把上面那 5 GB 工具链装好、编译、然后把 APK 给你下载。你电脑上**一个字节都不用装**。

> 本工程就是按这个思路设计的：仓库里**故意不放 `gradlew`**（Gradle 包装器），也不用你本机装 Gradle——编译机上的 Gradle 8.9 由 GitHub 官方插件 `gradle/actions/setup-gradle@v4` 提供。

---

## 二、自动编译是怎么跑的

工程里有一个文件：`.github/workflows/build-apk.yml`，它告诉 GitHub 该干什么。内容分 3 部分：

### 1）什么时候触发

```yaml
on:
  push:
    branches: [ main, master ]
  workflow_dispatch:
```

- **push 到 `main` 或 `master` 分支**：你每上传一次代码，就自动编译一次。
- **`workflow_dispatch`**：允许你在网页上手动点按钮触发（见下一节）。

### 2）用什么机器、什么工具

```yaml
runs-on: ubuntu-latest
```

用一台全新的 Ubuntu 云主机，每次都是干净环境。接着按顺序做 9 件事：

| 步骤 | 作用 |
| --- | --- |
| `actions/checkout@v4` | 把你的仓库代码下载到编译机上 |
| `Set up JDK 17` → `actions/setup-java@v4`（temurin, 17） | 装 JDK 17（Android Gradle Plugin 8.5.2 要求至少 JDK 17） |
| `Set up Gradle` → `gradle/actions/setup-gradle@v4`（8.9） | 装 Gradle 8.9 并开启依赖缓存 |
| `Decode signing keystore` | 把 Secrets 里的密钥库（base64）解码成临时文件，供签名使用 |
| `Build unsigned release APK` | **真正开始编译**：`gradle assembleRelease --no-daemon --stacktrace`，产出**未签名**包 |
| `Sign APK with v1 + v2 + v3` | 先用 `zipalign` 对齐，再由 `apksigner` 显式以 v1 + v2 + v3 签名，输出 `DrcomAutoLogin-v<版本号>-debug.apk`（版本号由 `app/build.gradle.kts` 的 `versionName` 解析得到，如 `DrcomAutoLogin-v1.1-debug.apk`） |
| `Verify APK (signature + manifest)` | 打印 APK 的签名方案（`apksigner verify`）、检查 v1 签名文件是否齐全、打印包信息（`aapt2 dump badging`），确认产物正确 |
| `Upload APK artifact` | 把签名后的 `DrcomAutoLogin-v<版本号>-debug.apk` 上传为 artifact |
| `Publish Release (tag debug)` | 用 `gh release` 删除并重建 tag 为 `debug` 的 Release，附件即该 APK |

> `--no-daemon`：编译机只用一次，不需要常驻进程，关掉更快更省内存。
> `--stacktrace`：万一失败，日志里会带完整调用栈，方便排错。

### 3）编译结果怎么给你

```yaml
- name: Upload APK artifact
  uses: actions/upload-artifact@v4
  with:
    name: DrcomAutoLogin-APK
    path: app/build/outputs/apk/release/DrcomAutoLogin-v*-debug.apk
    if-no-files-found: error
```

编译成功后，把 `app/build/outputs/apk/release/DrcomAutoLogin-v<版本号>-debug.apk`（`path` 里写成 `DrcomAutoLogin-v*-debug.apk` 通配，文件名带 `versionName`）打包成一个名为 **`DrcomAutoLogin-APK`** 的 artifact（可以理解为「云端产物压缩包」）供你下载。

最后还有一步 `Publish Release (tag debug)`：它用 `gh release` 把同一个 `DrcomAutoLogin-v<版本号>-debug.apk` 挂到 tag 为 **`debug`** 的 Release 上（先删旧 Release 再重建，所以 Release 页面地址固定不变；但**附件文件名会随版本变化**），并带上 `--prerelease` 标记。也就是说 **Actions artifact 里解压出的文件，和 Releases 里的附件是同一个文件、同名同内容**。

`if-no-files-found: error` 的意思是：**如果没找到 apk 就直接报错**。这是故意的——防止编译其实失败了、却悄悄给你一个空的下载包。

---

## 三、怎么手动触发一次编译

自动编译只在 `push` 时跑。如果你想立刻重新编译一次（比如上次失败了、或者下载链接过期了）：

1. 打开你的 GitHub 仓库页面。
2. 点顶部菜单的 **`Actions`** 标签。
3. 左侧列表里点 **`Build APK`**（注意不是别的 workflow）。
4. 右上角有一个 **`Run workflow`** 下拉按钮，点它。
5. 确认分支是 **`main`**（如果你用的是 `master` 就选 `master`）。
6. 点绿色的 **`Run workflow`** 按钮。

几秒钟后刷新页面，列表里会出现一条新的运行记录，状态是**黄色圆点**（运行中）。

---

## 四、怎么看构建进度和日志

1. `Actions` → 点进某一次运行记录。
2. 页面中间会列出这次运行的所有步骤：

```
Set up job
✓ Checkout                    ← 下载你的代码
✓ Set up JDK 17               ← 装 JDK
✓ Set up Gradle               ← 装 Gradle
✓ Decode signing keystore     ← 解码签名密钥库
✓ Build unsigned release APK  ← 编译出未签名包（耗时最久的一步）
✓ Sign APK with v1 + v2 + v3  ← zipalign + apksigner 显式签 v1/v2/v3
✓ Verify APK (signature + manifest) ← 校验签名方案、v1 签名文件与包信息
✓ Upload APK artifact         ← 上传结果
✓ Publish Release (tag debug) ← 更新 tag 为 debug 的 Release
✓ Complete job
```

- **绿色对勾 ✅**：这一步成功。
- **黄色转圈 🟡**：这一步正在跑。
- **红色叉 ❌**：这一步失败，点它展开可以看到详细报错。

想看点开某一步的日志，直接点那一步的名字即可。排错时最需要看的是 **`Build unsigned release APK`** 这一步的输出；想要签名与 SDK 版本的证据，看 **`Verify APK (signature + manifest)`** 那一步。

---

## 五、最省事：直接从 Releases 下载

不想点开 `Actions` 页面翻产物，可以直接用 **Releases**：

1. 打开 <https://github.com/TSS-Small-sunshine/DrcomAutoLogin/releases>。
2. 找到标签为 **`debug`** 的 Release（标题是「Debug 构建（自动更新）」）。
3. 在 `Assets` 区域下载附件 **`DrcomAutoLogin-v<版本号>-debug.apk`**（如 `DrcomAutoLogin-v1.1-debug.apk`）。
4. 把 APK 传到手机安装（需按提示允许「安装未知应用」）。

关于这个 Release：

| 项 | 值 |
| --- | --- |
| tag | **`debug`**（固定不变） |
| 附件名 | **`DrcomAutoLogin-v<版本号>-debug.apk`**（带版本号，如 `DrcomAutoLogin-v1.1-debug.apk`）——**看版本号就知道是不是最新** |
| 更新方式 | 每次向 `main` / `master` 推送代码后，CI 自动删除并重建该 Release，**Release 页面地址（`/releases/tag/debug`）始终不变**，但**附件文件名会随版本变化** |
| 是否需要登录 GitHub | **不需要**，链接可直接分享给同学 |

> 注意：这里说的「Debug 版」指的是「给同学用的临时分发版本」，**签名仍然是 release 正式签名**（v1 + v2 + v3），不是 Android 默认的 debug 签名。

---

## 六、怎么下载 APK

1. 进入那次**成功（绿色 ✅）**的运行记录页面。
2. 拉到页面**最底部**，找到 **`Artifacts`** 区块。
3. 点 **`DrcomAutoLogin-APK`**。
4. 浏览器会下载一个 **zip 压缩包**。
5. 解压这个 zip，得到 **`DrcomAutoLogin-v<版本号>-debug.apk`** ——这就是能装到手机上的安装包。

> **artifact 里的文件名和 Releases 里的一样吗？**
> 一样。`Upload APK artifact` 和 `Publish Release (tag debug)` 用的是同一个文件 `DrcomAutoLogin-v<版本号>-debug.apk`：Actions 的 artifact 里解压出来是它，Releases 里 `debug` 那个 Release 的附件也是它，**同名、同内容**（可以互相校验 `sha256`）。所以从哪边下载都行，装出来的 App 完全一致。

> **这是正式签名的包吗？**
> 是。CI 产出的是 **release 正式签名**包，同时启用了 **v1（JAR）+ v2 + v3** 三种签名方案。第三方来源侧载安装时 v1 签名是兼容性兜底——只签 v2 的包在部分国产 ROM 上会被包解析器拒绝，报「解析软件包时出现问题 / packageInfo is null」。
>
> **v1 签名是怎么保证的？**
> 因为 **AGP 在 `minSdk >= 24` 时会忽略 `enableV1Signing`**（AGP 认为 v1 冗余），所以 CI 不依赖 Gradle 的签名配置：先由 `gradle assembleRelease` 产出**未签名**包 → `zipalign` 对齐 → 再由 `apksigner` 显式以 `--v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true` 签名，产物即为 `DrcomAutoLogin-v<版本号>-debug.apk`。
>
> **为什么文件名是 `DrcomAutoLogin-v<版本号>-debug.apk` 而不是中文名？**
> 这个名字是 CI 在 **`Sign APK with v1 + v2 + v3`** 这一步用 `apksigner --out` 显式指定的，其中 `<版本号>` 从 `app/build.gradle.kts` 的 `versionName` 解析而来（Gradle 在 `app/build/outputs/apk/release/` 下产出的 `*-unsigned.apk` 只是签名前的中间产物，不会上传）。**带版本号是为了让你在下载页一眼分辨新旧包**：Release 页面地址不变，但每次重建附件名都会更新成当前的 `versionName`。安装到手机后显示的名称才是「Dr.COM 校园网自动登录」；这里的 `debug` 指的是「给同学用的临时分发版」，签名本身仍是 release 正式签名。

### 怎么确认这个 APK 签名正常

`Verify APK (signature + manifest)` 这一步会把下面这些证据打印到日志里，可以直接当证据看：

- **`apksigner verify --verbose --print-certs`**：列出签名方案，**v1 / v2 / v3 三项都应是 `true`**，并打印证书主体（`CN=TSS-Small-sunshine, OU=DrcomAutoLogin, ...`）。
- **v1 签名文件存在性检查**：脚本会逐个检查 APK 内是否含 `META-INF/MANIFEST.MF`、`META-INF/CERT.SF`、`META-INF/CERT.RSA`；**缺任意一个，这一步会直接失败**（红色 ❌），不会再产出一个「只有 v2/v3」的包。
- **`aapt2 dump badging`**：打印包名 `com.drcom.autologin` 与 `sdkVersion`（`minSdk`）/ `targetSdkVersion`。

再往下还有一行 `sha256`，是这次产物 APK 的校验值。

### 下载链接会过期

GitHub 的 artifact 默认**保留 90 天**，过期后链接失效。解决办法：回到 `Actions` 页面手动 `Run workflow` 重新编译一次，就会产生新的下载链接。

---

## 七、构建失败怎么办

红色 ❌ 时，先点进失败的那一步看日志。下面是几种最常见的情况。

### 情况 1：卡在 `Set up Gradle` 或 `Build unsigned release APK`，日志里有 `timeout` / `Connection timed out` / `Could not resolve`

**原因**：编译机在下载 Gradle 或 Android 依赖时网络超时。这是 GitHub 服务器到 Maven 仓库之间的偶发网络问题，不是你代码的问题。

**处理**：

1. 直接重新跑一次：`Actions` → `Build APK` → `Run workflow`。多数情况下第二次就好了。
2. 连续失败 3 次以上，再考虑换时间重试（海外机房高峰期会更慢）。

### 情况 2：`Could not find com.android.tools.build:gradle:8.5.2`

**原因**：和上面类似，依赖仓库没拉下来；也可能是有人把 `build.gradle.kts` 里的版本号改错了。

**处理**：

1. 确认 `build.gradle.kts` 里是 `id("com.android.application") version "8.5.2"`，不要手动改动。
2. 重跑一次 workflow。

### 情况 3：`Unsupported class file major version` / `Android Gradle plugin requires Java 17`

**原因**：JDK 版本不对。Android Gradle Plugin 8.5.2 需要 **JDK 17**。

**处理**：确认 workflow 里 `Set up JDK 17` 这一步存在且是 `java-version: '17'`。本工程已经配好了，不要改成别的版本。

### 情况 4：`Failed to install the following Android SDK packages` / `licenses` 相关报错

**原因**：Android SDK 组件下载或许可证未接受。

**处理**：

1. 先重跑一次（下载中断很常见）。
2. 持续失败时，可以在 `Build unsigned release APK` 步骤**之前**加一步显式安装 SDK 组件：

   ```yaml
   - name: Set up Android SDK
     uses: android-actions/setup-android@v3
   ```

   （`ubuntu-latest` 镜像本身就预装了 Android SDK，所以正常情况不需要这一步。）

### 情况 5：`No files were found with the provided path: app/build/outputs/apk/release/DrcomAutoLogin-v*-debug.apk`

**原因**：`Upload APK artifact` 报这个错，说明**签名那一步没产出 apk**（`Build unsigned release APK` 或 `Sign APK with v1 + v2 + v3` 的某一步先失败了）。往上翻，真正的错误通常在 `Build unsigned release APK` 那一步，一般是 Kotlin 语法错误或资源文件错误。

**处理**：

1. 点开 `Build unsigned release APK` 步骤，看日志里第一处 `e: ` 或 `error:` 开头的行（那才是根因）。
2. 如果你改过代码，把改动还原；如果没改过，直接重跑。

### 情况 6：`Actions` 页面根本没有 `Build APK` 这个 workflow

**原因**：`.github/workflows/build-apk.yml` 没有被上传到仓库。

**处理**：

1. 在你的仓库页面点开文件列表，确认能看到 `.github` 目录。
2. 看不到（GUI 服务器始终不显示隐藏目录，点进仓库 URL 后手动在地址栏加 `.github/workflows/build-apk.yml` 也能打开）→ 说明没传上去。
3. 重新上传一次：`Add file` → `Upload files` → 拖入时确保包含 `.github`（Windows 资源管理器需要先 `查看` → 勾选 `隐藏的项目`）。
4. 另外确认仓库的 `Settings` → `Actions` → `General` → `Actions permissions` 选择的是「允许所有 Actions」（默认就是允许）。

### 情况 7：`Permission denied` / workflow 显示需要审批

**原因**：某些仓库设置（比如来自 fork 的 PR）需要维护者手动批准才跑。

**处理**：在运行记录页面点 `Approve and run`。

---

## 八、改了代码怎么更新 App

流程和第一次完全一样：

1. 修改源码（在网页上直接编辑，或用 GitHub Desktop / git 命令行 push）。
2. `Commit changes` / `push` 到 `main` 分支。
3. 等 `Actions` 自动编译完成。
4. 下载新的 `DrcomAutoLogin-APK`，解压得到 `DrcomAutoLogin-v<版本号>-debug.apk`（版本号变大即确认为新包）。
5. 直接把新的 apk 覆盖安装到手机上。

**覆盖安装的数据保留情况**：

| 内容 | 覆盖安装后 |
| --- | --- |
| 账号、密码、服务器、端口、间隔等配置 | **保留**（release 包签名不变） |
| 日志文件 | **保留** |
| App 图标和名称 | 不变 |

也就是说，只要你没手动卸载，覆盖安装不会让你重新填一遍账号密码。

> ⚠️ 如果哪次编译换了签名（本工程不会），或者你先卸载再安装，配置就会清空——因为 App 的数据是跟着「包名 + 签名」走的。

### 更新后不用重新配保活吗

不用。自启动、省电策略、电池优化、通知权限这些都是**跟着包名走**的，覆盖安装后依然有效。

但是**「自启动」这一项**在部分 ROM 上（尤其是小米）覆盖安装后会被系统自动重置为关闭。所以更新完 App 之后，顺手去设置里看一眼自启动是否还开着（详见 [02 - 国产 ROM 保活指引](02-国产ROM保活指引.md)）。

---

## 九、常见疑问

**Q：编译要花钱吗？**
A：公开仓库（Public）的 Actions 完全免费不限额；私有仓库（Private）免费额度一般是每月 2000 分钟，本工程一次编译只花 3-5 分钟，日常更新完全够用。

**Q：一定要用 `main` 分支吗？**
A：workflow 里监听的是 `main` 和 `master` 两个分支，用哪个都行，但**必须**是这两个名字之一，叫 `dev` 之类的分支不会触发自动编译。

**Q：能一次编译出正式发布版吗？**
A：本工程**已经**是正式发布版：workflow 直接产出 **release 正式签名**的 APK。签名密钥（PKCS12 keystore）的密钥材料以 GitHub Secrets 形式存放，仓库里不含任何密钥文件；想换成自己的密钥，把 `SIGNING_KEYSTORE_BASE64` / `SIGNING_STORE_PASSWORD` / `SIGNING_KEY_ALIAS` / `SIGNING_KEY_PASSWORD` 这 4 个 Secrets 替换掉即可。

**Q：为什么编译好的 App 装的时候会被手机管家警告？**
A：因为它是正式签名 + 非应用商店来源，属于正常现象，选择「继续安装 / 仍要安装」即可。
