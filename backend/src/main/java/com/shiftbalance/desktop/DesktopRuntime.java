package com.shiftbalance.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shiftbalance.ShiftBalanceApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Local-only bootstrap. The server deployment never enters this class. */
public final class DesktopRuntime {
    static final List<String> MIGRATIONS = List.of(
            "schema_v1.sql", "schema_v2_requirement_template.sql", "schema_v3_terminal_supervisor.sql",
            "seed_v3_requirement_template.sql", "migration_v4_derived_terminal_rest_group_flow.sql",
            "migration_v5_next_day_breakfast_handoff.sql", "migration_v6_next_day_breakfast_meal.sql",
            "migration_v7_next_day_early_staffing.sql", "migration_v8_fixed_person_rules.sql",
            "migration_v9_power_bank_rotation.sql", "migration_v10_auth_users.sql", "migration_v11_user_profile.sql",
            "migration_v12_overnight_parallel_assignment.sql", "migration_v13_meal_three_people.sql",
            "migration_v14_power_bank_same_group.sql", "migration_v15_configurable_rules.sql");
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path root;
    private final Path payload;
    private final Path cluster;
    private final Properties settings = new Properties();
    private ConfigurableApplicationContext context;
    private boolean databaseStarted;
    private int port;
    private String adminUsername;
    private String adminPassword;

    DesktopRuntime(Path root, Path payload) {
        this.root = root.toAbsolutePath().normalize();
        this.payload = payload.toAbsolutePath().normalize();
        this.cluster = this.root.resolve("data/postgres");
    }

