# Dr.COM 校园网自动登录（安卓版）

这是**安卓手机上的校园网自动登录 App**。装到手机上以后，只要连着校园 WiFi，它就会在后台定时检查你有没有掉线，一旦掉线就自动帮你重新登录，不用再手动打开认证页面。

它和你电脑上装的 Windows 版用的是**同一套 Dr.COM 协议**，两边可以同时用、互不干扰：

- 查在线：`GET http://172.16.80.3/drcom/chkstatus?callback=cb&jsVersion=4.X`
- 登录：`GET http://172.16.80.3:801/eportal/portal/login?...`

> **给完全不懂技术的同学**：你不需要会写代码，也不需要在自己电脑上装任何开发工具。跟着下面「快速开始」的 5 步做完，就能把 App 装到手机上。

---

## 快速开始（5 步）

### 第 1 步：把 `android` 目录里的内容传到一个 GitHub 仓库

GitHub 是一个免费代码托管网站，它提供**免费的自动编译服务**：你把源码传上去，它帮你在云端打包成能装在手机上的 App（APK 文件）。

三种方式任选一种，**推荐方式 A（网页上传，什么都不用装）**。

#### 方式 A：网页上传（最省事，不用装任何东西）

1. 打开 <https://github.com/new> 新建一个仓库。
   - **Repository name**（仓库名）随便填，比如 `drcom-autologin-android`。
   - **Public（公开）或 Private（私有）都可以**。
   - ⚠️ **不要**勾选 `Add a README file`（本工程自带 README，勾了会多出一个冲突文件）。
2. 点 `Create repository`。
3. 进入这个新建的空仓库，点 `Add file` → `Upload files`。
4. **把 `android` 目录里的所有内容拖进网页的上传区域**。
   - ⚠️ 拖的是 `android` 目录**里面的内容**，不是 `android` 这个文件夹本身。
   - ⚠️ 一定要包含 `.github` 这个目录（它是隐藏目录，看不到就打开 Windows 资源管理器 → `查看` → 勾选 `隐藏的项目`）。**漏了它，自动编译就不会运行。**
5. 页面下方填写 commit message（随便写，比如 `first commit`），点 `Commit changes`。

#### 方式 B：GitHub Desktop（图形化客户端）

