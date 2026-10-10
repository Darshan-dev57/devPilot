package devPilot.backend.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A tiny stand-in for api.github.com: one public repo (acme/demo) whose commits the test
 * controls, one private repo, and 404 for everything else. Real HTTP, real zip archives.
 */
public class FakeGitHub implements AutoCloseable {

    private final HttpServer server;
    private final Map<String, Map<String, String>> commits = new LinkedHashMap<>();
    private volatile String head;
    public final AtomicInteger zipDownloads = new AtomicInteger();

    public FakeGitHub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Adds a commit with exactly these files and makes it the head of main. */
    public synchronized String commit(Map<String, String> files) {
        String sha = String.format("%040d", commits.size() + 1);
        commits.put(sha, new LinkedHashMap<>(files));
        head = sha;
        return sha;
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        try {
            if (path.equals("/repos/acme/demo")) {
                json(exchange, 200, "{\"id\":42,\"full_name\":\"acme/demo\",\"private\":false,"
                        + "\"default_branch\":\"main\",\"language\":\"Java\",\"html_url\":\"https://github.com/acme/demo\"}");
            } else if (path.equals("/repos/acme/secret")) {
                json(exchange, 200, "{\"id\":43,\"full_name\":\"acme/secret\",\"private\":true,\"default_branch\":\"main\"}");
            } else if (path.equals("/repos/acme/demo/commits/main")) {
                json(exchange, 200, "{\"sha\":\"" + head + "\"}");
            } else if (path.startsWith("/repos/acme/demo/compare/")) {
                String[] range = path.substring("/repos/acme/demo/compare/".length()).split("\\.\\.\\.");
                json(exchange, 200, compare(commits.get(range[0]), commits.get(range[1])));
            } else if (path.startsWith("/repos/acme/demo/zipball/")) {
                zipDownloads.incrementAndGet();
                String sha = path.substring(path.lastIndexOf('/') + 1);
                byte[] body = zip(sha, commits.get(sha));
                exchange.getResponseHeaders().add("Content-Type", "application/zip");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            } else {
                json(exchange, 404, "{\"message\":\"Not Found\"}");
            }
        } finally {
            exchange.close();
        }
    }

    private static String compare(Map<String, String> base, Map<String, String> head) {
        List<String> files = new ArrayList<>();
        for (var entry : head.entrySet()) {
            if (!base.containsKey(entry.getKey())) {
                files.add(file(entry.getKey(), "added"));
            } else if (!base.get(entry.getKey()).equals(entry.getValue())) {
                files.add(file(entry.getKey(), "modified"));
            }
        }
        for (String path : base.keySet()) {
            if (!head.containsKey(path)) {
                files.add(file(path, "removed"));
            }
        }
        return "{\"status\":\"ahead\",\"files\":[" + String.join(",", files) + "]}";
    }

    private static String file(String path, String status) {
        return "{\"filename\":\"" + path + "\",\"status\":\"" + status + "\"}";
    }

    private static byte[] zip(String sha, Map<String, String> files) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (var entry : files.entrySet()) {
                zip.putNextEntry(new ZipEntry("acme-demo-" + sha.substring(0, 7) + "/" + entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