    public static void run() throws Exception {
        // Windows console code pages must not corrupt the UTF-8 pipe protocol or redirected diagnostics.
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        DesktopRuntime runtime = new DesktopRuntime(Path.of(System.getenv("SHIFTBALANCE_DESKTOP_ROOT")),
                Path.of(System.getenv("SHIFTBALANCE_DESKTOP_PAYLOAD")));
        // Credentials arrive on the private pipe, never in arguments, environment, or logs.
        try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String first = input.readLine();
            if (first == null) return;
            var initial = JSON.readTree(first);
            runtime.adminUsername = initial.path("username").asText();
            runtime.adminPassword = initial.path("password").asText();
            runtime.initializeDirectories();
            try (FileChannel channel = FileChannel.open(runtime.root.resolve("data/desktop.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock lock = channel.tryLock()) {
                if (lock == null) throw new IOException("该数据目录已由另一个排班程序使用。");
                Thread hook = new Thread(runtime::stop, "desktop-shutdown");
                Runtime.getRuntime().addShutdownHook(hook);
                try {
                    runtime.startDatabase();
                    runtime.migrate();
                    runtime.startWeb();
                    String line;
                    while ((line = input.readLine()) != null) {
                        var command = JSON.readTree(line);
                        try {
                            switch (command.path("command").asText()) {
                                case "backup" -> event("backup", runtime.backup("manual").toString());
                                case "restore" -> runtime.restore(Path.of(command.path("path").asText()));
                                case "shutdown" -> { return; }
                                default -> throw new IOException("未知桌面操作。");
                            }
                        } catch (Exception error) {
                            error.printStackTrace(System.err);
                            event("error", error.getMessage());
                        }
                    }
                } finally {
                    runtime.stop();
                    Runtime.getRuntime().removeShutdownHook(hook);
                }
            }
        }
    }

    private void initializeDirectories() throws IOException {
        for (String directory : List.of("data", "data/tmp", "config", "backups", "exports", "logs")) {
            Path path = Files.createDirectories(root.resolve(directory));
            restrictToOwner(path);
        }
        Path configuration = root.resolve("config/database.properties");
        if (Files.exists(configuration)) {
            try (Reader reader = Files.newBufferedReader(configuration)) { settings.load(reader); }
            if (settings.getProperty("password", "").isBlank()) throw new IOException("本地数据库配置损坏，请从备份恢复。");
        } else {
            if (Files.exists(cluster)) throw new IOException("数据库目录已存在但凭据缺失；请恢复 config 目录，不能重新初始化。");
            if (adminUsername.isBlank() || adminUsername.length() > 100 || adminPassword.getBytes(StandardCharsets.UTF_8).length < 8
                    || adminPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
                throw new IOException("首次使用需要管理员账号和 8–72 字节的密码。");
            }
            settings.setProperty("password", randomPassword());
            saveSettings();
        }
    }

    private void saveSettings() throws IOException {
        Path target = root.resolve("config/database.properties");
        Path temporary = Files.createTempFile(target.getParent(), "database-", ".properties");
        restrictToOwner(temporary);
        try {
            try (Writer writer = Files.newBufferedWriter(temporary)) { settings.store(writer, "ShiftBalance local database"); }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    private void startDatabase() throws Exception {
        event("status", "正在准备本地数据库…");
        String binaryVersion = execute("postgres", false, "--version");
        if (Files.exists(cluster.resolve("PG_VERSION"))) {
            String major = Files.readString(cluster.resolve("PG_VERSION")).trim();
            if (!binaryVersion.matches("(?s).*\\b" + java.util.regex.Pattern.quote(major) + "\\.\\d+.*")) {
                throw new IOException("数据库主版本不匹配，已停止启动以保护数据。请使用原版本导出备份后迁移。");
            }
            // Recover only our own cluster after a previous abnormal desktop exit.
            if (pgRunning()) execute("pg_ctl", false, "stop", "-D", cluster.toString(), "-m", "fast", "-w", "-t", "60");
        } else {
            if (Files.exists(cluster)) throw new IOException("发现未完成的数据库初始化，请保留目录并检查 logs，勿覆盖现有数据。");
            Path secret = Files.createTempFile(root.resolve("config"), "init-", ".secret");
            restrictToOwner(secret);
            try {
                Files.writeString(secret, settings.getProperty("password"), StandardCharsets.UTF_8);
                execute("initdb", false, "-D", cluster.toString(), "-U", "shiftbalance", "--encoding=UTF8",
                        "--locale=C", "--auth=scram-sha-256", "--pwfile=" + secret);
            } finally { Files.deleteIfExists(secret); }
        }
        port = freePort(0);
        databaseStarted = true; // Cleanup also runs if pg_ctl times out after starting the server.
        execute("pg_ctl", false, "start", "-D", cluster.toString(), "-l", root.resolve("logs/postgres.log").toString(),
                "-w", "-t", "60", "-o", "-h 127.0.0.1 -p " + port + " -c unix_socket_directories=");
        if (sql("postgres", "SELECT 1 FROM pg_database WHERE datname='shiftbalance'").isBlank()) {
            execute("createdb", true, "--encoding=UTF8", "--template=template0", "shiftbalance");
        }
    }

    private boolean pgRunning() throws Exception {
        ProcessBuilder builder = process("pg_ctl", false, "status", "-D", cluster.toString());
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = builder.start();
        if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("数据库状态检查超时。"); }
        if (process.exitValue() != 0 && process.exitValue() != 3) throw new IOException("无法检查本地数据库状态。");
        return process.exitValue() == 0;
    }

    private void migrate() throws Exception {
        sql("shiftbalance", "CREATE TABLE IF NOT EXISTS desktop_schema_migration (name TEXT PRIMARY KEY, checksum TEXT NOT NULL)");
        List<String> applied = sql("shiftbalance", "SELECT name || ':' || checksum FROM desktop_schema_migration ORDER BY name")
                .lines().filter(line -> !line.isBlank()).toList();
        Map<String, String> checksums = new HashMap<>();
        for (String entry : applied) {
            String[] pair = entry.split(":", 2);
            if (!MIGRATIONS.contains(pair[0])) throw new IOException("数据由较新版本创建，请使用较新程序打开。");
            checksums.put(pair[0], pair[1]);
        }
        boolean backedUp = false;
        for (String name : MIGRATIONS) {
            String source = Files.readString(payload.resolve("db").resolve(name));
            String checksum = migrationChecksum(source);
            if (checksums.containsKey(name)) {
                if (!checksum.equals(checksums.get(name))) throw new IOException("数据库升级脚本已改变：" + name + "；已停止升级。");
                continue;
            }
            if (!backedUp && !checksums.isEmpty()) { backup("before-upgrade"); backedUp = true; }
            event("status", "正在初始化或升级数据：" + name);
            Path script = Files.createTempFile(root.resolve("config"), "migration-", ".sql");
            try {
                Files.writeString(script, migrationScript(name, source, checksum));
                execute("psql", true, "-X", "-v", "ON_ERROR_STOP=1", "-d", "shiftbalance", "-f", script.toString());
            } finally { Files.deleteIfExists(script); }
        }
    }

    static String migrationScript(String name, String source, String checksum) {
        if (!MIGRATIONS.contains(name) || !checksum.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid migration");
        // Existing server SQL owns its transaction. Record completion in that same commit.
        int commit = source.lastIndexOf("COMMIT;");
        if (commit < 0) throw new IllegalArgumentException("Migration must end in COMMIT");
        return source.substring(0, commit) + "INSERT INTO desktop_schema_migration VALUES ('" + name + "', '" + checksum + "');\n" + source.substring(commit);
    }

    static String migrationChecksum(String source) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(source.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8)));
    }

