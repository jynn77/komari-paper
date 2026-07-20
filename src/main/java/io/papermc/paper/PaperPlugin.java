package io.papermc.paper;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class PaperPlugin extends JavaPlugin {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private String uuid;
    private String hy2Port;
    private String realityPort;
    private String vmessWsPort;
    private String vlessWsPort;
    private String naivePort;
    private String anytlsPort;
    private String tuicPort;
    private String sni;
    private String privateKey = "";
    private String publicKey = "";
    private Path baseDir;
    private String argoUrl = ""; // Argo 隧道域名

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();

        getLogger().info("loading config.yml...");

        try {
            var config = getConfig();

            uuid = config.getString("uuid", "");
            hy2Port = config.getString("hy2_port", "");
            realityPort = config.getString("reality_port", "");
            vmessWsPort = config.getString("vmess_ws_port", "");
            vlessWsPort = config.getString("vless_ws_port", "");
            naivePort = config.getString("naive_port", "");
            anytlsPort = config.getString("anytls_port", "");
            tuicPort = config.getString("tuic_port", "");
            sni = config.getString("sni", "www.iij.ad.jp");

            if (uuid.isEmpty()) uuid = UUID.randomUUID().toString();
            if (hy2Port.isEmpty() && realityPort.isEmpty() && vmessWsPort.isEmpty()
                    && vlessWsPort.isEmpty() && naivePort.isEmpty() && anytlsPort.isEmpty() && tuicPort.isEmpty())
                throw new RuntimeException("❌ 未设置任何端口！");

            baseDir = getDataFolder().toPath().resolve(".cache");
            Files.createDirectories(baseDir);
            Path configPath = baseDir.resolve("config.json");
            Path certPath = baseDir.resolve("cert.pem");
            Path keyPath = baseDir.resolve("private.key");
            Path bin = baseDir.resolve("sb");
            Path keypairFile = baseDir.resolve("keypair.txt");

            getLogger().info("✅ config.yml 加载成功");

            // Reality 密钥
            if (!realityPort.isEmpty()) {
                if (Files.exists(keypairFile)) {
                    String[] parts = Files.readString(keypairFile).trim().split("\n");
                    privateKey = parts[0];
                    publicKey = parts.length > 1 ? parts[1] : "";
                    getLogger().info("🔑 已加载 Reality 密钥对");
                } else {
                    downloadSingBox(bin);
                    String kp = sh(bin + " generate reality-keypair");
                    Matcher pm = Pattern.compile("PrivateKey:\\s*(.*)").matcher(kp);
                    Matcher pum = Pattern.compile("PublicKey:\\s*(.*)").matcher(kp);
                    if (pm.find() && pum.find()) {
                        privateKey = pm.group(1).trim();
                        publicKey = pum.group(1).trim();
                        Files.writeString(keypairFile, privateKey + "\n" + publicKey + "\n");
                        getLogger().info("✅ Reality 密钥已生成");
                    }
                }
            }

            // 证书（需要 TLS 的协议才生成）
            boolean needCert = !hy2Port.isEmpty() || !vmessWsPort.isEmpty() || !vlessWsPort.isEmpty()
                    || !naivePort.isEmpty() || !anytlsPort.isEmpty() || !tuicPort.isEmpty();
            if (needCert && (!Files.exists(certPath) || !Files.exists(keyPath))) {
                getLogger().info("🔨 生成自签证书...");
                sh("openssl ecparam -genkey -name prime256v1 -out \"" + keyPath + "\"");
                sh("openssl req -new -x509 -days 3650 -key \"" + keyPath + "\" -out \"" + certPath + "\" -subj \"/CN=bing.com\"");
            }

            // 下载 sing-box
            if (!Files.exists(bin)) downloadSingBox(bin);

            // 生成配置
            String json = buildConfig(certPath.toString(), keyPath.toString(), argoEnabled, argoPort);
            Files.writeString(configPath, json, StandardCharsets.UTF_8);
            getLogger().info("✅ sing-box 配置生成完成");

            // 启动
            sh("nohup " + bin + " run -c " + configPath + " >/dev/null 2>&1 &");
            getLogger().info("✅ sing-box 已启动");
            Thread.sleep(2000);

            // 删除二进制
            try { Files.deleteIfExists(bin); } catch (IOException ignored) {}

            // ===== Argo 隧道 =====
            boolean argoEnabled = config.getBoolean("argo_enabled", false);
            String argoToken = config.getString("argo_token", "");
            String argoDomain = config.getString("argo_domain", "");
            String argoPort = config.getString("argo_port", "8001");
            String argoCfip = config.getString("argo_cfip", "saas.sin.fan");
            if (argoCfip.isEmpty()) argoCfip = "saas.sin.fan";

            if (argoEnabled) {
                String argoName = "argo-tunnel";
                String argoArch = System.getProperty("os.arch").toLowerCase().contains("arm") ? "arm64" : "amd64";
                String argoDownUrl = "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-" + argoArch;
                getLogger().info("🚇 下载 Argo 隧道...");
                sh("curl -L -o \"" + baseDir + "/" + argoName + "\" \"" + argoDownUrl + "\" && chmod +x \"" + baseDir + "/" + argoName + "\"");

                if (!argoToken.isEmpty()) {
                    // 固定隧道
                    sh("nohup \"" + baseDir + "/" + argoName + "\" tunnel run --token " + argoToken + " >/dev/null 2>&1 &");
                    if (!argoDomain.isEmpty()) {
                        this.argoUrl = argoDomain;
                        getLogger().info("🚇 Argo 固定隧道域名: " + argoDomain);
                    }
                } else {
                    // 临时隧道
                    String argoLog = baseDir + "/argo.log";
                    sh("nohup \"" + baseDir + "/" + argoName + "\" tunnel --url http://localhost:" + argoPort + " >" + argoLog + " 2>&1 &");
                    Thread.sleep(5000);
                    // 从日志提取域名
                    try {
                        String log = Files.readString(Paths.get(argoLog));
                        Matcher m = Pattern.compile("https://[a-zA-Z0-9.-]+\\.trycloudflare\\.com").matcher(log);
                        if (m.find()) {
                            this.argoUrl = m.group().replace("https://", "");
                            getLogger().info("🚇 Argo 临时隧道域名: " + this.argoUrl);
                        }
                    } catch (Exception e) {
                        getLogger().warning("⚠️ 提取 Argo 域名失败: " + e.getMessage());
                    }
                }
                try { Files.deleteIfExists(baseDir.resolve(argoName)); } catch (IOException ignored) {}
            }
            // =========================

            // 节点
            String serverIp = getPublicIP();
            String isp = getISP();
            String nodeName = config.getString("node_name", "");
            if (nodeName.isEmpty()) nodeName = isp;
            else nodeName = nodeName + "-" + isp;

            String nodes = buildNodes(serverIp, nodeName, argoCfip);
            getLogger().info("\n=== ✅ 节点链接 ===\n" + nodes);

            // Telegram
            String botToken = config.getString("tg_bot_token", "");
            String chatId = config.getString("tg_chat_id", "");
            if (!botToken.isEmpty() && !chatId.isEmpty()) {
                String b64 = Base64.getEncoder().encodeToString(nodes.getBytes(StandardCharsets.UTF_8));
                sendTelegram(botToken, chatId,
                        "✅ 节点已就绪 | " + nodeName + "\n\uD83C\uDF0D IP: " + serverIp + "\n\n<pre>"
                        + b64.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</pre>");
            }

            getLogger().info("✅ " + getName() + " v" + getDescription().getVersion() + " 已启动");

        } catch (Exception e) {
            getLogger().severe("启动失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void onDisable() {
        try { sh("pkill -f 'sb run' 2>/dev/null || true"); } catch (Exception ignored) {}
    }

    // ===== 工具 =====
    private String sh(String cmd) {
        try {
            Process p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
            String out = new BufferedReader(new InputStreamReader(p.getInputStream())).lines().collect(Collectors.joining("\n"));
            p.waitFor(15, TimeUnit.SECONDS);
            return out;
        } catch (Exception e) {
            return "";
        }
    }

    private String getPublicIP() {
        try {
            return HTTP.send(HttpRequest.newBuilder(URI.create("http://ipv4.ip.sb")).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body().trim();
        } catch (Exception e) { return "127.0.0.1"; }
    }

    private String getISP() {
        try {
            var r = HTTP.send(HttpRequest.newBuilder(URI.create("https://api.ip.sb/geoip"))
                    .timeout(Duration.ofSeconds(5)).header("User-Agent", "Mozilla/5.0").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            String cc = findJson(r.body(), "country_code");
            String isp = findJson(r.body(), "isp");
            if (cc != null && isp != null) return (cc + "-" + isp).replace(' ', '_');
        } catch (Exception ignored) {}
        try {
            var r = HTTP.send(HttpRequest.newBuilder(URI.create("http://ip-api.com/json"))
                    .timeout(Duration.ofSeconds(5)).header("User-Agent", "Mozilla/5.0").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            String cc = findJson(r.body(), "countryCode");
            String org = findJson(r.body(), "org");
            if (cc != null && org != null) return (cc + "-" + org).replace(' ', '_');
        } catch (Exception ignored) {}
        return "Unknown";
    }

    private String findJson(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    // ===== 下载 sing-box =====
    private void downloadSingBox(Path bin) throws Exception {
        String arch = System.getProperty("os.arch").toLowerCase().contains("arm") ? "arm64" : "amd64";
        String ver = "1.12.12";
        try {
            var r = HTTP.send(HttpRequest.newBuilder(URI.create("https://api.github.com/repos/SagerNet/sing-box/releases/latest"))
                    .timeout(Duration.ofSeconds(5)).header("Accept", "application/vnd.github.v3+json").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            int i = r.body().indexOf("\"tag_name\":\"v");
            if (i != -1) ver = r.body().substring(i + 13, r.body().indexOf("\"", i + 13));
        } catch (Exception ignored) {}
        String url = "https://github.com/SagerNet/sing-box/releases/download/v" + ver + "/sing-box-" + ver + "-linux-" + arch + ".tar.gz";
        getLogger().info("⬇️ 下载 sing-box: " + url);
        sh("curl -L -o \"" + baseDir + "/sb.tar.gz\" \"" + url + "\"");
        sh("cd \"" + baseDir + "\" && tar -xzf sb.tar.gz 2>/dev/null; find . -type f -name 'sing-box' -exec mv {} ./sb \\; 2>/dev/null; chmod +x sb 2>/dev/null");
        if (!Files.exists(bin)) throw new IOException("sing-box 下载失败");
        try { Files.deleteIfExists(baseDir.resolve("sb.tar.gz")); } catch (IOException ignored) {}
    }

    // ===== 配置生成 =====
    private String buildConfig(String certPath, String keyPath, boolean argoEnabled, String argoPort) {
        List<Object> inbounds = new ArrayList<>();

        // Argo 专用 VMess WebSocket（只监听本地，由 Argo 转发）
        if (argoEnabled) {
            int aPort = argoPort.isEmpty() ? 8001 : Integer.parseInt(argoPort);
            inbounds.add(mapOf(
                    "type", "vmess",
                    "tag", "vmess-argo-in",
                    "listen", "127.0.0.1",
                    "listen_port", aPort,
                    "users", listOf(mapOf("uuid", uuid)),
                    "transport", mapOf("type", "ws", "path", "/vmess-argo", "early_data_header_name", "Sec-WebSocket-Protocol")
            ));
        }

        // Hysteria2（UDP）
        if (!hy2Port.isEmpty()) {
            inbounds.add(mapOf(
                    "type", "hysteria2",
                    "tag", "hysteria-in",
                    "listen", "::",
                    "listen_port", Integer.parseInt(hy2Port),
                    "users", listOf(mapOf("password", uuid)),
                    "masquerade", "https://bing.com",
                    "tls", mapOf("enabled", true, "alpn", listOf("h3"), "certificate_path", certPath, "key_path", keyPath)
            ));
        }

        // VLESS Reality（TCP, TLS 指纹伪装）
        if (!realityPort.isEmpty()) {
            inbounds.add(mapOf(
                    "type", "vless",
                    "tag", "vless-reality",
                    "listen", "::",
                    "listen_port", Integer.parseInt(realityPort),
                    "users", listOf(mapOf("uuid", uuid, "flow", "xtls-rprx-vision")),
                    "tls", mapOf(
                            "enabled", true,
                            "server_name", sni,
                            "reality", mapOf(
                                    "enabled", true,
                                    "handshake", mapOf("server", sni, "server_port", 443),
                                    "private_key", privateKey,
                                    "short_id", listOf("")
                            )
                    )
            ));
        }

        // VMess + WebSocket + TLS（TCP/HTTPS，最隐蔽）
        if (!vmessWsPort.isEmpty()) {
            inbounds.add(mapOf(
                    "type", "vmess",
                    "tag", "vmess-ws-in",
                    "listen", "::",
                    "listen_port", Integer.parseInt(vmessWsPort),
                    "users", listOf(mapOf("uuid", uuid)),
                    "tls", mapOf("enabled", true, "server_name", sni, "certificate_path", certPath, "key_path", keyPath),
                    "transport", mapOf("type", "ws", "path", "/vmess")
            ));
        }

        // VLESS + WebSocket + TLS（TCP/HTTPS，最隐蔽）
        if (!vlessWsPort.isEmpty()) {
            inbounds.add(mapOf(
                    "type", "vless",
                    "tag", "vless-ws-in",
                    "listen", "::",
                    "listen_port", Integer.parseInt(vlessWsPort),
                    "users", listOf(mapOf("uuid", uuid)),
                    "tls", mapOf("enabled", true, "server_name", sni, "certificate_path", certPath, "key_path", keyPath),
                    "transport", mapOf("type", "ws", "path", "/vless")
            ));
        }

        // NaiveProxy（HTTP/2 伪装）
        if (!naivePort.isEmpty()) {
            inbounds.add(mapOf(
                    "type", "naive",
                    "tag", "naive-in",
                    "listen", "::",
                    "listen_port", Integer.parseInt(naivePort),
                    "users", listOf(mapOf("username", uuid.substring(0, 8), "password", uuid.substring(0, 12))),
                    "tls", mapOf("enabled", true, "server_name", sni, "certificate_path", certPath, "key_path", keyPath)
            ));
        }

        // AnyTLS（轻量 TLS 隧道）
        if (!anytlsPort.isEmpty()) {
            inbounds.add(mapOf(
                    "type", "anytls",
                    "tag", "anytls-in",
                    "listen", "::",
                    "listen_port", Integer.parseInt(anytlsPort),
                    "users", listOf(mapOf("password", uuid)),
                    "tls", mapOf("enabled", true, "certificate_path", certPath, "key_path", keyPath)
            ));
        }

        // TUIC（UDP）
        if (!tuicPort.isEmpty()) {
            inbounds.add(mapOf(
                    "type", "tuic",
                    "tag", "tuic-in",
                    "listen", "::",
                    "listen_port", Integer.parseInt(tuicPort),
                    "users", listOf(mapOf("uuid", uuid, "password", uuid)),
                    "congestion_control", "bbr",
                    "tls", mapOf("enabled", true, "alpn", listOf("h3"), "certificate_path", certPath, "key_path", keyPath)
            ));
        }

        return toJson(mapOf(
                "log", mapOf("disabled", true, "level", "error", "timestamp", true),
                "inbounds", inbounds,
                "outbounds", listOf(mapOf("type", "direct", "tag", "direct"))
        ));
    }

    // ===== 节点生成 =====
    private String buildNodes(String ip, String nodeName, String argoCfip) {
        List<String> nodes = new ArrayList<>();
        if (!realityPort.isEmpty())
            nodes.add("vless://" + uuid + "@" + ip + ":" + realityPort + "?encryption=none&flow=xtls-rprx-vision&security=reality&sni=" + sni + "&fp=firefox&pbk=" + publicKey + "&type=tcp&headerType=none#" + nodeName + "-Reality");
        if (!hy2Port.isEmpty())
            nodes.add("hysteria2://" + uuid + "@" + ip + ":" + hy2Port + "/?sni=www.bing.com&insecure=1&alpn=h3&obfs=none#" + nodeName + "-HY2");
        if (!vmessWsPort.isEmpty()) {
            String vmessJson = "{\"v\":\"2\",\"ps\":\"" + nodeName + "-VMess\",\"add\":\"" + ip + "\",\"port\":\"" + vmessWsPort + "\",\"id\":\"" + uuid + "\",\"aid\":\"0\",\"scy\":\"auto\",\"net\":\"ws\",\"type\":\"none\",\"host\":\"\",\"path\":\"/vmess\",\"tls\":\"tls\",\"sni\":\"" + sni + "\",\"alpn\":\"h2\",\"fp\":\"chrome\",\"allowInsecure\":1}";
            nodes.add("vmess://" + Base64.getEncoder().encodeToString(vmessJson.getBytes(StandardCharsets.UTF_8)));
        }
        if (!vlessWsPort.isEmpty())
            nodes.add("vless://" + uuid + "@" + ip + ":" + vlessWsPort + "?encryption=none&security=tls&sni=" + sni + "&type=ws&host=" + sni + "&path=/vless&fp=chrome&alpn=h2&allowInsecure=1#" + nodeName + "-VLESS-WS");
        if (!naivePort.isEmpty())
            nodes.add("naive://" + uuid.substring(0, 8) + ":" + uuid.substring(0, 12) + "@" + ip + ":" + naivePort + "?sni=" + sni + "#" + nodeName + "-Naive");
        if (!anytlsPort.isEmpty())
            nodes.add("anytls://" + uuid + "@" + ip + ":" + anytlsPort + "?sni=" + sni + "&insecure=1#" + nodeName + "-AnyTLS");
        if (!tuicPort.isEmpty())
            nodes.add("tuic://" + uuid + ":" + uuid + "@" + ip + ":" + tuicPort + "?sni=" + sni + "&alpn=h3&congestion_control=bbr&allowInsecure=1#" + nodeName + "-TUIC");
        // Argo 隧道节点（通过 Cloudflare）
        if (!argoUrl.isEmpty()) {
            String vmessArgo = "{\"v\":\"2\",\"ps\":\"" + nodeName + "-Argo\",\"add\":\"" + argoCfip + "\",\"port\":\"443\",\"id\":\"" + uuid + "\",\"aid\":\"0\",\"scy\":\"auto\",\"net\":\"ws\",\"type\":\"none\",\"host\":\"" + argoUrl + "\",\"path\":\"/vmess-argo?ed=2560\",\"tls\":\"tls\",\"sni\":\"" + argoUrl + "\",\"alpn\":\"\",\"fp\":\"firefox\"}";
            nodes.add("vmess://" + Base64.getEncoder().encodeToString(vmessArgo.getBytes(StandardCharsets.UTF_8)));
        }
        return String.join("\n", nodes);
    }

    // ===== Telegram =====
    private void sendTelegram(String botToken, String chatId, String text) {
        try {
            String form = "chat_id=" + URLEncoder.encode(chatId, StandardCharsets.UTF_8)
                    + "&text=" + URLEncoder.encode(text, StandardCharsets.UTF_8)
                    + "&parse_mode=HTML";
            HTTP.send(HttpRequest.newBuilder(URI.create("https://api.telegram.org/bot" + botToken + "/sendMessage"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build(), HttpResponse.BodyHandlers.discarding());
            getLogger().info("📨 Telegram 推送成功");
        } catch (Exception e) {
            getLogger().warning("⚠️ Telegram 推送失败: " + e.getMessage());
        }
    }

    // ===== JSON 工具 =====
    private String toJson(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return "\"" + escapeJson((String) value) + "\"";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map) {
            return ((Map<?, ?>) value).entrySet().stream()
                    .map(e -> toJson(String.valueOf(e.getKey())) + ":" + toJson(e.getValue()))
                    .collect(Collectors.joining(",", "{", "}"));
        }
        if (value instanceof Iterable) {
            List<String> items = new ArrayList<>();
            for (Object item : (Iterable<?>) value) items.add(toJson(item));
            return String.join(",", items).replaceFirst("^", "[") + "]";
        }
        return toJson(String.valueOf(value));
    }

    private String escapeJson(String value) {
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default: out.append(c);
            }
        }
        return out.toString();
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) map.put(String.valueOf(values[i]), values[i + 1]);
        return map;
    }

    private List<Object> listOf(Object... values) {
        return new ArrayList<>(List.of(values));
    }
}