1. 下载安装 [GitHub Desktop](https://desktop.github.com/) 并登录你的 GitHub 账号。
2. 菜单 `File` → `Add local repository`…，选择本机的 `android` 目录。
   - 如果提示「这不是一个 Git 仓库」，选择 `create a repository` 即可。
3. 点右上角 `Publish repository`，确认仓库名 → 发布。
4. 注意：发布时**不要**勾选 `Keep this code private` 之外的任何「忽略文件」选项，否则 `.github` 可能被漏掉。

#### 方式 C：git 命令行（会用命令行的同学）

```bash
cd D:\Student_Workstation\Using_Workstation\campus-network-service\android
git init
git add .
git commit -m "Initial commit"
git branch -M main
git remote add origin https://github.com/<你的用户名>/<仓库名>.git
git push -u origin main
```

> ⚠️ **再次提醒**：无论用哪种方式，都必须确保 `.github/workflows/build-apk.yml` 被上传成功，否则 GitHub 不会自动编译。

### 第 2 步：等 GitHub 自动编译

上传完成后：

1. 打开你仓库页面顶部的 `Actions` 标签。
2. 你会看到一条名为 **`Build APK`** 的任务正在跑（黄色圆点转圈 = 运行中）。
3. 首次编译大约需要 **3-5 分钟**（第一次要下载依赖，会慢一些）。
4. 变成绿色对勾 ✅ 就表示成功了。

如果 Actions 页面是空的、没有任务在跑，说明 `.github` 目录没传上去，请回到第 1 步重传。
如果编译失败（红色叉 ❌），请看 [编译 APK 文档](docs/01-编译APK.md) 的「构建失败怎么办」章节。

### 第 3 步：下载编译好的 App

1. 在 `Actions` 页面点进刚才那次 `Build APK` 任务。
2. 拉到页面**最底部**，找到 `Artifacts` 区域。
3. 点击 **`DrcomAutoLogin-APK`** 下载（会得到一个 zip 压缩包）。
4. 解压这个 zip，里面就是安装包 **`app-debug.apk`**。

> 提示：这个下载链接有效期大约 90 天，过期后重新点一次 `Run workflow` 再编译一次即可。

### 第 4 步：传到手机并安装

1. 用数据线、微信文件传输助手、QQ、网盘等**任意方式**把 `app-debug.apk` 传到手机。
2. 在手机上点开这个 apk 文件。
3. 系统会提示「**不允许安装未知来源应用**」之类的信息，按提示进入设置，允许你当前用的文件管理器 / 浏览器「**安装未知应用**」。
   - 大致路径：`设置` → `应用` → `特殊应用权限`（不同品牌叫法不同）→ `安装未知应用` → 找到你用来打开 apk 的那个 App → 打开开关。
4. 返回继续安装。如果弹「**Play 保护机制/安全检测**」提示，选择「仍要安装」。
5. 安装完成后，桌面/应用列表会出现一个蓝色图标，名字是 **`Dr.COM 校园网自动登录`**。

### 第 5 步：打开 App 填配置

1. 打开 App。**第一次打开会弹通知权限**（Android 13 及以上），点「**允许**」。
2. 在「账号配置」区域填写：
   - **认证服务器**：默认 `172.16.80.3`（不用改）
   - **端口**：默认 `801`（不用改）
   - **上网账号**：你的学号或手机号（**不要**自己加 `@yd` 之类后缀）
   - **运营商**：按你的账号类型选（校园用户 / 移动 @yd / 电信 @dx / 联通 @lt），**选错会登录失败**
   - **密码**：你的上网密码
3. 点「**保存配置**」。
4. 点「**立即登录**」验证一下能不能登上去，看「连接状态」区域是否变成「已在线」。
5. 打开「**自动检查**」开关，检查间隔选 `30 分钟`（或你想要的值）。
6. 打开「**常驻通知保活**」开关，通知栏会出现一条「Dr.COM 自动登录」的常驻通知（这是正常的，是它在守护）。
7. 点「**打开电池优化设置**」，把本 App 设为「**不优化**」。
8. **最后一步（国产手机必做）**：按你的手机品牌，照着 [国产 ROM 保活指引](docs/02-国产ROM保活指引.md) 把自启动、省电策略配好。**不做这一步，App 过一会儿就会被系统杀掉，自动化就失效了。**

---

## 目录结构说明

```
android/
├── .github/workflows/build-apk.yml   ← 自动编译脚本（最重要，别漏传）
├── app/
│   ├── build.gradle.kts              ← App 编译配置（包名、版本号）
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml        ← 权限声明（网络、开机自启、通知、定位）
│       ├── java/com/drcom/autologin/  ← 程序源码
│       └── res/                       ← 界面文字、颜色、图标
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── .gitignore
├── README.md                          ← 本文件
└── docs/                              ← 详细文档
```

## 关键信息速查

| 项目 | 值 |
| --- | --- |
| 应用名 | `Dr.COM 校园网自动登录` |
| 包名 | `com.drcom.autologin` |
| 版本 | `1.0`（versionCode 1） |
| 系统要求 | Android 8.0（API 26）及以上 |
| 编译产物路径 | `app/build/outputs/apk/debug/app-debug.apk` |
| CI artifact 名称 | `DrcomAutoLogin-APK` |
| 自动编译触发条件 | push 到 `main` 或 `master` 分支（也可手动 `Run workflow`） |

---

## 详细文档

| 文档 | 内容 | 什么时候看 |
| --- | --- | --- |
| [01 - 编译 APK](docs/01-编译APK.md) | 怎么让 GitHub 帮你打包、怎么下载、构建失败怎么办 | 第 2、3 步卡住时 |
| [02 - 国产 ROM 保活指引](docs/02-国产ROM保活指引.md) | 小米/华为/OPPO/vivo 等手机的后台保活设置（**最重要**） | 装好 App 之后的必做一步 |
| [03 - 使用与排错](docs/03-使用与排错.md) | 界面每个选项什么意思、登录失败怎么查 | 用起来之后遇到问题 |

## 安全说明

- 你的账号密码只保存在**手机 App 自己的私有目录**里，其他 App 读不到，本工程里也**没有内置任何人的账号密码**。
- 如果你把这个 App 分享给别人，别人需要自己填自己的账号密码。
- 更详细的安全说明见 [03 - 使用与排错](docs/03-使用与排错.md) 的「安全说明」章节。
