# Fork 自签名 APK

本 fork 的 `Test APK` 工作流使用固定的自有签名和官方包名
`io.github.ccch1mneyyy.dsh_app`。它不是官方签名的安装包。

## 安装和更新

- 首次从官方版切换：先备份应用数据，卸载官方版，再安装本 fork APK。
- 后续更新：保持同一个包名、签名密钥，构建生成的版本号不低于已安装版本，即可覆盖安装。
- 旧的 `DSH Go 测试` 使用 `.test` 包名，数据独立，需要另行迁移。
- 应用内的官方更新源不会生成本 fork 的签名包；更新请安装本 fork Actions 的产物。

## 本地构建

`app/android/fork-signing.jks` 和 `app/android/key.properties` 是本地签名材料，
由现有 `.gitignore` 排除，不上传 Git。Gradle 自动读取 `key.properties`。

在准备好 Flutter、Android SDK 和运行时之后运行：

```bash
runtime/build-runtime.sh arm64-v8a
tooling/build-apk.sh --release -f arm64 --no-companion --no-star
```

不要使用 `--side`，它会改变包名。不要重新生成或覆盖现有密钥。

## Actions 构建

仓库的 Actions Secrets 保存同一把密钥：

- `FORK_ANDROID_KEYSTORE_BASE64`
- `FORK_ANDROID_STORE_PASSWORD`
- `FORK_ANDROID_KEY_ALIAS`
- `FORK_ANDROID_KEY_PASSWORD`

工作流在临时目录恢复密钥，通过现有 Gradle 签名配置生成 APK，核对 APK
证书与密钥证书一致后上传 `dsh-go-test-apk`，最后清理 runner 上的签名文件。
缺少 Secrets 会直接失败，不会退回临时 debug 签名。

## 同步上游

将上游更新合并进本 fork，保留 `.github/workflows/test-apk.yml` 和自己的功能改动。
不要用丢弃 fork 提交的方式同步。更新涉及 `app/`、`packages/`、`runtime/`、`tooling/`
或工作流时，推送到 `main` 会自动构建；也可手动运行 `Test APK`。

签名材料不需要在每次同步后重新配置。如果上游调整构建工具链或产生合并冲突，
需要相应解决，固定签名不能保证任意上游版本都自动编译成功。
