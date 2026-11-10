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

每 6 小时检查官方 master 的 APP_VERSION_CODE。当前没有额外要求上游必须创建 GitHub Release；官方只改代码而未提高版本号时不重复发布。也可手动输入官方分支、标签或提交。已存在的 fork 版本会跳过。

流程依次拉取源码、严格应用补丁、初始化子模块、注入私密配置、编译 arm64 Standalone APK、验证签名及版本号并发布 Release。补丁冲突、缺少配置或构建失败都会终止，旧 Release 保留。不会自动猜测如何修复上游重构。

工作流在临时 source 目录构建，**不会自动覆盖 Fork 的 master 源码**。Release 记录官方提交、自动化提交、子模块版本，并附公开补丁和更新器源码；完整构建配置在本仓库。读取这两份提交及补丁可定位对应源代码。API 凭据和证书只在构建时注入，不放入发布附件。

版本名称完全沿用官方 APP_VERSION_NAME，不添加 fork 后缀。Android versionCode 为 `(官方 APP_VERSION_CODE × 100 + 定制修订号) × 10 + 9`，保留 Standalone afat 的末位规则。修订号保存在 `automation/revision.txt`，范围 0–99；同一官方版本需要发布定制修改时，递增该文件并提交。例：官方 13.2.1 / 7291、修订号 1，对应显示版本 13.2.1、内部版本 7291019，高于旧规则的 72919。修订号不变时仍跳过已发布版本；官方版本提高时会自动发布，无需修改修订号，也无需修改上游拉取流程。不要在同一官方版本内降低修订号；达到 99 后需调整版本规则或等待上游版本提高。

Release 标签为 `fork-<versionCode>`，APK 为 `fork-<versionCode>-arm64.apk`，手机更新器继续按内部版本号比较更新。同一包名始终使用同一证书，不要降低版本号或更换签名。切换到新规则后，旧规则 APK 无法直接覆盖安装新规则 APK。

手机端通过公开 GitHub Releases API 查询最新正式 Release，最多每 6 小时自动检查一次。发现更大版本号及匹配 APK 后提示用户，在浏览器下载，由系统执行安装并验证签名。它不会静默安装，也不依赖在 App 中保存 GitHub Token。仓库和 Releases 需要公开；GitHub 无法访问或 API 限流时，自动检查静默失败，手动检查显示错误，不影响聊天。

默认只支持 arm64；其他架构设备不要安装。Telegram 原生构建体积大，GitHub 托管 runner 可能遇到磁盘、内存或构建时长限制；首次运行后需要根据真实日志调整 runner 和上游工具链版本。

Gradle 依赖缓存仅包含 `~/.gradle/caches/modules-2` 和 `~/.gradle/wrapper/dists`，复用已下载的 Maven 依赖和 Gradle 发行包。缓存键按操作系统、JDK 17 和构建配置计算；配置变更时可恢复旧依赖并下载缺少的部分。首次成功构建会保存缓存，后续构建才可复用；缓存可能被 GitHub 清理。项目构建输出、编译类、配置缓存和签名资料不在缓存范围内；APK 仍需重新编译、签名和验证。

## 维护补丁

`automation/patches/customizations.patch` 保存本次定制（广告、下载、图标、推广与更新入口）。`automation/ForkUpdater.java` 是更新器的权威副本，同时在本地应用源码中保留副本。修改更新器时同步两个文件；构建脚本会把 automation 中的副本复制到上游源码。

上游重构后需要人工迁移改动，重新生成补丁并检查每项功能，随后等待新的上游版本号发布。补丁应用成功、编译成功不能代替实机验证。
