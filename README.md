# Paper + Komari-Agent

> 基于 [eishare/Paper](https://github.com/eishare/Paper) 修改，集成 komari-agent 监控

## 功能

- **Java 启动器 + Sing-box 多协议内核伪装方案**（tuic / hy2 / vless+reality）
- **集成 komari-agent** — 自动下载并启动，上报服务器状态到 komari 面板
- **伪装插件名** — 二进制文件伪装成 Minecraft 插件（默认 `bettermix`）
- 自动生成 UUID、每日 00:03 自动重启 Sing-box
- TCP/UDP 端口可共用

## 快速开始

### 1. 下载

从 [Releases](https://github.com/jynn77/komari-paper/releases) 下载：

- `server.jar` — 主程序
- `config.yml` — 配置文件

### 2. 配置

编辑 `config.yml`：

```yaml
tuic_port: "8443"              # TUIC 协议端口
hy2_port: "8443"               # Hysteria2 端口
reality_port: "8443"           # VLESS+Reality 端口
sni: "www.bing.com"            # 伪装 SNI

# komari-agent 配置
komari_agent_enabled: true
komari_agent_name: "bettermix"   # 伪装文件名
komari_agent_ver: "1.0.1"        # 伪装版本号
komari_agent_endpoint: ""        # komari 服务器地址（填了才启动）
komari_agent_key: ""             # 自动发现密钥（填了才启动）
```

> UUID 自动生成并持久化到 `data/uuid.txt`，无需手动填写。

### 3. 部署

上传 `server.jar` + `config.yml` 到 Minecraft 托管面板，设置启动命令：

```bash
java -Xms128M -Xmx3072M -jar server.jar
```

启动后自动完成：
1. 生成自签证书
2. 下载并启动 Sing-box（代理服务）
3. 如配置了 endpoint/key，下载并启动 komari-agent
4. 每日 00:03 自动重启 Sing-box
5. 输出节点链接

## 构建（GitHub Actions）

推送 `main` 分支自动触发 Actions 编译：

```bash
git push origin main
```

构建产物在 Releases 页下载。

## 分支说明

| 分支 | 内容 |
|------|------|
| `main` | 本版本（Java-Paper 重构 + komari-agent 集成） |
| `plugin` | 转换为 Bukkit 插件格式的版本 |

## 目录结构

```
├── .github/workflows/build-jar.yml   # Actions 自动编译
├── config.yml                        # 配置文件
├── src/main/java/io/papermc/paper/
│   └── PaperBootstrap.java           # 主程序（含 komari-agent 集成）
└── build/libs/server.jar             # 编译产物
```