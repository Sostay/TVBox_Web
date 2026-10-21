# TVBox Web 跨平台双引擎影视聚合项目

本项目是一个探索并实现 **Android 后端 + Web 前端** 架构的 TVBox 跨平台影视播放解决方案，旨在打破传统电视盒子的设备限制，让手机、平板、电脑及车机浏览器都能享受到丝滑、强大的 TVBox 观影体验。

---

## 🌟 核心特性亮点

* 📱 **跨平台双端生态**：
  * **Windows 本地端**：提供 `start.bat` 一键引导启动、`start_silent.bat` 无黑框后台静默守护、`stop.bat` 优雅安全关停，以及 `修复端口占用.bat` 智能解决 WinNAT / Hyper-V 动态端口占用问题。
  * **Android 服务端**：一键安装 APK，内置 NanoHTTPD 与 Dalvik 爬虫引擎，开机自启常驻，全屋局域网设备扫码即看。
* 🎵 **双封装多音轨原生切换 (MP4 & MKV)**：
  * 独家实现 MP4 `moov/trak/tkhd` 与 MKV EBML `Tracks/TrackEntry` 动态二进制重写中继。
  * 彻底抹平 Chromium (Edge / Chrome) 与 Gecko (Firefox / Zen) 浏览器内核对默认音轨选择与启用的规则差异，国粤双语、多声道原声原画自由切换。
* 💬 **全方位多字幕引擎**：
  * 自动嗅探并中继 HLS `#EXT-X-MEDIA` 字幕与网络外挂字幕。
  * 新增 **【📂 本地字幕】** 文件一键导入与 **文件拖拽即播** 支持，电脑本地的 `.srt`、`.vtt`、`.ass`、`.ssa` 字幕拖入播放区域即可秒级转换渲染。
  * 智能识别蓝光原盘 PGS 位图字幕流并给出清晰指引，避免无效选择。
* ⚡ **纯净离线与极致交互**：
  * 全站本地化内置 Tailwind CSS 与 HLS.js，**零 CDN 外部网络依赖**，弱网与局域网快速秒开。
  * 完备的 **h5player 专业快捷键体系**：逐帧微调（`F` / `D`）、倍速精控与记忆切换（`X` / `C` / `Z`）、画中画（`P`）、画面旋转（`R`）、截图（`S`）、网页全屏（`W`）、帮助面板（`?` / `H`）等。
  * 支持 PWA 渐进式安装与 Window Controls Overlay (WCO) 沉浸式桌面视窗融合。
* ☁️ **网盘生态与追剧断点**：
  * 内置网盘扫码配置中心（支持夸克网盘、阿里云盘、百度网盘、115 网盘等）。
  * 智能本地历史记录，精准定位每集播放断点并支持一键“继续播放”。

---

## 运行环境要求与安装说明 (Windows)

运行本项目的 Windows 服务端需要 **Node.js (推荐 v18 或以上 LTS 长期支持版)**：

### 如何准备 Node.js 环境？
1. **自动检测与一键安装（最省心）**：
   直接双击 `start.bat`。如果脚本未检测到 Node.js，会自动询问你是否通过 Windows 官方包管理器一键安装（按 `Y` 自动完成，无需手动配置环境变量）。
2. **手动官网下载**：
   前往 Node.js 官网下载 Windows 安装包：[https://nodejs.org](https://nodejs.org) （推荐下载 LTS 版本，一路点击下一步即可）。
3. **便携式绿色版（高级用户）**：
   从 Node.js 官网下载 `node-v*-win-x64.zip` 便携压缩包，将解压出的 `node.exe` 直接放入本项目下的 `bin/node.exe`，`start.bat` 会自动优先使用该本地便携版。

---

## 快速启动

### 方式一：双击批处理（推荐）
直接在 Windows 资源管理器中双击运行：
```text
TVBox_Web/start.bat
```
如需后台静默无弹窗运行，可直接双击：
```text
TVBox_Web/start_silent.bat
```

### 方式二：命令行启动
```bash
cd TVBox_Web
node gateway.js
```

服务就绪后：
* **Web 观影前端**：[http://127.0.0.1:8999](http://127.0.0.1:8999)
* **网盘扫码配置中心**：[http://127.0.0.1:8999/website](http://127.0.0.1:8999/website)
* **局域网设备观看**：在手机、iPad、平板浏览器打开 `http://<本机局域网IP>:8999`

---

## 核心技术实现解析

1. **双引擎架构**：
   * **Node 引擎 (`index.js`)**：负责解析 `*.js.md5` 类的跨平台爬虫，直接在本地 Node.js 进程中启动 Fastify 服务，免去安卓虚拟机的沉重开销；
   * **Android Dex 引擎 (`android/`)**：负责解析传统 TVBox 源中的 `csp_*` Dalvik 字节码爬虫与加密容器，通过安卓原生前台服务或 WSA 容器宿主，直接运行 Dalvik DexClassLoader 并调用 Android Bionic 运行时。
2. **零依赖网关与流媒体中继 (`gateway.js`)**：
   * **解除 CORS 限制**：为所有 Spider API 请求与配置自动注入跨域允许头；
   * **突破防盗链 (Anti-Hotlinking)**：针对 m3u8/mp4 流，自动注入源端要求的 `Referer` 与 `User-Agent`，解决浏览器原生播放报 403 的痛点；
   * **M3U8 流式动态重写**：自动将 M3U8 切片路径代理转发，支持 HTTP Range 断点续传拖动快进。
3. **流媒体音轨嗅探与动态切轨引擎 (`lib/media-inspector.js`)**：
   * 采用纯原生 JavaScript / Buffer 操作，毫秒级解复用 MP4 atom 树与 Matroska EBML 元素树；
   * 针对浏览器直链播放无法切换音轨的限制，在数据中继阶段执行动态包头重排与 enabled 标志位置换，以极低资源损耗实现原盘音轨自由切换。
4. **现代化 Web 前端管线 (`public/index.html`)**：
   * **持久化源管理**：支持用户自由导入、编辑、切换与删除多个 TVBox 源订阅；
   * **前端外挂字幕管线**：内置 SRT / ASS / SSA 到 WebVTT 转换器，支持本地上传与全屏拖拽载入；
   * **智能容灾诊断**：针对源站失效、超时或格式受限给出友好切换建议。
