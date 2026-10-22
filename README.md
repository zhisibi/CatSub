# CatSub

**Sub-Store 的第三方全功能安卓客户端** —— 把 Sub-Store 官方后端与前端完整打包进一个 APK，
在手机本机运行真正的 Sub-Store 服务，无需服务器、无需 root、无需 Termux。

[![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL--3.0-blue.svg)](LICENSE)
![Platform](https://img.shields.io/badge/platform-Android%208.0%2B%20(arm64--v8a)-green)
![Sub-Store Backend](https://img.shields.io/badge/Sub--Store%20backend-2.42.6-orange)
![Sub-Store Frontend](https://img.shields.io/badge/Sub--Store%20frontend-2.34.1-orange)
![Node.js](https://img.shields.io/badge/Node.js-26.4.0-brightgreen)

> English summary: CatSub packages the official Sub-Store backend (Node.js bundle) and
> frontend into a standalone Android app. An embedded Node.js runtime (from Termux builds)
> runs the unmodified backend on-device at `127.0.0.1:3000`, and the app shell provides a
> WebView UI, a foreground service with watchdog, boot autostart, full logging, and in-app
> upstream updates. Licensed under AGPL-3.0, same as upstream Sub-Store.

## 功能

- **全功能 Sub-Store**：订阅转换（Clash/mihomo、Surge、Loon、QX、Stash、sing-box、V2Ray 等）、
  订阅格式化（过滤、重命名、排序、脚本处理等全部官方操作）、组合订阅、文件托管、定时同步、
  流量查询——均为上游官方后端能力，未做任何阉割或修改。
- **本机运行**：服务只监听 `127.0.0.1:3000`，数据全部保存在应用私有目录，不经过任何第三方服务器。
- **同机直连**：其他代理客户端可直接使用 `http://127.0.0.1:3000/download/...` 订阅链接。
- **前台服务保活**：常驻通知、进程看门狗自动重启、可选开机自启。
- **应用内更新**：菜单里一键从上游 GitHub 检查 Sub-Store 前后端新版本并下载更新，
  随时可一键恢复 APK 内置版本（订阅数据不受影响）。
- **完整日志**：应用内可查看运行日志，另镜像一份到 `下载/SubStore/boot-log.txt`，方便排障。
- **免责声明与关于页**：启动时展示免责声明；关于页展示各组件版本与开源仓库地址。

## 架构

```
┌─────────────────────────────────────────────────┐
│ CatSub APK (Kotlin, 零 AndroidX)                │
│                                                 │
│  MainActivity ── WebView ──► http://127.0.0.1:3000 (官方前端)
│  NodeService  ── 前台服务 + 看门狗 + 开机自启   │
│  NodeRuntime  ── 拉起 Node 子进程               │
│        │                                        │
│        ├─ libnodebin.so  Node.js 26 (Termux)    │
│        ├─ sub-store.bundle.js  官方后端 (未修改)│
│        └─ frontend/            官方前端 (未修改)│
└─────────────────────────────────────────────────┘
```

- Node 运行时来自 Termux 的 aarch64 构建，经 patchelf 处理 SONAME/RPATH 后以
  `lib*.so` 形式放入 jniLibs（`extractNativeLibs` 解压到应用 lib 目录执行），
  处理细节见 `scripts/prepare-node-runtime.sh`。
- 后端以官方 MERGE 模式运行（同一端口托管前端与 API），数据目录、端口、CORS
  白名单均通过 Sub-Store 官方环境变量配置，未改上游一行业务代码。

## 从源码构建

前置：JDK 17、Android SDK（platform 34 + build-tools 34）、Kotlin 编译器、
`patchelf`、`curl`、`unzip`、`dpkg-deb`。

```bash
# 1) 拉取上游前后端发行资产到 assets（可指定版本）
BACKEND_VER=2.42.6 FRONTEND_VER=2.34.1 ./scripts/fetch-upstream-assets.sh

# 2) 准备内嵌 Node 运行时（Termux 二进制 + patchelf 处理）
./scripts/prepare-node-runtime.sh

# 3) 准备你自己的签名 keystore（放到 keystore/substore.keystore），设置密码后构建
export SUBSTORE_KS_PASS='你的keystore密码'
./build-manual.sh
# 产物：app/build-manual/SubStore.apk
```

构建链为手工链（aapt2 + kotlinc + d8 + apksigner），不需要 Gradle 守护进程。

## 使用说明

1. 安装 APK 并打开，阅读并同意免责声明后进入。
2. 顶部圆点变绿即服务就绪；在网页界面里添加、编辑你的订阅（与官方 Web 版完全一致）。
3. 订阅下载链接形如 `http://127.0.0.1:3000/download/<name>?target=ClashMeta`，
   同一台手机上的代理客户端可直接引用（需保持 CatSub 服务运行）。
4. 导出备份请用菜单「浏览器打开」后在系统浏览器里操作（WebView 内生成的
   blob 下载不会落盘，这是系统 WebView 的限制）。

## 许可与上游合规

- 本项目及其所打包的 Sub-Store 前后端均遵循 **AGPL-3.0** 许可（全文见 [LICENSE](LICENSE)）。
- 上游项目：[Sub-Store](https://github.com/sub-store-org/Sub-Store)、
  [Sub-Store Front-End](https://github.com/sub-store-org/Sub-Store-Front-End)，
  版权归 Peng-YM 及全体贡献者所有。第三方组件清单与许可见 [NOTICE.md](NOTICE.md)。
- 依 AGPL-3.0 第 13 条，本软件的完整对应源代码即本仓库；应用内「检查更新」
  下载的也是上游官方发布的原始构建产物。
- 发行 APK 的签名密钥由维护者私有保管，不包含在本仓库中；自行构建的 APK
  签名与官方发行版不同，无法互相覆盖升级——这是正常现象。

## 免责声明

本软件仅供个人学习与技术研究使用，不提供、不存储、不分发任何订阅内容或节点信息，
全部数据仅保存在使用者设备本机。请遵守所在国家或地区的法律法规，禁止用于任何
违法用途；因使用本软件产生的任何后果由使用者自行承担，与开发者无关。

## 致谢

感谢 Sub-Store 作者 Peng-YM 及全体贡献者，感谢 Termux 项目提供的 aarch64 构建。
