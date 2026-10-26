package kr.co.kevit.vdaemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public final class Main implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService httpWorkers = Executors.newFixedThreadPool(8);
    private final ExecutorService ioWorkers = Executors.newFixedThreadPool(4);
    private final Map<String, Charger> chargers = new ConcurrentHashMap<>();
    private final Config config;
    private final Transport transport;
    Main(Config config, int port) throws Exception {
        this.config = config;
        transport = Transport.jdk(HttpClient.newBuilder().executor(ioWorkers).sslContext(config.sslContext()).connectTimeout(Duration.ofSeconds(10)).build());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 32);
        server.setExecutor(httpWorkers); server.createContext("/", this::handle);
    }
    void start() { server.start(); }
    int port() { return server.getAddress().getPort(); }
    private void handle(HttpExchange exchange) throws IOException {
        try {
            String host = exchange.getRequestHeaders().getFirst("Host");
            if (!Set.of("127.0.0.1:" + port(), "localhost:" + port()).contains(host)) { reply(exchange, 403, "허용되지 않은 Host"); return; }
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            if (origin != null && !Set.of("http://127.0.0.1:" + port(), "http://localhost:" + port()).contains(origin)) { reply(exchange, 403, "동일 출처 요청만 허용합니다."); return; }
            String path = exchange.getRequestURI().getPath(), method = exchange.getRequestMethod();
            if (path.startsWith("/api/")) {
                if (method.equals("POST")) {
                    if (origin == null || !Objects.toString(exchange.getRequestHeaders().getFirst("Content-Type"), "").startsWith("application/json")) {
                        reply(exchange, 403, "웹 UI의 JSON 요청만 허용합니다."); return;
                    }
                }
                JsonNode body = Json.object();
                if (method.equals("POST")) {
                    byte[] bytes = exchange.getRequestBody().readNBytes(1_048_577);
                    if (bytes.length > 1_048_576) { reply(exchange, 413, "요청 크기 제한 초과"); return; }
                    body = Json.MAPPER.readTree(bytes);
                    if (body == null || !body.isObject()) throw new IllegalArgumentException("JSON 객체가 필요합니다.");
                }
                reply(exchange, 200, api(path, method, body)); return;
            }
            if (!method.equals("GET")) { reply(exchange, 405, "GET only"); return; }
            String resource = switch (path) { case "/", "/index.html" -> "index.html"; case "/app.js" -> "app.js"; case "/style.css" -> "style.css"; default -> null; };
            if (resource == null) { reply(exchange, 404, "Not found"); return; }
            try (var input = Main.class.getResourceAsStream("/web/" + resource)) {
                if (input == null) throw new FileNotFoundException(resource);
                String contentType = resource.endsWith(".js") ? "text/javascript" : resource.endsWith(".css") ? "text/css" : "text/html";
                headers(exchange); exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
                byte[] bytes = input.readAllBytes(); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { reply(exchange, 400, "JSON 형식이 올바르지 않습니다."); }
        catch (IllegalArgumentException | IllegalStateException | java.time.DateTimeException e) { reply(exchange, 400, e.getMessage()); }
        catch (SQLException e) { reply(exchange, 400, "DB 조회 실패 (SQLState=" + e.getSQLState() + "). 접속 설정과 테이블 스키마를 확인하세요."); }
        catch (Exception e) {
            Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
            reply(exchange, cause instanceof IllegalArgumentException || cause instanceof IllegalStateException ? 400 : 500,
                    cause instanceof IllegalArgumentException || cause instanceof IllegalStateException ? cause.getMessage() : "처리 실패: " + cause.getClass().getSimpleName());
        } finally { exchange.close(); }
    }
    private Object api(String path, String method, JsonNode body) throws Exception {
        if (path.equals("/api/chargers") && method.equals("GET")) {
            ArrayNode rows = Json.MAPPER.createArrayNode();
            for (Charger charger : chargers.values().stream().sorted(Comparator.comparing(c -> c.id)).toList()) rows.add(charger.summary().get(3, TimeUnit.SECONDS));
            return rows;
        }
        if (method.equals("GET") && path.matches("/api/chargers/[0-9]{6}-[0-9]{2}")) {
            Charger charger = chargers.get(path.substring(path.lastIndexOf('/') + 1));
            if (charger == null) throw new IllegalArgumentException("등록되지 않은 충전기입니다.");
            return charger.state().get(3, TimeUnit.SECONDS);
        }
        if (method.equals("GET") && path.matches("/api/chargers/[0-9]{6}-[0-9]{2}/meters")) {
            String id = path.split("/")[3];
            if (!chargers.containsKey(id)) throw new IllegalArgumentException("등록되지 않은 충전기입니다.");
            return new Prerequisites(config).meters(id);
        }
        if (!method.equals("POST")) throw new IllegalArgumentException("지원하지 않는 API입니다.");
        if (path.equals("/api/plan")) return SessionPlan.create(body);
        if (path.equals("/api/validate")) return new Prerequisites(config).inspect(body);
        if (path.equals("/api/chargers")) {
            String id = Json.required(body, "id");
            synchronized (chargers) {
                if (chargers.size() >= 64) throw new IllegalStateException("가상 충전기는 최대 64기입니다.");
                if (chargers.containsKey(id)) throw new IllegalArgumentException("이미 등록한 충전기 ID입니다.");
                Charger charger = new Charger(id, transport, Duration.ofSeconds(config.number("request.timeout.seconds", 15)), Path.of(config.get("logs.directory", "logs")));
                chargers.put(id, charger); return charger.state().get(3, TimeUnit.SECONDS);
            }
        }
        String[] parts = path.split("/");
        if (parts.length != 5 || !parts[2].equals("chargers")) throw new IllegalArgumentException("지원하지 않는 API입니다.");
        Charger charger = chargers.get(parts[3]);
        if (charger == null) throw new IllegalArgumentException("가상 충전기를 먼저 추가하세요.");
        return switch (parts[4]) {
            case "clear-error" -> charger.clearError().get(3, TimeUnit.SECONDS);
            case "connect" -> charger.connect(Json.required(body, "url"), body.path("password").asText(""), Json.integer(body, "heartbeatSeconds", 0, 0, 86400)).get(3, TimeUnit.SECONDS);
            case "disconnect" -> charger.disconnect().get(3, TimeUnit.SECONDS);
            case "send" -> {
                if (!body.path("payload").isObject()) throw new IllegalArgumentException("payload는 객체여야 합니다.");
                yield charger.send(Json.required(body, "action"), (ObjectNode) body.get("payload"),
                        Json.integer(body, "connectorId", body.path("payload").path("connectorId").asInt(1), body.path("action").asText().equals("StatusNotification") ? 0 : 1, 64)).get(3, TimeUnit.SECONDS);
            }
            case "schedule" -> charger.startSchedule(Json.integer(body, "connectorId", 1, 1, 64), body.path("steps")).get(3, TimeUnit.SECONDS);
            case "stop" -> (body.has("connectorId") ? charger.stopSchedule(Json.integer(body, "connectorId", 1, 1, 64)) : charger.stopSchedule()).get(3, TimeUnit.SECONDS);
            case "remove" -> { synchronized (chargers) { charger.close(); chargers.remove(charger.id, charger); } yield Map.of("removed", charger.id); }
            default -> throw new IllegalArgumentException("지원하지 않는 API입니다.");
        };
    }
    private void headers(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; style-src 'self'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'");
    }
    private void reply(HttpExchange exchange, int code, Object value) throws IOException {
        headers(exchange); exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        byte[] bytes = Json.MAPPER.writeValueAsBytes(value instanceof String ? Map.of("error", value) : value);
        exchange.sendResponseHeaders(code, bytes.length); exchange.getResponseBody().write(bytes);
    }
    @Override public void close() {
        server.stop(0); chargers.values().forEach(Charger::close); chargers.clear(); httpWorkers.shutdownNow(); ioWorkers.shutdownNow();
    }
    public static void main(String[] args) throws Exception {
        var config = new Config(Path.of(args.length > 0 ? args[0] : "config/local.properties"));
        var app = new Main(config, config.number("server.port", 7088));
        Runtime.getRuntime().addShutdownHook(new Thread(app::close)); app.start();
        System.out.println("v_daemon: http://127.0.0.1:" + app.port());
    }
}
