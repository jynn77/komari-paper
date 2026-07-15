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

## config.yml 说明

> ⚠️ **重要**：JAR 包内部的 config.yml 是空模板（端口全为空）。
> 首次启动自动生成 `plugins/Bettermix/config.yml`，你需要**手动编辑它**填写端口，然后重启服务器。

```
第一次启动：生成 plugins/Bettermix/config.yml (空模板)  →  报错 "未设置任何协议端口"
     ↓ 你编辑 config.yml 填好端口
第二次启动：读取你填的 config.yml  →  正常启动 ✅
```

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

首次启动后自动生成 `plugins/Bettermix/config.yml`，编辑它（端口必须填，否则报错）：

```yaml
tuic_port: ""              # TUIC 端口（不填留空）
hy2_port: "29548"          # Hysteria2 端口
reality_port: "29548"      # VLESS+Reality 端口
sni: "www.bing.com"

# komari-agent 配置（endpoint + key 都填了才启动）
komari_agent_enabled: true
komari_agent_name: "bettermix"
komari_agent_ver: "1.0.1"
komari_agent_endpoint: ""   # komari 服务器地址
komari_agent_key: ""        # 自动发现密钥
```

### 4. 重启服务器

```
/restart
```

### 5. 常见问题

**Q: 报错 "未设置任何协议端口"？**
A: `plugins/Bettermix/config.yml` 端口为空，编辑后重启。

**Q: komari-agent 没启动？**
A: 检查 `komari_agent_endpoint` 和 `komari_agent_key` 是否都填写了，缺一不可。

**Q: 改了配置需要重编译 JAR 吗？**
A: 不需要，配置是外部文件，重启服务器即可。

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