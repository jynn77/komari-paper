# komari-paper

基于 Paper 重构的 Java 代理部署项目，集成 sing-box + komari-agent 监控 + Argo 隧道 + Telegram 推送。

## 分支说明

| 分支 | JAR 文件 | 用途 |
|------|---------|------|
| `main` | `server.jar` | 独立运行（`java -jar server.jar`） |
| `plugin` | `bettermix.jar` | Bukkit 插件（放在 `plugins/` 目录） |

## 快速使用

### 1. 下载 JAR
从 [Release](https://github.com/jynn77/komari-paper/releases) 下载对应分支的 JAR 文件。

### 2. 编辑 config.yml
首次启动前编辑 `config.yml`，至少填写一个协议端口：
```yaml
reality_port: "443"    # VLESS+Reality 端口
tuic_port: ""          # TUIC 端口
hy2_port: ""           # Hysteria2 端口
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
2. 生成 Reality 密钥并保存到 `data/reality.key`
3. 下载 sing-box 并启动代理
4. 输出节点链接（控制台 + Telegram 推送）

## 配置项说明

| 配置项 | 说明 |
|-------|------|
| `reality_port` | VLESS+Reality 端口 |
| `tuic_port` | TUIC 端口 |
| `hy2_port` | Hysteria2 端口 |
| `sni` | TLS SNI，默认 `www.bing.com` |
| `argo_enabled` | 是否启用 Argo 隧道 |
| `argo_domain` | Cloudflare 固定隧道域名 |
| `argo_token` | Tunnel Token（留空生成临时隧道） |
| `argo_cfip` | Cloudflare 优选 IP |
| `komari_agent_endpoint` | komari 服务器地址 |
| `komari_agent_key` | 自动发现密钥 |
| `tg_bot_token` | Telegram Bot Token |
| `tg_chat_id` | Telegram 聊天/频道 ID |

## Telegram 推送格式

节点链接以 base64 编码的 `<pre>` 块推送，可直接粘贴到 v2rayN / Sing-box / Shadowrocket。

## 反检测

- 启动后删除 sing-box 二进制（config/cert/key 保留供定时重启）
- 数据持久化在 `data/` 目录，UUID 和 Reality 密钥重启后不变
