# komari-paper

基于 Paper 重构的 Java 代理部署项目，集成 sing-box (Hysteria2 + VLESS Reality) + komari-agent 监控 + Argo 隧道 + Telegram 推送。

## 分支说明

| 分支 | JAR 文件 | 用途 |
|------|---------|------|
| `main` | `server.jar` | 独立运行（`java -jar server.jar`） |
| `plugin` | `bettermix.jar` | Bukkit 插件（放在 `plugins/` 目录） |

## 快速使用

### 1. 下载 JAR
从 [Release](https://github.com/jynn77/komari-paper/releases) 下载对应分支的 JAR 文件。

### 2. 编辑 config.yml
首次启动前编辑 `config.yml`，至少填写端口：

```yaml
port: "25983"           # hy2 + reality 共用端口
sni: "www.bing.com"     # TLS SNI / Reality 握手域名
```

### 3. 启动

**独立版（main 分支）：**
```bash
java -jar server.jar
```

**插件版（plugin 分支）：**
将 `bettermix.jar` 放入 Minecraft 服务器的 `plugins/` 目录，重启服务器。

### 4. 首次启动流程
1. 自动生成 UUID 并保存到 `data/uuid.txt`（重启后不变）
2. 生成 Reality 密钥对并保存到 `data/reality.key`
3. 生成自签证书（EC prime256v1, CN=bing.com）
4. 下载 sing-box 并启动代理（hy2 + reality 共用端口）
5. 输出节点链接（控制台 + Telegram 推送）

### 5. 节点链接示例

```
vless://uuid@your-server:25983?encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.bing.com&fp=chrome&pbk=xxxxx#CN-Telecom-Reality
hysteria2://uuid@your-server:25983?sni=www.bing.com&insecure=1&alpn=h3&obfs=none#CN-Telecom-Hysteria2
```

## 配置项说明

| 配置项 | 默认值 | 说明 |
|-------|--------|------|
| `port` | `""` | hy2 + reality 共用端口（必填） |
| `sni` | `www.bing.com` | TLS SNI / Reality 握手域名 |
| `sb_log_enabled` | `false` | sing-box 日志开关（开启后写入 `data/sing-box.log`） |
| `node_name` | `""` | 节点名称前缀（留空自动检测 ISP，如 `CN-Telecom`） |
| `argo_enabled` | `false` | 是否启用 Argo 隧道（VMess WebSocket） |
| `argo_token` | `""` | Cloudflare Tunnel Token（留空生成临时隧道） |
| `argo_domain` | `""` | 固定隧道域名（token 模式必填） |
| `argo_port` | `8001` | 临时隧道本地端口 |
| `argo_name` | `argo-tunnel` | Argo 伪装文件名 |
| `argo_cfip` | `saas.sin.fan` | Cloudflare 优选 IP（用于拼接节点链接） |
| `komari_agent_enabled` | `true` | 是否启用 komari-agent 监控 |
| `komari_agent_name` | `bettermix` | komari 伪装文件名 |
| `komari_agent_endpoint` | `""` | komari 服务器地址，格式：`https://www.mydomain.com`（不需要端口和路径） |
| `komari_agent_key` | `""` | komari 自动发现密钥 |
| `tg_bot_token` | `""` | Telegram Bot Token（留空不推送） |
| `tg_chat_id` | `""` | Telegram 聊天/频道 ID（留空不推送） |

## Telegram 推送格式

```
✅ 节点已就绪 | CN-Telecom
🌍 IP: 你的服务器IP

<pre>base64编码的节点链接...</pre>
```

节点链接以 base64 编码的 `<pre>` 块推送，可直接粘贴到 v2rayN / Sing-box / Shadowrocket / NekoBox。

## 节点名称自动检测

`node_name` 留空时自动从 `api.ip.sb/geoip` 获取 ISP，格式：`国家代码-运营商`，如 `CN-Telecom`、`JP-Ionos`、`US-Google`。

节点链接后缀使用 nodeName 区分协议：`#CN-Telecom-Reality`、`#CN-Telecom-Hysteria2`。

## 反检测

- 启动后删除 sing-box 二进制（config/cert/key 保留供定时重启）
- 数据持久化在 `data/` 目录，UUID 和 Reality 密钥重启后不变
- 每日北京时间 00:03 自动重启 sing-box 进程
