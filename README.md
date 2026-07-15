# Bettermix — Bukkit Plugin 版

> 基于 [eishare/Paper](https://github.com/eishare/Paper) 重构，转换为 Bukkit 插件格式
> 
> 放入 `plugins/` 目录即可运行，无需 `java -jar`

## 功能

- **Bukkit 插件** — 放入 `plugins/` 目录，服务器启动时自动加载
- **Sing-box 多协议内核**（tuic / hy2 / vless+reality）— 自动下载并启动
- **komari-agent 集成** — 可选，配置 endpoint 后自动上报监控
- 自动生成 UUID、每日 00:03 自动重启 Sing-box
- TCP/UDP 端口可共用

## 安装

### 1. 下载

从 [Releases](https://github.com/jynn77/komari-paper/releases) 下载 `bettermix.jar`

### 2. 安装

放入 Paper 服务器的 `plugins/` 目录，重启服务器：

```
plugins/
└── bettermix.jar        # 本插件
```

### 3. 配置

首次启动后自动生成 `plugins/Bettermix/config.yml`，编辑它：

```yaml
tuic_port: "8443"              # TUIC 端口
hy2_port: "8443"               # Hysteria2 端口
reality_port: "8443"           # VLESS+Reality 端口
sni: "www.bing.com"            # 伪装 SNI

komari_agent_enabled: true
komari_agent_name: "bettermix"
komari_agent_ver: "1.0.1"
komari_agent_endpoint: ""      # 填了 komari 地址才启动 agent
komari_agent_key: ""
```

### 4. 重载配置

```
/bettermix reload
```

或重启服务器。

## 构建

```bash
./gradlew build
# 产物在 build/libs/bettermix.jar
```

## 分支说明

| 分支 | 内容 |
|------|------|
| `main` | Java-Paper 重构 + komari-agent（独立运行版） |
| `plugin` | **本版本**（Bukkit 插件版） |

## 目录结构

```
├── build/libs/bettermix.jar          # 编译产物（放入 plugins/）
├── src/main/java/io/papermc/paper/
│   └── PaperPlugin.java              # 主类（继承 JavaPlugin）
├── src/main/resources/
│   ├── plugin.yml                    # 插件描述
│   └── config.yml                    # 内置默认配置
└── build.gradle.kts
```