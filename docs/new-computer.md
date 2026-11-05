# 换电脑继续开发

代码都在 GitHub 上，密钥和登录状态不在。按下面的顺序准备一次就够了。

## 1. 从 GitHub 拉代码

先准备 Git、[GitHub CLI](https://cli.github.com/) 和 Node.js，然后登录：

```bash
gh auth login
```

把四个项目克隆到同一个文件夹：

```bash
gh repo clone oprfelix/znkeyboard zn-keyboard
gh repo clone oprfelix/Znkeyboard-for-macOS znkeyboard-for-macos
gh repo clone oprfelix/Znkeyboard-for-Windows znkeyboard-for-windows
gh repo clone oprfelix/znkeyboard znkeyboard-site -- -b gh-pages
```

| 目录 | 内容 | 开发和发布的分支 |
|---|---|---|
| `zn-keyboard` | Android 官网版 | `main` |
| `znkeyboard-for-macos` | macOS | `main` |
| `znkeyboard-for-windows` | Windows | `main` |
| `znkeyboard-site` | 官网和 Cloudflare Worker | `gh-pages` |

每个项目只维护一条线，不开新分支。旧的功能分支只是历史记录，不用管。

`zn-keyboard` 和 `znkeyboard-site` 是同一个 GitHub 仓库的两个分支，所以要分开克隆到两个目录。

Play 版停在 1.10.0（提交 `7be4aee`），不再维护。

## 2. 恢复备份的文件

这些文件不在 GitHub 上，也不能放上去，只能从你自己的加密备份恢复。

| 用途 | 文件或凭据 | 放到哪里 |
|---|---|---|
| Android 签名 | `zn-keyboard-release.jks` | `~/.android/keystores/` |
| Android 签名 | `zn-keyboard-release.properties` | `~/.android/keystores/` |
| macOS 签名 | Developer ID Application 证书（`.p12`） | 双击导入「钥匙串访问」 |

Android 的两个文件要放在同一个目录，文件名不变。没有它们也能开发和构建 Debug 版，但打不出能覆盖安装的正式包。

`.p12` 要在旧 Mac 上导出：打开「钥匙串访问」→「我的证书」，右键 `Developer ID Application: chuan rui yang` →「导出」。导出时设置密码，并保存到加密的地方。证书丢了可以在 Apple 开发者后台重新创建。

## 3. 安装开发工具

| 项目 | 需要 |
|---|---|
| Android | JDK 17 或更高、Android SDK、`adb` |
| macOS | Xcode 或 Command Line Tools（只能在 Mac 上构建） |
| Windows | .NET 10 SDK（在 Mac 上也能编译：`~/.dotnet`） |
| 官网 | Node.js |

Android 项目根目录要新建 `local.properties`，指向 SDK：

```properties
sdk.dir=/你的/Android/sdk/路径
```

## 4. 登录发布用的账号

发布时才需要：

```bash
cd znkeyboard-site/cloudflare
npm ci
npx wrangler login
```

```bash
xcrun notarytool store-credentials znkeyboard-notary --apple-id oprfelix@gmail.com --team-id 6DSHU7W7LQ
```

第二条命令要用 Apple 账号的「App 专用密码」，在 account.apple.com 创建。

## 5. 确认环境没问题

```bash
cd zn-keyboard && ./gradlew :app:assembleRelease
```

```bash
cd znkeyboard-for-macos && swift build -c release
```

```bash
cd znkeyboard-for-windows && PATH="$HOME/.dotnet:$PATH" dotnet build src/ZnKeyboard.csproj -c Release
```

```bash
cd znkeyboard-site/cloudflare && npx wrangler deploy --dry-run
```

## 6. 发布

版本号改在这里：

| 项目 | 文件 |
|---|---|
| Android | `app/build.gradle.kts` 的 `versionCode` 和 `versionName` |
| macOS | `scripts/build_app.sh` 的 `VERSION` 和 `BUILD` |
| Windows | `src/ZnKeyboard.csproj` 的 `Version` |

`versionCode` 目前是 50，Play 版是 44。每次发布都要比上一次大。

构建安装包：

```bash
cd zn-keyboard && ./gradlew :app:assembleRelease
```

```bash
cd znkeyboard-for-macos && scripts/release.sh
```

```bash
cd znkeyboard-for-windows && scripts/build.sh
```

推送代码和标签，再创建 GitHub Release：

```bash
git tag v版本号 && git push origin main --tags
```

```bash
gh release create v版本号 --title "ZnKeyboard 版本号" --notes-file 更新说明.md 安装包路径
```

Android 的安装包要先改名为 `ZnKeyboard-v版本号.apk`。

把安装包发布到官网下载源（三端各运行一次）：

```bash
cd znkeyboard-site && cloudflare/publish-download.sh android 版本号 安装包路径 Release页面网址
```

部署官网（先提交）：

```bash
cd znkeyboard-site && ./deploy.sh
```

## 不需要迁移的内容

- **用户统计：** 在 Cloudflare 的 D1 数据库里，官网的 Worker 直接读写。
- **安装包：** 在 Cloudflare R2 和 GitHub Release 上。
- **`znkeyboard-stats` 文件夹：** 已废弃，功能并入了官网的 Worker，Cloudflare 上也没有这个 Worker 了，不用带走。

## 换机后还要做的

- 手机上用 USB 调试时，Android 覆盖安装一定要用 `adb install -r`。不要 `adb uninstall`，会清空设置和学习数据。
- Claude Code 的项目记忆存在旧电脑的 `~/.claude/` 里，不会跟着 GitHub 走。重要规则已写进这份文档，新电脑上要重新告诉它：只维护一条线，不开分支。
