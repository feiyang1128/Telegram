# Fork 自动构建与更新

上游：`DrKLO/Telegram`；发布仓库：`feiyang1128/Telegram`。

## 首次配置

1. 在 https://my.telegram.org 的 API development tools 申请 API ID、API Hash。
2. 本地安装 JDK 17，重开 PowerShell，运行 `powershell -ExecutionPolicy Bypass -File automation/create-signing-key.ps1`。脚本在用户目录生成 `feiyang-release.jks`，不会覆盖已有文件。别名为 `feiyang`。备份证书及密码，不要提交证书。

   没有 Java 时，可运行 `powershell -ExecutionPolicy Bypass -File automation/create-signing-openssl.ps1`，使用 Git 自带的 OpenSSL 生成 PKCS12 证书。证书和四项 Secrets 存在被 Git 忽略且受访问权限限制的 `.signing/` 目录；自动构建兼容 JKS 和 PKCS12。当前已生成的备份位于 `D:\Desktop\tg\signing`，不要重复生成。

   无需显示秘密值，运行 `powershell -ExecutionPolicy Bypass -File automation/copy-signing-secret.ps1 -Name SIGNING_KEYSTORE_BASE64` 可复制指定 Secret 到剪贴板，其余三项更换名称即可。所有四项值也可从私密备份中的 `github-secrets.json` 读取。
3. 在仓库 Settings → Secrets and variables → Actions → Secrets 新建：

| 名称 | 内容 |
| --- | --- |
| TELEGRAM_API_ID | API ID |
| TELEGRAM_API_HASH | API Hash |
| SIGNING_KEYSTORE_BASE64 | 签名文件的 Base64 |
| SIGNING_KEY_ALIAS | feiyang |
| SIGNING_STORE_PASSWORD | 密钥库密码 |
| SIGNING_KEY_PASSWORD | 密钥密码 |
| FIREBASE_GOOGLE_SERVICES_BASE64 | 可选：你自己的 google-services.json 的 Base64 |

