Github上传工具 (GitHub Upload Tool)

项目简介

Git上传工具是一款运行于 Android 平台的实用工具应用，用于将本地指定目录内的所有文件（包含子目录）通过 GitHub REST API 递归上传至远程 GitHub 仓库的指定分支中。工具不依赖 JGit 或其他 Git 客户端库，直接调用 GitHub 官方 REST API 完成 Blob、Tree、Commit、Ref 的创建与更新，实现与 git push 等效的提交推送效果。

---

当前版本

项目 信息
版本号 1.0
versionCode 100
minSdk 30（Android 11）
targetSdk 36
compileSdk 36
语言 Kotlin
UI 框架 Material Design 3 + ViewBinding
最低运行环境 Android 11.0 及以上

当前处于 1.0 正式版，核心上传链路已实现并通过基本验证。

---

主要运用场景

1. 移动端代码/文件备份
      在 Android 设备上编写或收集的代码、文档、配置文件等，无需电脑即可直接推送到 GitHub 仓库。
2. AndroidIDE 等移动开发环境协作
      在 AndroidIDE 等移动端 IDE 中完成项目修改后，可快速将整个项目目录上传至远程仓库，实现版本留存与协作。
3. 批量文件归档
      将设备上某个目录（如笔记、日志、数据导出目录）整体打包提交至 GitHub，借助 Git 的版本管理能力保留历史快照。
4. 无 Git 环境下的应急推送
      当设备端无法安装或运行完整 Git 客户端时，本工具提供轻量级的上传通道。

---

功能清单

已实现功能

功能 说明
递归上传 自动遍历所选目录及全部子目录，逐一上传文件
SAF 目录选择 通过系统文档树选择器选取目录，支持持久化权限
文件系统路径支持 可直接输入本地绝对路径作为上传源
GitHub 仓库解析 自动从 URL 提取 owner/repo，仅支持 https://github.com/ 格式
分支管理 目标分支不存在时自动基于默认分支创建；已存在则更新
强制推送 支持 force=true 更新远程分支引用
空目录占位 空目录自动生成 .gitkeep 文件
大文件保护 单个文件超过 80MB 时中止并提示，防止 API 调用失败
Token 加密存储 Git PAT 使用 Android Keystore（AES/GCM）加密保存
配置持久化 用户名、仓库 URL、本地路径、分支、强推开关自动保存
实时执行日志 显示上传进度、API 交互状态与错误信息
密钥脱敏 日志中仓库 URL 内的凭据自动替换为 ***

暂未实现 / 已知限制

· 仅支持 github.com，不支持 Gitee、GitLab 等平台
· 仅支持 HTTPS URL，不支持 SSH
· 单个文件大小上限 80MB（GitHub API 限制）
· 不支持增量上传——每次上传均重新创建完整 Tree 和 Commit
· 不支持 .gitignore 规则
· 无后台服务，上传过程中需保持应用在前台
· 不支持并行上传多文件（当前为串行逐文件创建 Blob）

---

项目结构

```
app/
├── src/main/
│   ├── kotlin/com/githubupload/mt/
│   │   ├── MainActivity.kt      # UI 入口与交互逻辑
│   │   ├── GitUploader.kt       # 上传引擎核心
│   │   └── SecurePrefs.kt       # Token 加密存储
│   ├── res/
│   │   ├── layout/activity_main.xml
│   │   ├── drawable/            # 图标资源
│   │   ├── values/              # 主题与字符串资源
│   │   └── xml/                 # 备份规则
│   └── AndroidManifest.xml
├── build.gradle                  # Groovy DSL 构建脚本
├── build.gradle.kts              # Kotlin DSL 构建脚本（备用）
└── proguard-rules.pro
```

开发要求

环境依赖

依赖 版本/要求
Android Studio 任意支持 AGP 8.x 的版本
JDK 17
Kotlin 2.x（编译器目标 JVM 17）
Gradle Plugin com.android.application
compileSdk 36
minSdk 30

主要依赖库

```kotlin
implementation("androidx.appcompat:appcompat:1.7.1")
implementation("com.google.android.material:material:1.13.0")
```

构建步骤

```bash
# 使用 Gradle Wrapper 构建 Debug APK
./gradlew assembleDebug

# 构建 Release APK（需配置签名）
./gradlew assembleRelease
```

权限申请

应用需要在 AndroidManifest 中声明以下权限：

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />
```

· INTERNET：用于调用 GitHub REST API
· MANAGE_EXTERNAL_STORAGE：用于读取本地文件系统路径中的文件（非 SAF 场景）。首次点击“一键上传”时若未授权，应用会跳转至系统授权页面。

---

工具运行逻辑

整体流程图

```
用户配置输入（用户名/Token/仓库URL/本地路径/分支）
        ↓
