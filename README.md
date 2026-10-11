# 口袋酒馆 (SillyTavernPocket)

口袋酒馆是一个 Android 客户端,把 [SillyTavern](https://github.com/SillyTavern/SillyTavern)(酒馆)1.19.0 直接跑在手机上。

- 应用内启动一个本地 Node.js 进程运行酒馆服务,WebView 加载 `127.0.0.1` 上的页面,不需要电脑或外部服务器。
- 酒馆数据保存在应用私有目录,升级内核不会丢失聊天和角色。
- 支持 Android 8.0(API 26)及以上,仅 arm64-v8a 设备。

## 依赖的第三方源码与许可证

- **SillyTavern 1.19.0**(酒馆)— AGPL-3.0
  通过 git submodule 固定在 `kernel/`,本项目的补丁位于 `patches/kernel/`。
- **SillyTavern 的 npm 生产依赖**(约 667 个包)— 各包自有许可证,多为 MIT、ISC、Apache-2.0 等
  构建时由 `npm ci` 安装,完整列表见 `kernel/package-lock.json`。
- **Node.js** nodejs-lts 24.18.0(经 Termux 预编译包获取)— MIT
- **OpenSSL** 3.x — Apache-2.0
- **ICU** — ICU License(Unicode 自定义许可)
- **libc++** — NCSA 许可证(Termux 包元数据标注)
- **c-ares** — MIT
- **zlib** — zlib License
- **SQLite** — 公有领域
- **resolv-conf** — 公有领域
- **ca-certificates** 数据 — MPL-2.0

Node 运行时的 `.so` 依赖来自 [Termux 软件包仓库](https://github.com/termux/termux-packages),构建脚本 `scripts/fetch-node-runtime.sh` 会校验每个包的 SHA256。

> 注意:以上组件的完整许可证文本随 APK 分发的工作尚未完成,发布前需要补齐。

## 许可证

本项目以 [AGPL-3.0](LICENSE) 发布。
