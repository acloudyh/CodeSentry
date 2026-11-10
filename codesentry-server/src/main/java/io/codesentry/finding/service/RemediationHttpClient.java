package io.codesentry.finding.service;

import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;
import java.io.*;
import java.net.*;
import java.util.Map;
import java.util.zip.*;

/** Fixed public endpoints only. No project files, source code or credentials leave the host. */
@Component
public class RemediationHttpClient {
    private final ObjectMapper json;
    public RemediationHttpClient(ObjectMapper json) { this.json = json; }

    public JsonNode query(String coordinate, String version) throws Exception {
        String body = json.writeValueAsString(Map.of("package", Map.of("ecosystem", "Maven", "name", coordinate), "version", version));
        return json.readTree(fetch("https://api.osv.dev/v1/query", body, 4 * 1024 * 1024));
    }

    public Integer minimumJava(String coordinate, String version) throws Exception {
        String url = artifactUrl(coordinate, version);
        byte[] archive = fetch(url, null, 20 * 1024 * 1024);
        return classJava(archive);
    }

    public static String artifactUrl(String coordinate, String version) {
        if (!coordinate.matches("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*:[A-Za-z0-9_.-]+")
                || !version.matches("[0-9]+(?:\\.[0-9]+)*(?:(?:\\.Final|\\.RELEASE)|-(?:jre|android))?"))
            throw new IllegalArgumentException("无法核实的 Maven 坐标或版本");
        String[] parts = coordinate.split(":");
        return "https://repo.maven.apache.org/maven2/" + parts[0].replace('.', '/') + "/" + parts[1]
                + "/" + version + "/" + parts[1] + "-" + version + ".jar";
    }

    static Integer classJava(byte[] archive) throws IOException {
        int major = 0, entries = 0;
        long expanded = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 50000) throw new IOException("发布包条目过多，兼容性未核实");
                // Optional multi-release classes are ignored; base module-info is a descriptor.
                boolean selected = entry.getName().endsWith(".class") && !entry.getName().startsWith("META-INF/")
                        && !entry.getName().endsWith("module-info.class");
                byte[] header = selected ? zip.readNBytes(8) : new byte[0];
                if (selected) {
                    if (header.length != 8 || header[0] != (byte) 0xca || header[1] != (byte) 0xfe
                            || header[2] != (byte) 0xba || header[3] != (byte) 0xbe) throw new IOException("无效的 Java 类文件");
                    major = Math.max(major, ((header[6] & 255) << 8) | (header[7] & 255));
                }
                int count;
                while ((count = zip.read(buffer)) != -1) {
                    expanded += count;
                    if (expanded > 100L * 1024 * 1024) throw new IOException("发布包解压超限，兼容性未核实");
                }
            }
        }
        return major < 45 ? null : major - 44;
    }

    private byte[] fetch(String url, String body, int limit) throws Exception {
        io.codesentry.process.CancellableTasks.check();
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(4000); connection.setReadTimeout(6000);
        connection.setInstanceFollowRedirects(false);
        try {
            if (body != null) {
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                try (OutputStream output = connection.getOutputStream()) { output.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            }
            if (connection.getResponseCode() != 200) throw new IOException("远程服务返回 HTTP " + connection.getResponseCode());
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int count;
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(body == null ? 60 : 10);
                while ((count = input.read(buffer)) != -1) {
                    io.codesentry.process.CancellableTasks.check();
                    if (output.size() + count > limit || System.nanoTime() > deadline) throw new IOException("远程响应超时或超过大小限制");
                    output.write(buffer, 0, count);
                }
                return output.toByteArray();
            }
        } finally { connection.disconnect(); }
    }
}