    private void startWeb() throws Exception {
        event("status", "正在启动排班服务…");
        int webPort = freePort(Integer.parseInt(settings.getProperty("webPort", "0")));
        // Explicit command-line properties isolate this process from server .env and inherited DB settings.
        SpringApplication application = new SpringApplication(ShiftBalanceApplication.class);
        context = application.run(
                "--spring.profiles.active=desktop", "--spring.config.import=",
                "--shiftbalance.desktop=true",
                "--spring.datasource.url=jdbc:postgresql://127.0.0.1:" + port + "/shiftbalance",
                "--spring.datasource.username=shiftbalance", "--spring.datasource.password=" + settings.getProperty("password"),
                "--server.address=127.0.0.1", "--server.port=" + webPort,
                "--shiftbalance.cors.allowed-origins=",
                "--server.shutdown=graceful", "--spring.lifecycle.timeout-per-shutdown-phase=30s",
                "--server.servlet.session.cookie.secure=false",
                "--spring.web.resources.static-locations=" + payload.resolve("web").toUri(),
                "--shiftbalance.bootstrap-admin.username=" + adminUsername,
                "--shiftbalance.bootstrap-admin.password=" + adminPassword,
                "--shiftbalance.bootstrap-admin.display-name=排班管理员",
                "--logging.file.name=" + root.resolve("logs/backend.log"), "--logging.logback.rollingpolicy.max-history=7",
                "--knife4j.enable=false", "--springdoc.api-docs.enabled=false", "--springdoc.swagger-ui.enabled=false");
        settings.setProperty("webPort", Integer.toString(((ServletWebServerApplicationContext) context).getWebServer().getPort()));
        saveSettings();
        adminUsername = "";
        adminPassword = "";
        Files.writeString(root.resolve("config/initialized"), "ShiftBalance desktop\n");
        event("ready", "http://127.0.0.1:" + settings.getProperty("webPort") + "/");
    }

    private Path backup(String reason) throws Exception {
        event("status", "正在备份数据…");
        String file = "shiftbalance-" + reason + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
        Path target = root.resolve("backups").resolve(file + ".dump");
        Path partial = target.resolveSibling(file + ".partial");
        try {
            execute("pg_dump", true, "--format=custom", "--no-owner", "--no-acl", "--file=" + partial, "shiftbalance");
            Files.move(partial, target);
            return target;
        } finally { Files.deleteIfExists(partial); }
    }

    private void restore(Path archive) throws Exception {
        if (!Files.isRegularFile(archive) || !archive.getFileName().toString().endsWith(".dump")) throw new IOException("请选择 .dump 格式的数据库备份。");
        execute("pg_restore", false, "--list", archive.toAbsolutePath().toString());
        Path safety = backup("before-restore");
        event("status", "正在恢复数据，请勿关闭程序…");
        closeWeb();
        // Restore to a separate database; validate and migrate before replacing the current one.
        sql("postgres", "DROP DATABASE IF EXISTS shiftbalance_restore");
        execute("createdb", true, "--encoding=UTF8", "--template=template0", "shiftbalance_restore");
        try {
            execute("pg_restore", true, "--exit-on-error", "--single-transaction", "--no-owner", "--no-acl",
                    "-d", "shiftbalance_restore", archive.toAbsolutePath().toString());
            String version = sql("shiftbalance_restore", "SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND "
                    + "((table_name='schedule_rotation_config' AND column_name='rules') OR (table_name='app_user' AND column_name='avatar_data'))");
            if (!"2".equals(version.trim())) throw new IOException("备份不是当前版本的排班数据，请先在原系统升级至 v15 或更高版本。");
            if (!"t".equals(sql("shiftbalance_restore", "SELECT EXISTS(SELECT 1 FROM app_user WHERE enabled)").trim())) {
                throw new IOException("备份中没有可用的登录账号。");
            }
            // A server export has no desktop ledger. Mark the verified v15 server schema as the baseline.
            if (sql("shiftbalance_restore", "SELECT to_regclass('public.desktop_schema_migration') IS NOT NULL").equals("f")) {
                baselineServerArchive();
            } else {
                validateArchiveMigrations();
            }
            sql("postgres", "DROP DATABASE IF EXISTS shiftbalance_previous");
            sql("postgres", "ALTER DATABASE shiftbalance RENAME TO shiftbalance_previous");
            try { sql("postgres", "ALTER DATABASE shiftbalance_restore RENAME TO shiftbalance"); }
            catch (Exception error) {
                sql("postgres", "ALTER DATABASE shiftbalance_previous RENAME TO shiftbalance");
                throw error;
            }
        } catch (Exception error) {
            event("error", "恢复失败，当前数据已保留。恢复前备份：" + safety + "。" + error.getMessage());
        } finally {
            startWeb();
        }
    }