在 PowerShell 复制签名文件 Base64：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $env:USERPROFILE 'feiyang-release.jks'))) | Set-Clipboard
```

4. 同页面的 Variables 可设置：

| 名称 | 默认值 | 含义 |
| --- | --- | --- |
| APP_PACKAGE | io.github.feiyang.messenger | 基础包名，最终包名自动加 .web |
| ENABLE_PASSKEYS | false | 设置 true 保留 Passkey 入口；实际认证是否可用需实测 |

Passkey 代码保留。自定义包名和签名不属于 Telegram 官方身份；能否注册或登录取决于服务端和 Android 凭据校验，不会伪造官方身份。可先设置 ENABLE_PASSKEYS=true 测试，失败时改回 false；正常手机号登录不依赖该功能。

Firebase 可在 https://console.firebase.google.com 创建项目，注册与最终包名完全一致的 Android 应用（默认 `io.github.feiyang.messenger.web`），下载 google-services.json，将其 Base64 存入上述 Secret。未提供时跳过此模块的 Google Services 插件，FCM 推送不可用，后台消息到达情况需实测。地图、Google 登录等其他绑定官方身份的集成也未保证可用。

5. 提交并推送这些改动，在 GitHub Actions 页面启用工作流，选择 **Build fork release → Run workflow**。首次构建必须实际成功后，才能视为流程已验证。

## 自动更新流程

master 每次推送都会触发检查，也每 6 小时检查官方 master。根据上游提交、仓库应用改动、构建脚本和公开构建参数计算指纹；只有最新正式版本已包含相同内容且存在 APK 时才跳过。上游代码变化而版本号不变，也会发布新修订。也可手动输入官方分支、标签或提交。Secrets 变化不计入公开指纹，需要提高最低修订号来触发重新发布。

维护原则：优先适配官方最新版并保留自动跟进能力。遇到上游重构时迁移集成与定制改动，更新 `automation/upstream-base.txt` 并验证构建；不要默认固定旧版来绕过适配。

在线播放定制：当前读取位置附近的缺失分块可跳过请求间隔，每批最多 4 个请求；其他预取仍使用间隔控制。预取目标按媒体码率和实测网速计算，范围为 512 KiB–8 MiB；拖动后保留缓存与新窗口内的请求，取消无用的旧请求。普通远程视频的起播缓冲为快网 250 ms、慢网 750 ms；卡顿恢复从 2 秒逐步增加至最多 6 秒，间隔超过 60 秒后重新计数。本地文件、直播及特殊下载分支保留原有行为。`automation/tests/StreamingPolicyTest.java` 可用 JDK 单独编译运行，Actions 会在完整编译前执行该回归检查；实际出画时间和卡顿率仍需真机验证。

流程依次拉取源码、严格应用补丁、初始化子模块、注入私密配置、编译 arm64 Standalone APK、验证签名及版本号并发布 Release。补丁冲突、缺少配置或构建失败都会终止，旧 Release 保留。不会自动猜测如何修复上游重构。

工作流在临时 source 目录构建，**不会自动覆盖 Fork 的 master 源码**。Release 记录官方提交、自动化提交、子模块版本，并附公开补丁和更新器源码；完整构建配置在本仓库。读取这两份提交及补丁可定位对应源代码。API 凭据和证书只在构建时注入，不放入发布附件。

版本名称完全沿用官方 APP_VERSION_NAME，不添加 fork 后缀。Android versionCode 为 `(官方 APP_VERSION_CODE × 100 + 定制修订号) × 10 + 9`，保留 Standalone afat 的末位规则。`automation/revision.txt` 是最低修订号，范围 0–99；实际修订号根据已有 Releases 自动递增，不需要每次手动修改。例：官方 13.2.1 / 7291、修订号 1，对应显示版本 13.2.1、内部版本 7291019，高于旧规则的 72919。内容变化时自动选择高于已有版本的修订号；内容相同时跳过。达到 99 或指定的旧上游无法产生更高版本时明确报错，不覆盖旧 Release。

Release 标签为 `fork-<versionCode>`，APK 为 `fork-<versionCode>-arm64.apk`，手机更新器继续按内部版本号比较更新。同一包名始终使用同一证书，不要降低版本号或更换签名。切换到新规则后，旧规则 APK 无法直接覆盖安装新规则 APK。

手机端通过公开 GitHub Releases API 查询最新正式 Release，最多每 1 小时自动检查一次。发现更大版本号及匹配 APK 后提示用户，在浏览器下载，由系统执行安装并验证签名。它不会静默安装，也不依赖在 App 中保存 GitHub Token。仓库和 Releases 需要公开；GitHub 无法访问或 API 限流时，自动检查静默失败，手动检查显示错误，不影响聊天。

默认只支持 arm64；其他架构设备不要安装。Telegram 原生构建体积大，GitHub 托管 runner 可能遇到磁盘、内存或构建时长限制；首次运行后需要根据真实日志调整 runner 和上游工具链版本。

Gradle 依赖缓存仅包含 `~/.gradle/caches/modules-2` 和 `~/.gradle/wrapper/dists`，复用已下载的 Maven 依赖和 Gradle 发行包。缓存键按操作系统、JDK 17 和构建配置计算；配置变更时可恢复旧依赖并下载缺少的部分。首次成功构建会保存缓存，后续构建才可复用；缓存可能被 GitHub 清理。项目构建输出、编译类、配置缓存和签名资料不在缓存范围内；APK 仍需重新编译、签名和验证。

## 维护补丁

C/C++ 编译使用 `ccache`，通过 CMake 的 C/C++ compiler launcher 环境变量启用。缓存只包含原生编译缓存，不保存 APK、Java 编译输出、签名文件或 Gradle 项目输出。缓存上限 2 GB，按系统、架构、NDK 和 CMake 版本隔离；每次成功编译后保存新快照，发布失败也不会丢失已保存的编译缓存。保留默认源码、头文件、参数和工作目录校验，编译器按内容校验，不启用宽松匹配。首次构建建立缓存，后续构建可在 `Show native compiler cache statistics` 步骤查看命中率；链接、压缩和签名仍需执行。若需废弃旧缓存，递增工作流缓存键中的 `arm64-v1` 后缀。

应用源码是发布的权威来源，包括 `TMessagesProj/src/main/java/org/telegram/messenger/ForkUpdater.java`。工作流以 `automation/upstream-base.txt` 指定的上游基线，对当前提交自动生成完整源码补丁，再应用到拉取的官方源码并作为 Release 附件保存。不再读取历史副本 `automation/patches/customizations.patch` 或 `automation/ForkUpdater.java`，无需同步它们。构建遇到补丁冲突会报错，不会忽略源码改动。

上游重构后需要人工迁移源码改动并检查每项功能；如果在本仓库合并新的上游基线，需要同步更新 `automation/upstream-base.txt`。补丁应用成功、编译成功不能代替实机验证。
