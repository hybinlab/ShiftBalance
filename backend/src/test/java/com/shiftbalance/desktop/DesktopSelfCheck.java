package com.shiftbalance.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Run with -ea; optional --postgres-bin exercises an isolated, disposable local cluster. */
public final class DesktopSelfCheck {
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        Path backend = Path.of("backend").toAbsolutePath();
        if (!Files.isDirectory(backend.resolve("src"))) backend = Path.of("").toAbsolutePath();
        Path scripts = backend.resolve("src/main/resources/db");
        String checksum = "a".repeat(64);
        assert DesktopRuntime.migrationChecksum("BEGIN;\nCOMMIT;\n")
                .equals(DesktopRuntime.migrationChecksum("BEGIN;\r\nCOMMIT;\r\n"));
        for (String file : DesktopRuntime.MIGRATIONS) {
            String original = Files.readString(scripts.resolve(file));
            String wrapped = DesktopRuntime.migrationScript(file, original, checksum);
            assert wrapped.startsWith("BEGIN;") : file;
            assert wrapped.indexOf("INSERT INTO desktop_schema_migration VALUES") < wrapped.lastIndexOf("COMMIT;") : file;
            assert wrapped.substring(wrapped.lastIndexOf("COMMIT;") + 7).isBlank() : file;
        }
        assert !DesktopRuntime.randomPassword().equals(DesktopRuntime.randomPassword());
        assert DesktopRuntime.randomPassword().matches("[A-Za-z0-9_-]{43}");
        try (ServerSocket occupied = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            assert DesktopRuntime.freePort(occupied.getLocalPort()) != occupied.getLocalPort();
        }
        if (args.length == 0) { System.out.println("Desktop bootstrap checks passed; add --postgres-bin for lifecycle/database checks."); return; }
        assert args.length == 2 && args[0].equals("--postgres-bin");
        Path binaries = Path.of(args[1]).toAbsolutePath();
        Path target = Files.createDirectories(backend.resolve("target"));
        Path fixture = Files.createTempDirectory(target, "desktop-self-check-");
        Path payload;
        if (System.getProperty("os.name").startsWith("Windows")) {
            payload = binaries.getParent().getParent(); // prepared Windows bundle already contains db/ and web/
        } else {
            payload = Files.createDirectories(fixture.resolve("payload"));
            Files.createSymbolicLink(payload.resolve("pgsql"), binaries.getParent());
            Files.createSymbolicLink(payload.resolve("db"), scripts);
            Files.createDirectory(payload.resolve("web"));
            Files.writeString(payload.resolve("web/index.html"), "<!doctype html><title>Desktop check</title>");
        }
        Path root = Files.createDirectories(fixture.resolve("用户 数据"));
        Files.writeString(root.resolve(".env.local"), "SHIFTBALANCE_DB_URL=jdbc:postgresql://invalid.invalid/remote\n");
        String backup;
        try (Instance first = new Instance(root, payload, "admin", "local-check-password")) {
            String url = first.await("ready");
            HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
            assert get(client, url, "").statusCode() == 200 : "Login UI must be public";
            assert get(client, url, "api/people").statusCode() == 401 : "Business data must remain protected";
            assert client.send(HttpRequest.newBuilder(URI.create(url + "api/auth/session")).header("Origin", "http://localhost:5173")
                    .GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode() == 403 : "Desktop must reject cross-origin access";
            login(client, url, "admin");
            assert get(client, url, "api/groups").body().contains("A组");
            assert get(client, url, "api/people").body().equals("[]") : "No production personnel may be bundled";
            assert get(client, url, "api/schedules/time-slots").statusCode() == 200;
            assert get(client, url, "api/schedules/requirements-template").statusCode() == 200;
            first.send(Map.of("command", "backup"));
            backup = first.await("backup");
            assert Files.size(Path.of(backup)) > 0;
            put(client, url, "api/auth/profile", "{\"username\":\"renamed\"}");
            assert get(client, url, "api/auth/session").body().contains("renamed");
            first.send(Map.of("command", "restore", "path", backup));
            url = first.await("ready");
            login(client, url, "admin");
            assert get(client, url, "api/auth/session").body().contains("admin") : "Restore must bring back the original account";
            // A trusted Web export lacks the desktop ledger; importing it must preserve business data and accounts.
            Path serverArchive = root.resolve("backups/server-export.dump");
            databaseTool(binaries, root, "pg_dump", "--format=custom", "--no-owner", "--no-acl",
                    "--exclude-table=desktop_schema_migration", "--file=" + serverArchive, "shiftbalance");
            first.send(Map.of("command", "restore", "path", serverArchive.toString()));
            url = first.await("ready");
            login(client, url, "admin");
            assert get(client, url, "api/schedules/requirements-template").statusCode() == 200;
            Path broken = root.resolve("backups/broken.dump");
            Files.writeString(broken, "not a PostgreSQL archive");
            first.send(Map.of("command", "restore", "path", broken.toString()));
            first.await("error");
            assert get(client, url, "api/groups").statusCode() == 200 : "A corrupt archive must not stop the current backend";
            assert Files.exists(root.resolve("config/initialized"));
            try (var backups = Files.list(root.resolve("backups"))) {
                assert backups.anyMatch(path -> path.getFileName().toString().startsWith("shiftbalance-before-restore"));
            }
            // Simulate one pending migration; reopening must make an upgrade backup before applying it.
            databaseTool(binaries, root, "psql", "-X", "-v", "ON_ERROR_STOP=1", "-d", "shiftbalance", "-c",
                    "DELETE FROM desktop_schema_migration WHERE name='migration_v15_configurable_rules.sql'");
        }
        assert !Files.exists(root.resolve("data/postgres/postmaster.pid")) : "Database must stop with the desktop";
        // Reopening must not need bootstrap credentials or overwrite the existing account.
        try (Instance second = new Instance(root, payload, "", "")) {
            String url = second.await("ready");
            HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
            login(client, url, "admin");
            assert get(client, url, "api/auth/session").body().contains("admin");
            second.send(Map.of("command", "backup"));
            assert Files.size(Path.of(second.await("backup"))) > 0;
            try (var backups = Files.list(root.resolve("backups"))) {
                assert backups.anyMatch(path -> path.getFileName().toString().startsWith("shiftbalance-before-upgrade"));
            }
        }
        assert !Files.exists(root.resolve("data/postgres/postmaster.pid"));
        System.out.println("Desktop lifecycle checks passed: isolated UTF-8 directory, migrations, auth, backup, restore, server import, invalid archive, upgrade backup, restart, clean shutdown. Fixture: " + fixture);
    }

