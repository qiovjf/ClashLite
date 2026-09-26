# ClashLite

[English](#english) | 简体中文

一个开源的 Android 轻量级 VPN 代理客户端，内嵌 [mihomo](https://github.com/MetaCubeX/mihomo)（Clash.Meta）内核，使用 Kotlin + Jetpack Compose 构建，带有完全可自定义的 Material 3 主题系统（含 iOS 风格液态玻璃导航栏）。

> ⚠️ 本项目仅供学习研究网络技术之用，请遵守您所在地区的法律法规。

## 特性

- **多协议**：SS / VMess / VLESS / Trojan / AnyTLS / Hysteria2 等 mihomo 全部协议
- **订阅**：Clash YAML、base64 整包、`ss://` `vmess://` `trojan://` 分享链接；自动更新
- **TUN 接管**：VpnService + [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)（进程内 JNI，无需 root）
- **DNS**：MappedDNS（fake-ip）本地应答 + 域名远端解析，规避 Android 平台 DNS 限制
- **仪表盘**：实时速率、IP 出口检测（境内/境外 + 国旗）、运行模式热切换（规则/全局/直连）、精简运行状态
- **代理页**：分组标签页、节点卡片网格、延迟着色、搜索、并发测速、风格/排序/布局/尺寸自定义
- **连接页**：实时连接监控（v2.0）
- **基础配置**：端口/局域网/认证/日志等级/测速链接/IPv6/统一延迟/TCP 并发/Geo 低内存等 13 项
- **主题**：自定义主色（HSV）、深浅色三模式、Material You 动态取色、AMOLED 纯黑、液态玻璃导航栏、主题导入导出
- 其他：分应用代理、开机自启、日志分级过滤与导出、崩溃自动重启、网络切换自动重连

## 架构

```
┌─ UI（Kotlin + Jetpack Compose + Material 3）──────┐
│  仪表盘 / 代理 / 订阅 / 连接 / 主题 / 设置         │
├─ 控制层：mihomo RESTful API（127.0.0.1:9090）─────┤
├─ VPN 层：VpnService TUN ──(fd)──> hev-socks5-tunnel│
│          （进程内 JNI，直接接管 TUN 文件描述符）   │
├─ 内核：mihomo v1.19.13（arm64，jniLib exec 方式）─┤
└───────────────────────────────────────────────────┘
```

关键设计说明：

- 内核复用：预编译 mihomo 二进制置于 `jniLibs`，运行时从 `nativeLibraryDir` exec（Android 10+ 唯一允许 exec 的路径），无需 root。
- TUN 接管必须进程内完成：Android 的 `Runtime.exec` 会在子进程中关闭除 0/1/2 外的所有 fd，TUN 文件描述符无法通过 exec 传递给子进程。
- DNS 必须使用真实 DNS IP：Android 并行解析器不会查询 VPN 自定义的 DNS 地址；hev 的 mapdns 以同一 IP 拦截应答实现 fake-ip。

## 构建

环境要求：JDK 17+、Android SDK（platform 35 + build-tools 34.0.0）、NDK 27（仅重编 hev 时需要）、Gradle 8.7。

```bash
gradle assembleDebug      # debug APK
gradle assembleRelease    # 正式签名 APK（需自备 keystore）
gradle test               # 单元测试
```

APK 仅包含 `arm64-v8a`。

## 使用

1. 安装到 arm64 设备（Android 8.0+）
2. 「订阅」页添加订阅链接
3. 仪表盘点击「连接」并授权 VPN
4. 「代理」页选择节点，「主题」页定制外观

## 许可证

本项目以 [GPL-3.0](LICENSE) 发布，因为它分发了 GPL-3.0 授权的 mihomo 内核二进制。

第三方组件：
| 组件 | 许可证 |
|---|---|
| [mihomo](https://github.com/MetaCubeX/mihomo) 内核二进制 | GPL-3.0 |
| [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) | MIT |
| [Haze](https://github.com/chrisbanes/haze) | Apache-2.0 |
| Jetpack Compose / OkHttp / DataStore 等 | Apache-2.0 |

## <a id="english"></a>English

ClashLite is an open-source lightweight VPN proxy client for Android, built with Kotlin and Jetpack Compose, embedding the [mihomo](https://github.com/MetaCubeX/mihomo) (Clash.Meta) core. It features a fully customizable Material 3 theming system including an iOS-style liquid-glass navigation bar. Licensed under GPL-3.0 because it bundles the GPL-3.0 mihomo core binary. For study and research only — comply with your local laws.