    private void validateArchiveMigrations() throws Exception {
        String ledger = sql("shiftbalance_restore", "SELECT name || ':' || checksum FROM desktop_schema_migration");
        Set<String> seen = new HashSet<>();
        for (String entry : ledger.lines().toList()) {
            String[] pair = entry.split(":", 2);
            if (pair.length != 2 || !MIGRATIONS.contains(pair[0])) throw new IOException("备份版本较新或升级记录损坏。");
            String checksum = migrationChecksum(Files.readString(payload.resolve("db").resolve(pair[0])));
            if (!checksum.equals(pair[1])) throw new IOException("备份升级脚本与当前程序不一致。");
            seen.add(pair[0]);
        }
        if (!seen.containsAll(MIGRATIONS)) throw new IOException("请先用原版本升级该备份，再导入当前程序。");
    }

    private void baselineServerArchive() throws Exception {
        sql("shiftbalance_restore", "CREATE TABLE desktop_schema_migration (name TEXT PRIMARY KEY, checksum TEXT NOT NULL)");
        for (String name : MIGRATIONS) {
            String checksum = migrationChecksum(Files.readString(payload.resolve("db").resolve(name)));
            sql("shiftbalance_restore", "INSERT INTO desktop_schema_migration VALUES ('" + name + "', '" + checksum + "')");
        }
    }

    private String sql(String database, String query) throws Exception {
        return execute("psql", true, "-X", "-t", "-A", "-v", "ON_ERROR_STOP=1", "-d", database, "-c", query).trim();
    }

    private ProcessBuilder process(String tool, boolean connection, String... arguments) {
        String extension = System.getProperty("os.name").startsWith("Windows") ? ".exe" : "";
        List<String> command = new ArrayList<>(List.of(payload.resolve("pgsql/bin/" + tool + extension).toString()));
        if (connection) command.addAll(List.of("-h", "127.0.0.1", "-p", Integer.toString(port), "-U", "shiftbalance"));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile());
        // Do not inherit libpq connection overrides, service files or remote credentials.
        builder.environment().keySet().removeIf(key -> key.toUpperCase(Locale.ROOT).startsWith("PG"));
        builder.environment().put("PGPASSWORD", settings.getProperty("password", ""));
        builder.environment().put("PGCLIENTENCODING", "UTF8");
        return builder;
    }

    private String execute(String tool, boolean connection, String... arguments) throws Exception {
        Path output = Files.createTempFile(root.resolve("logs"), "command-", ".log");
        try {
            Process process = process(tool, connection, arguments).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            // ponytail: local backup/restore is synchronous; revisit for databases taking over 10 minutes.
            if (!process.waitFor(10, TimeUnit.MINUTES)) {
                process.destroyForcibly(); process.waitFor(); throw new IOException(tool + " 执行超时，请检查 logs。");
            }
            String result = new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                Files.copy(output, root.resolve("logs/last-database-error.log"), StandardCopyOption.REPLACE_EXISTING);
                throw new IOException(tool + " 执行失败，请查看 logs/last-database-error.log。");
            }
            return result;
        } finally {
            try { Files.deleteIfExists(output); }
            catch (IOException error) {
                // On Windows pg_ctl's server child keeps stdout open until PostgreSQL stops.
                output.toFile().deleteOnExit();
            }
        }
    }

    private synchronized void closeWeb() {
        if (context != null) { context.close(); context = null; }
    }

    private synchronized void stop() {
        closeWeb();
        if (databaseStarted) {
            try { execute("pg_ctl", false, "stop", "-D", cluster.toString(), "-m", "fast", "-w", "-t", "60"); }
            catch (Exception error) { error.printStackTrace(System.err); }
            databaseStarted = false;
        }
    }

    static int freePort(int preferred) throws IOException {
        try (ServerSocket socket = new ServerSocket(preferred, 0, InetAddress.getByName("127.0.0.1"))) { return socket.getLocalPort(); }
        catch (java.net.BindException error) { if (preferred != 0) return freePort(0); throw error; }
    }

    static String randomPassword() {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static void restrictToOwner(Path path) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (posix != null) posix.setPermissions(PosixFilePermissions.fromString(Files.isDirectory(path) ? "rwx------" : "rw-------"));
        AclFileAttributeView windows = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (windows != null) {
            // Elevated accounts may create files owned by Administrators. PostgreSQL drops that group when launching.
            UserPrincipal owner = path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
            windows.setOwner(owner);
            AclEntry.Builder entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class));
            if (Files.isDirectory(path)) entry.setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT);
            windows.setAcl(List.of(entry.build()));
        }
    }

    private static synchronized void event(String type, String message) throws IOException {
        System.out.println("SHIFTBALANCE_DESKTOP " + JSON.writeValueAsString(Map.of("type", type, "message", Objects.toString(message, "操作失败"))));
        System.out.flush();
    }
}
