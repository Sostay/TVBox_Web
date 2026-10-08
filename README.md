# TVBox Web 跨平台双引擎影视聚合项目

本项目是一个探索并实现 **Android 后端 + Web 前端** 架构的 TVBox 跨平台影视播放解决方案。

---

## 运行环境要求与安装说明 (Windows)

运行本项目的 Windows 服务端需要 **Node.js (推荐 v18 或以上 LTS 长期支持版)**：

### 为什么源码中不直接捆绑 `node.exe`？
* 单个 `node.exe` 二进制文件大小约为 **88 MB**，不适宜直接提交至 Git 仓库中；
* 因此，在 GitHub 仓库中，我们提供了智能启动脚本与极简安装方案；在 GitHub Releases 中，可额外提供包含了内置绿色免安装 Node 的全量压缩包。

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
3. **前端持久化源管理 (`public/index.html`)**：
   * 默认为空，支持用户自由添加、编辑、切换与删除多个 TVBox 源订阅；
   * 智能诊断卡片：针对网盘失效镜像或超时给出友好切换建议，内置一键直达高可用秒播站功能。