保存配置（Token 经 Keystore 加密）
        ↓
权限检查（MANAGE_EXTERNAL_STORAGE）
        ↓
收集本地文件（递归遍历，跳过 .git）
        ↓
验证 GitHub 仓库存在性（GET /repos/{owner}/{repo}）
        ↓
检查目标分支是否存在（GET /git/ref/heads/{branch}）
        ↓
    ┌── 分支存在 → 获取当前 HEAD commit → 获取 base_tree
    └── 分支不存在 → 尝试获取默认分支 HEAD → 获取 base_tree
        ↓
逐文件创建 Git Blob（POST /git/blobs）
        ↓
创建 Git Tree（POST /git/trees，可携带 base_tree）
        ↓
创建 Git Commit（POST /git/commits，携带 parents）
        ↓
    ┌── 分支不存在 → 创建新 Ref（POST /git/refs）
    └── 分支已存在 → 更新 Ref（PATCH /git/refs/heads/{branch}，force 可选）
        ↓
上传完成，显示结果统计（文件数、字节数）
```

核心类说明

类名 职责
MainActivity UI 入口，负责配置读写、权限管理、目录选择、线程调度与日志展示
GitUploader 上传引擎，实现文件收集、API 调用、Tree/Commit 构建的完整逻辑
SecurePrefs Token 安全存储，使用 Android Keystore AES/GCM 加密
GitHubApi GitHub REST API 内部封装类，处理 HTTP 请求/响应与错误

数据流细节

1. 源数据获取
   · SAF 模式：通过 DocumentsContract 递归查询子文档
   · 文件系统模式：通过 File.listFiles() 递归遍历
   · 两种模式均跳过 .git 目录
2. 文件读取
      采用流式读取，限制单文件不超过 80MB。若文件大小未知，采用动态扩容的 ByteArrayOutputStream，超限即中止。
3. API 调用序列
      每个文件先调用 POST /git/blobs 获取 blob SHA，全部完成后调用 POST /git/trees 一次性创建 Tree，随后 POST /git/commits 创建 Commit，最后根据分支是否存在选择 POST /git/refs 或 PATCH /git/refs/heads/{branch}。
4. 错误处理
      捕获所有异常，提取最内层 cause.message 作为错误信息输出到日志。API 层区分 404（分支不存在）与其他错误码。
5. 线程模型
      所有网络操作在单线程 ExecutorService 中执行，UI 更新通过 runOnUiThread 回主线程。上传过程中禁用相关按钮防止重复触发。

---

使用方法

1. 打开应用，填写 Git 用户名、Git 令牌（PAT）、远程仓库 URL
2. 点击「选择目录」选取要上传的本地目录，或手动输入路径
3. 可选：修改远程分支名（默认 main）、勾选强制推送
4. 点击「一键上传」，观察执行日志
5. 首次使用需授予「所有文件访问权限」

令牌（PAT）获取

访问 GitHub → Settings → Developer settings → Personal access tokens → Generate new token，勾选 repo 权限即可。

---

免责声明

1. 数据安全风险
      本工具直接操作远程 GitHub 仓库，强制推送功能可能覆盖远程分支历史。使用前请确认目标分支的重要性，必要时先创建备份分支。因误操作导致的数据丢失，开发者不承担任何责任。
2. 令牌保管责任
      用户的 Git 令牌（PAT）以加密形式存储在设备本地，但加密强度依赖于设备 Keystore 的完整性。若设备已获取 Root 权限或存在其他安全漏洞，令牌可能面临泄露风险。用户应自行妥善保管令牌，并遵循最小权限原则分配令牌权限范围。
3. 网络与平台限制
      本工具依赖 GitHub 官方 REST API。GitHub 的 API 速率限制、服务中断、政策变更等因素可能导致上传失败，开发者不对由此引发的数据问题负责。
4. 合法使用声明
      本工具仅供用户上传自己拥有合法权限的文件至自己拥有合法权限的仓库。严禁使用本工具向他人仓库注入恶意代码、侵犯他人知识产权或从事任何违反 GitHub 服务条款及当地法律法规的活动。由此产生的一切后果由使用者自行承担。
5. 软件按“现状”提供
      本工具不提供任何明示或暗示的担保，包括但不限于适销性、特定用途适用性及非侵权性担保。开发者不对使用或无法使用本工具所导致的任何直接、间接、偶然、特殊或后果性损害承担责任。
6. 用户自行验证
      建议用户在上传重要数据前，先在测试仓库中验证工具行为是否符合预期。上传完成后，应登录 GitHub 网页端确认提交结果。

---