    private static void databaseTool(Path binaries, Path root, String tool, String... arguments) throws Exception {
        Properties settings = new Properties();
        try (Reader reader = Files.newBufferedReader(root.resolve("config/database.properties"))) { settings.load(reader); }
        String port = Files.readAllLines(root.resolve("data/postgres/postmaster.pid")).get(3);
        String extension = System.getProperty("os.name").startsWith("Windows") ? ".exe" : "";
        List<String> command = new ArrayList<>(List.of(binaries.resolve(tool + extension).toString(), "-h", "127.0.0.1", "-p", port, "-U", "shiftbalance"));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(root.resolve("tool-check.log").toFile());
        builder.environment().put("PGPASSWORD", settings.getProperty("password"));
        Process process = builder.start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError(tool + " timed out"); }
        assert process.exitValue() == 0 : Files.readString(root.resolve("tool-check.log"));
    }

    private static HttpResponse<String> get(HttpClient client, String url, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url + path)).timeout(java.time.Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private static void login(HttpClient client, String url, String username) throws Exception {
        String csrf = JSON.readTree(get(client, url, "api/auth/csrf").body()).path("csrfToken").asText();
        var response = client.send(HttpRequest.newBuilder(URI.create(url + "api/auth/login"))
                .header("Content-Type", "application/json").header("X-CSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("username", username, "password", "local-check-password"))))
                .build(), HttpResponse.BodyHandlers.ofString());
        assert response.statusCode() == 200 : response.body();
    }
    private static void put(HttpClient client, String url, String path, String body) throws Exception {
        String csrf = JSON.readTree(get(client, url, "api/auth/csrf").body()).path("csrfToken").asText();
        var response = client.send(HttpRequest.newBuilder(URI.create(url + path))
                .header("Content-Type", "application/json").header("X-CSRF-TOKEN", csrf).PUT(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assert response.statusCode() == 200 : response.body();
    }

    private static final class Instance implements AutoCloseable {
        final Process process;
        final Writer input;
        final BlockingQueue<JsonNode> events = new LinkedBlockingQueue<>();
        final Path root;
        Instance(Path root, Path payload, String username, String password) throws Exception {
            this.root = root;
            String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
            ProcessBuilder builder = new ProcessBuilder(java, "-Dfile.encoding=UTF-8", "-cp", absoluteClasspath(), "com.shiftbalance.ShiftBalanceApplication");
            builder.directory(root.toFile()).redirectError(ProcessBuilder.Redirect.appendTo(root.resolve("check-errors.log").toFile()));
            builder.environment().put("SHIFTBALANCE_DESKTOP_ROOT", root.toString());
            builder.environment().put("SHIFTBALANCE_DESKTOP_PAYLOAD", payload.toString());
            builder.environment().put("SHIFTBALANCE_DB_URL", "jdbc:postgresql://invalid.invalid/remote");
            builder.environment().put("SHIFTBALANCE_ADMIN_PASSWORD", "should-not-be-used");
            process = builder.start();
            input = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
            send(Map.of("username", username, "password", password));
            Thread.ofPlatform().daemon().start(() -> {
                try (BufferedReader reader = process.inputReader(StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("SHIFTBALANCE_DESKTOP ")) events.add(JSON.readTree(line.substring("SHIFTBALANCE_DESKTOP ".length())));
                    }
                } catch (Exception error) { throw new RuntimeException(error); }
                events.add(JSON.createObjectNode().put("type", "exit").put("message", "See check-errors.log"));
            });
        }
        void send(Map<String, String> command) throws Exception { input.write(JSON.writeValueAsString(command) + "\n"); input.flush(); }
        String await(String type) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
            while (System.nanoTime() < deadline) {
                JsonNode event = events.poll(5, TimeUnit.SECONDS);
                if (event == null) continue;
                String actual = event.path("type").asText();
                if (type.equals(actual)) return event.path("message").asText();
                if (actual.equals("error") || actual.equals("exit")) {
                    for (String file : List.of("check-errors.log", "logs/last-database-error.log", "logs/postgres.log")) {
                        Path log = root.resolve(file);
                        if (Files.exists(log)) System.err.println(file + "\n" + Files.readString(log));
                    }
                    throw new AssertionError(event.toString());
                }
            }
            throw new AssertionError("Timed out waiting for " + type);
        }
        public void close() throws Exception {
            input.close(); // EOF is the same cleanup path as a crashed Tauri parent.
            if (!process.waitFor(100, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("Desktop did not stop cleanly"); }
            assert process.exitValue() == 0 : "Desktop exit code " + process.exitValue();
        }
        private static String absoluteClasspath() {
            return Arrays.stream(System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator)))
                    .map(path -> Path.of(path).toAbsolutePath().toString()).collect(java.util.stream.Collectors.joining(File.pathSeparator));
        }
    }
}
