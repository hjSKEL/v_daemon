package kr.co.kevit.vdaemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.net.URI;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

final class Charger implements AutoCloseable {
    static final Set<String> ACTIONS = Set.of("BootNotification", "Authorize", "StatusNotification", "StartTransaction", "MeterValues", "StopTransaction", "DataTransfer", "Heartbeat");
    final String id;
    private final ScheduledThreadPoolExecutor worker;
    private final Transport transport;
    private final Duration timeout;
    private final EventLog log;
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private final Map<Integer, ObjectNode> connectors = new TreeMap<>();
    private final ObjectNode chargePoint = Json.object().put("connectorId", 0).put("status", "Unknown");
    private final Map<Integer, ScheduleRun> schedules = new TreeMap<>();
    private static final class ScheduleRun {
        final int connectorId;
        final ArrayList<JsonNode> steps = new ArrayList<>();
        String status = "IDLE", error = "";
        int position;
        long generation;
        ScheduledFuture<?> next;
        ScheduleRun(int connectorId) { this.connectorId = connectorId; }
    }
    private Transport.Link link;
    private String connection = "DISCONNECTED", lastError = "";
    private long generation;
    private int heartbeatSeconds;
    private ScheduledFuture<?> heartbeat;
    private boolean heartbeatPending;
    private Instant lastStart = Instant.EPOCH;
    private record Pending(String action, ObjectNode payload, CompletableFuture<JsonNode> result, ScheduledFuture<?> timer, int connectorId) {}

    Charger(String id, Transport transport, Duration timeout, Path logs) {
        if (!id.matches("[0-9]{6}-[0-9]{2}")) throw new IllegalArgumentException("충전기 ID 형식: 000001-01");
        this.id = id; this.transport = transport; this.timeout = timeout; this.log = new EventLog(logs, id);
        worker = new ScheduledThreadPoolExecutor(1, r -> new Thread(r, "charger-" + id));
        worker.setRemoveOnCancelPolicy(true);
    }
    <T> CompletableFuture<T> submit(Supplier<T> task) {
        var future = new CompletableFuture<T>();
        try { worker.execute(() -> { try { future.complete(task.get()); } catch (Exception e) { future.completeExceptionally(e); } }); }
        catch (RejectedExecutionException e) { future.completeExceptionally(new IllegalStateException("삭제된 충전기입니다.")); }
        return future;
    }
    private void later(Runnable task) { if (!worker.isShutdown()) try { worker.execute(task); } catch (RejectedExecutionException ignored) {} }
    CompletableFuture<ObjectNode> connect(String address, String password, int interval) {
        URI uri = URI.create(address.replace("{chargerId}", id));
        if (!("ws".equals(uri.getScheme()) || "wss".equals(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("ws/wss URL을 지정하세요. 인증정보·쿼리는 URL에 넣지 마세요.");
        return submit(() -> {
            if (!connection.equals("DISCONNECTED")) throw new IllegalStateException("이미 연결 중이거나 연결되어 있습니다.");
            long token = ++generation; heartbeatSeconds = interval; connection = "CONNECTING"; lastError = "";
            log.add("EVENT", "연결 시작");
            try {
                transport.connect(uri, id, password, new Transport.Events() {
                    public void text(String text) { later(() -> { if (token == generation) receive(text); }); }
                    public void closed(String reason) { later(() -> { if (token == generation) disconnected(reason); }); }
                }).whenComplete((opened, error) -> { if (worker.isShutdown()) { if (opened != null) opened.close(); return; } later(() -> {
                    if (token != generation) { if (opened != null) opened.close(); return; }
                    if (error != null) { disconnected("연결 실패: " + Transport.errorDetails(error)); return; }
                    link = opened; connection = "CONNECTED"; log.add("EVENT", "연결 완료"); startHeartbeat();
                }); });
            } catch (Exception e) { disconnected("연결 실패: " + Transport.errorDetails(e)); }
            return snapshot();
        });
    }
    CompletableFuture<ObjectNode> disconnect() { return submit(() -> { disconnected("사용자 연결 종료"); return snapshot(); }); }
    CompletableFuture<ObjectNode> clearError() { return submit(() -> { lastError = ""; return snapshot(); }); }
    private void disconnected(String reason) {
        ++generation;
        if (link != null) link.close(); link = null; connection = "DISCONNECTED";
        schedules.values().forEach(s -> stopRun(s, "STOPPED"));
        if (heartbeat != null) heartbeat.cancel(false); heartbeat = null; heartbeatPending = false;
        var calls = new ArrayList<>(pending.values()); pending.clear();
        for (Pending p : calls) { p.timer.cancel(false); p.result.completeExceptionally(new IllegalStateException(reason)); }
        lastError = reason; log.add("EVENT", reason);
    }
    CompletableFuture<ObjectNode> send(String action, ObjectNode payload) {
        return send(action, payload, 0);
    }
    CompletableFuture<ObjectNode> send(String action, ObjectNode payload, int connectorId) {
        return submit(() -> {
            boolean chargePointStatus = action.equals("StatusNotification") && payload.has("connectorId")
                    && Json.integer(payload, "connectorId", 1, 0, 64) == 0;
            int target = chargePointStatus ? 0 : connectorId == 0 ? payload.path("connectorId").asInt(connectors.entrySet().stream()
                    .filter(e -> e.getValue().has("transactionId") && e.getValue().get("transactionId").equals(payload.get("transactionId")))
                    .mapToInt(Map.Entry::getKey).findFirst().orElse(1)) : connectorId;
            if (target < 1 && !chargePointStatus) throw new IllegalArgumentException("충전기 자체(0)는 StatusNotification에서만 사용할 수 있습니다.");
            if (running(target)) throw new IllegalStateException("해당 커넥터의 스케줄을 중지한 뒤 개별 전송하세요.");
            if (busy(target)) throw new IllegalStateException("해당 커넥터의 이전 메시지 응답을 기다리세요.");
            issue(action, payload, target).whenComplete((response, error) -> { if (error != null) fail(error); });
            return snapshot();
        });
    }
    private boolean running(int connectorId) {
        return schedules.containsKey(connectorId) && schedules.get(connectorId).status.equals("RUNNING");
    }
    private boolean busy(int connectorId) {
        return pending.values().stream().anyMatch(p -> !p.action.equals("Heartbeat") && (p.connectorId == connectorId || p.action.equals("BootNotification")));
    }
    private CompletableFuture<JsonNode> issue(String action, ObjectNode template) {
        return issue(action, template, template.path("connectorId").asInt(1));
    }
    private CompletableFuture<JsonNode> issue(String action, ObjectNode template, int connectorId) {
        if (!ACTIONS.contains(action)) throw new IllegalArgumentException("지원하지 않는 메시지: " + action);
        if (link == null) throw new IllegalStateException("먼저 daemon에 연결하세요.");
        if (pending.size() >= 32) throw new IllegalStateException("응답 대기 메시지가 너무 많습니다.");
        if (action.equals("Authorize") && (!template.path("idTag").isTextual() || template.path("idTag").asText().isBlank() || template.path("idTag").asText().length() > 20))
            throw new IllegalArgumentException("Authorize idTag는 1~20자의 문자열이어야 합니다.");
        if (action.equals("BootNotification") && (connectors.values().stream().anyMatch(c -> c.has("transactionId"))
                || pending.values().stream().anyMatch(p -> !p.action.equals("Heartbeat"))
                || schedules.values().stream().anyMatch(s -> s.connectorId != connectorId && s.status.equals("RUNNING"))))
            throw new IllegalStateException("BootNotification은 모든 커넥터의 거래와 다른 스케줄이 종료된 뒤 전송하세요.");
        ObjectNode payload = (ObjectNode) resolve(template, connectorId);
        if (Set.of("StatusNotification", "StartTransaction", "MeterValues").contains(action)) {
            int messageConnector = Json.integer(payload, "connectorId", connectorId, action.equals("StatusNotification") ? 0 : 1, 64);
            if (messageConnector != connectorId && !(action.equals("StatusNotification") && messageConnector == 0))
                throw new IllegalArgumentException("메시지의 커넥터가 선택한 커넥터와 일치하지 않습니다.");
            payload.put("connectorId", messageConnector);
        }
        if (Set.of("MeterValues", "StopTransaction").contains(action) && schedules.containsKey(connectorId) && running(connectorId)) {
            JsonNode own = connector(connectorId).get("transactionId");
            if (own == null || !own.equals(payload.get("transactionId"))) throw new IllegalStateException("스케줄의 거래 ID가 해당 커넥터와 일치하지 않습니다.");
        }
        if (Set.of("MeterValues", "StopTransaction").contains(action) && connectors.entrySet().stream()
                .anyMatch(e -> e.getKey() != connectorId && e.getValue().has("transactionId") && e.getValue().get("transactionId").equals(payload.get("transactionId"))))
            throw new IllegalStateException("다른 커넥터의 거래에 메시지를 전송할 수 없습니다.");
        if (action.equals("DataTransfer") && !payload.path("messageId").asText().equals("Tariff")) throw new IllegalArgumentException("1차 DataTransfer는 Tariff만 지원합니다.");
        if (action.equals("DataTransfer")) {
            try {
                JsonNode data = Json.MAPPER.readTree(payload.path("data").asText());
                if (data == null || !data.isObject()) throw new IllegalArgumentException("Tariff data는 JSON 객체 문자열이어야 합니다.");
                if (Json.integer(data, "connectorId", connectorId, 1, 64) != connectorId)
                    throw new IllegalArgumentException("Tariff의 커넥터가 선택한 커넥터와 일치하지 않습니다.");
                payload.put("data", resolve(data, payload.path("connectorId").asInt(1)).toString());
            } catch (java.io.IOException e) { throw new IllegalArgumentException("Tariff data JSON 형식 오류"); }
        }
        if (action.equals("StartTransaction")) {
            int number = Json.integer(payload, "connectorId", 1, 1, 64);
            if (connectors.containsKey(number) && connectors.get(number).has("transactionId")) throw new IllegalStateException("이미 진행 중인 거래가 있습니다. StopTransaction으로 종료하세요.");
            if (Duration.between(lastStart, Instant.now()).toMillis() < 1100) throw new IllegalStateException("daemon 충전 ID 충돌 방지: 같은 충전기의 StartTransaction은 1.1초 이상 간격이 필요합니다.");
            lastStart = Instant.now();
        }
        String uniqueId = UUID.randomUUID().toString();
        ArrayNode frame = Json.MAPPER.createArrayNode().add(2).add(uniqueId).add(action).add(payload);
        var result = new CompletableFuture<JsonNode>();
        ScheduledFuture<?> timer = worker.schedule(() -> {
            Pending lost = pending.remove(uniqueId);
            if (lost != null) lost.result.completeExceptionally(new IllegalStateException(action + " 응답 시간 초과. 자동 재전송하지 않았습니다."));
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        pending.put(uniqueId, new Pending(action, payload, result, timer, connectorId));
        log.add("TX", frame);
        try { link.send(frame.toString()).whenComplete((unused, error) -> { if (error != null) later(() -> {
            Pending lost = pending.remove(uniqueId);
            if (lost != null) { lost.timer.cancel(false); lost.result.completeExceptionally(new IllegalStateException("메시지 전송 실패")); }
        }); }); } catch (Exception e) {
            pending.remove(uniqueId); timer.cancel(false); result.completeExceptionally(new IllegalStateException("메시지 전송 실패"));
        }
        return result;
    }
    private JsonNode resolve(JsonNode node, int connector) {
        if (node.isTextual()) {
            String text = node.textValue();
            if (text.equals("$now")) return TextNode.valueOf(Json.timestamp(Instant.now()));
            if (text.equals("$transactionId") || text.matches("\\$transactionId:[1-9][0-9]*")) {
                int target = text.contains(":") ? Integer.parseInt(text.substring(text.indexOf(':') + 1)) : connector;
                JsonNode transaction = connector(target).get("transactionId");
                if (transaction == null || transaction.isNull()) throw new IllegalStateException("커넥터 " + target + "의 transactionId가 없습니다.");
                return transaction.deepCopy();
            }
            return node.deepCopy();
        }
        if (node.isObject()) {
            ObjectNode copy = Json.object(); node.fields().forEachRemaining(e -> {
                JsonNode value = resolve(e.getValue(), connector);
                if (e.getKey().equals("timestamp") && value.isTextual())
                    value = TextNode.valueOf(Json.timestamp(Instant.parse(value.asText())));
                copy.set(e.getKey(), value);
            });
            return copy;
        }
        if (node.isArray()) { ArrayNode copy = Json.MAPPER.createArrayNode(); node.forEach(n -> copy.add(resolve(n, connector))); return copy; }
        return node.deepCopy();
    }
    private void receive(String text) {
        try {
            JsonNode frame = Json.MAPPER.readTree(text); log.add("RX", frame);
            if (!frame.isArray() || frame.size() < 3) throw new IllegalArgumentException("OCPP 프레임 형식 오류");
            int type = frame.get(0).asInt(); String key = frame.get(1).asText();
            if (type == 2) {
                var error = Json.MAPPER.createArrayNode().add(4).add(key).add("NotImplemented").add("Virtual charger phase 1").add(Json.object());
                log.add("TX", error); link.send(error.toString()); return;
            }
            if (type != 3 && type != 4) throw new IllegalArgumentException("알 수 없는 OCPP 프레임 종류");
            Pending call = pending.remove(key);
            if (call == null) { log.add("EVENT", "응답 대기 목록에 없는 uniqueId: " + key); return; }
            call.timer.cancel(false);
            if (type == 4) { call.result.completeExceptionally(new IllegalStateException(call.action + " CALLERROR: " + frame.get(2).asText())); return; }
            JsonNode response = frame.get(2);
            if (!response.isObject()) { call.result.completeExceptionally(new IllegalStateException("응답 payload는 객체여야 합니다.")); return; }
            String status = response.path("idTagInfo").path("status").asText(response.path("status").asText("Accepted"));
            if (!status.equals("Accepted")) { call.result.completeExceptionally(new IllegalStateException(call.action + " 거절: " + status)); return; }
            try { apply(call.action, call.payload, response); call.result.complete(response); }
            catch (Exception e) { call.result.completeExceptionally(e); }
        } catch (Exception e) { fail(e); }
    }
    private ObjectNode connector(int number) { return connectors.computeIfAbsent(number, n -> Json.object().put("connectorId", n).put("status", "Unknown").put("meterWh", 0)); }
    private void apply(String action, ObjectNode payload, JsonNode response) {
        int number = payload.path("connectorId").asInt(1);
        ObjectNode state = action.equals("StatusNotification") && number == 0 ? chargePoint
                : Set.of("StatusNotification", "StartTransaction", "MeterValues").contains(action) ? connector(number) : null;
        switch (action) {
            case "Authorize" -> {
                if (!response.path("idTagInfo").path("status").asText().equals("Accepted"))
                    throw new IllegalStateException("Authorize 응답에 유효한 idTagInfo.status가 없습니다.");
            }
            case "BootNotification" -> {
                connectors.values().forEach(s -> { s.remove("transactionId"); s.remove("idTag"); });
                if (response.path("interval").canConvertToInt() && response.path("interval").asInt() > 0) {
                    heartbeatSeconds = Math.min(86400, response.path("interval").asInt()); startHeartbeat();
                }
            }
            case "StatusNotification" -> {
                state.put("status", payload.path("status").asText());
                if (number == 0) for (String field : List.of("errorCode", "vendorErrorCode", "timestamp"))
                    if (payload.has(field)) state.set(field, payload.get(field).deepCopy());
            }
            case "StartTransaction" -> {
                if (!response.path("transactionId").isIntegralNumber()) throw new IllegalStateException("StartTransaction 응답에 transactionId가 없습니다.");
                state.set("transactionId", response.get("transactionId")); state.put("status", "Charging");
                state.put("meterWh", payload.path("meterStart").asLong()); state.put("idTag", payload.path("idTag").asText());
                lastStart = Instant.now();
            }
            case "MeterValues" -> {
                JsonNode values = payload.path("meterValue").path(0).path("sampledValue");
                for (JsonNode value : values) if (value.path("measurand").asText("Energy.Active.Import.Register").equals("Energy.Active.Import.Register")) {
                    double wh = value.path("value").asDouble() * (value.path("unit").asText("Wh").equals("kWh") ? 1000 : 1);
                    state.put("meterWh", wh); break;
                }
            }
            case "StopTransaction" -> connectors.values().stream().filter(s -> s.has("transactionId") && s.get("transactionId").equals(payload.get("transactionId"))).forEach(s -> {
                s.remove("transactionId"); s.remove("idTag"); s.put("meterWh", payload.path("meterStop").asLong()); s.put("status", "Finishing");
            });
            default -> { }
        }
    }
    private void startHeartbeat() {
        if (heartbeat != null) heartbeat.cancel(false);
        if (heartbeatSeconds <= 0 || link == null) return;
        heartbeat = worker.scheduleWithFixedDelay(() -> {
            if (heartbeatPending) return;
            heartbeatPending = true;
            try { issue("Heartbeat", Json.object()).whenComplete((r, e) -> { heartbeatPending = false; if (e != null) fail(e); }); }
            catch (Exception e) { heartbeatPending = false; fail(e); }
        }, heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);
    }
    CompletableFuture<ObjectNode> startSchedule(JsonNode input) {
        int connectorId = 1;
        for (JsonNode step : input) if (step.path("payload").has("connectorId")
                && !(step.path("action").asText().equals("StatusNotification") && step.path("payload").path("connectorId").asInt(-1) == 0)) {
            connectorId = step.path("payload").path("connectorId").asInt(1); break;
        }
        return startSchedule(connectorId, input);
    }
    CompletableFuture<ObjectNode> startSchedule(int connectorId, JsonNode input) {
        return submit(() -> {
            if (connectorId < 1 || connectorId > 64) throw new IllegalArgumentException("커넥터 범위: 1~64");
            if (link == null) throw new IllegalStateException("먼저 daemon에 연결하세요.");
            if (running(connectorId)) throw new IllegalStateException("해당 커넥터는 이미 스케줄 실행 중입니다.");
            if (schedules.values().stream().filter(s -> s.status.equals("RUNNING")).count() >= 2)
                throw new IllegalStateException("충전기당 스케줄은 최대 2개까지 동시에 실행할 수 있습니다.");
            if (busy(connectorId)) throw new IllegalStateException("해당 커넥터의 이전 메시지 응답을 기다리세요.");
            if (!input.isArray() || input.isEmpty() || input.size() > 2000) throw new IllegalArgumentException("steps는 1~2000개여야 합니다.");
            for (ScheduleRun other : schedules.values()) if (other.status.equals("RUNNING")) {
                for (int i = other.position; i < other.steps.size(); i++)
                    if (other.steps.get(i).path("action").asText().equals("BootNotification"))
                        throw new IllegalStateException("다른 스케줄의 BootNotification 완료 후 실행하세요.");
            }
            for (JsonNode step : input) {
                String action = Json.required(step, "action");
                if (!ACTIONS.contains(action) || !step.path("payload").isObject()) throw new IllegalArgumentException("action 또는 payload 형식 오류");
                Json.integer(step, "delayMs", 0, 0, 86_400_000);
                JsonNode payload = step.path("payload");
                if (payload.has("connectorId")) {
                    int target = Json.integer(payload, "connectorId", connectorId, action.equals("StatusNotification") ? 0 : 1, 64);
                    if (target != connectorId && !(action.equals("StatusNotification") && target == 0))
                        throw new IllegalArgumentException("스케줄에는 선택한 커넥터 또는 충전기 자체의 상태 메시지만 넣으세요.");
                }
                if (action.equals("DataTransfer")) {
                    try {
                        JsonNode data = Json.MAPPER.readTree(payload.path("data").asText());
                        if (data == null || !data.isObject() || Json.integer(data, "connectorId", connectorId, 1, 64) != connectorId)
                            throw new IllegalArgumentException("Tariff의 커넥터가 스케줄과 일치하지 않습니다.");
                    } catch (java.io.IOException e) { throw new IllegalArgumentException("Tariff data JSON 형식 오류"); }
                }
                validateReferences(payload, connectorId);
                if (action.equals("BootNotification") && (schedules.values().stream().anyMatch(s -> s.status.equals("RUNNING"))
                        || connectors.values().stream().anyMatch(c -> c.has("transactionId"))))
                    throw new IllegalStateException("BootNotification은 모든 거래와 스케줄 종료 후 전송하세요.");
            }
            ScheduleRun schedule = schedules.computeIfAbsent(connectorId, ScheduleRun::new);
            schedule.steps.clear(); input.forEach(s -> schedule.steps.add(s.deepCopy()));
            schedule.position = 0; schedule.status = "RUNNING"; schedule.error = ""; lastError = ""; ++schedule.generation;
            log.add("EVENT", "커넥터 " + connectorId + " 스케줄 시작: " + schedule.steps.size() + "단계");
            queueStep(schedule, schedule.generation, -1); return snapshot();
        });
    }
    private void validateReferences(JsonNode value, int connectorId) {
        if (value.isTextual() && value.asText().matches("\\$transactionId:[1-9][0-9]*")
                && !value.asText().equals("$transactionId:" + connectorId))
            throw new IllegalArgumentException("다른 커넥터의 거래 ID는 스케줄에서 사용할 수 없습니다.");
        if (value.isContainerNode()) value.forEach(v -> validateReferences(v, connectorId));
    }
    private void queueStep(ScheduleRun schedule, long token, long retryDelay) {
        if (token != schedule.generation || !schedule.status.equals("RUNNING")) return;
        if (schedule.position == schedule.steps.size()) {
            schedule.status = "COMPLETED"; log.add("EVENT", "커넥터 " + schedule.connectorId + " 스케줄 완료"); return;
        }
        JsonNode step = schedule.steps.get(schedule.position);
        schedule.next = worker.schedule(() -> {
            if (token != schedule.generation) return;
            // The daemon generates transaction IDs from seconds shared by both connectors.
            if (step.path("action").asText().equals("StartTransaction")) {
                long wait = 1100 - Duration.between(lastStart, Instant.now()).toMillis();
                if (wait > 0 || pending.values().stream().anyMatch(p -> p.action.equals("StartTransaction"))) {
                    queueStep(schedule, token, Math.max(50, wait)); return;
                }
            }
            try {
                issue(step.path("action").asText(), (ObjectNode) step.path("payload"), schedule.connectorId).whenComplete((response, error) -> {
                    if (token != schedule.generation) return;
                    if (error != null) failSchedule(schedule, error);
                    else { ++schedule.position; queueStep(schedule, token, -1); }
                });
            } catch (Exception e) { failSchedule(schedule, e); }
        }, retryDelay >= 0 ? retryDelay : step.path("delayMs").asLong(0), TimeUnit.MILLISECONDS);
    }
    private void failSchedule(ScheduleRun schedule, Throwable error) {
        schedule.error = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        log.add("ERROR", "커넥터 " + schedule.connectorId + ": " + schedule.error); lastError = schedule.error;
        stopRun(schedule, "FAILED");
    }
    CompletableFuture<ObjectNode> stopSchedule() {
        return submit(() -> { schedules.values().forEach(s -> stopRun(s, "STOPPED")); return snapshot(); });
    }
    CompletableFuture<ObjectNode> stopSchedule(int connectorId) {
        return submit(() -> { ScheduleRun s = schedules.get(connectorId); if (s != null) stopRun(s, "STOPPED"); return snapshot(); });
    }
    private void stopRun(ScheduleRun schedule, String status) {
        ++schedule.generation; if (schedule.next != null) schedule.next.cancel(false);
        if (schedule.status.equals("RUNNING")) schedule.status = status;
    }
    private void fail(Throwable error) { lastError = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); log.add("ERROR", lastError); }
    CompletableFuture<ObjectNode> state() { return submit(this::snapshot); }
    CompletableFuture<ObjectNode> summary() { return submit(() -> snapshot(false)); }
    private ObjectNode snapshot() { return snapshot(true); }
    private ObjectNode snapshot(boolean includeEvents) {
        String status = schedules.values().stream().anyMatch(s -> s.status.equals("RUNNING")) ? "RUNNING"
                : schedules.values().stream().anyMatch(s -> s.status.equals("FAILED")) ? "FAILED"
                : schedules.values().stream().anyMatch(s -> s.status.equals("STOPPED")) ? "STOPPED"
                : schedules.isEmpty() ? "IDLE" : "COMPLETED";
        ObjectNode result = Json.object().put("id", id).put("connection", connection).put("worker", "charger-" + id)
                .put("schedule", status).put("step", schedules.values().stream().mapToInt(s -> s.position).sum())
                .put("totalSteps", schedules.values().stream().mapToInt(s -> s.steps.size()).sum()).put("pending", pending.size())
                .put("heartbeatSeconds", heartbeatSeconds).put("lastError", lastError).put("logError", log.error());
        ArrayNode runs = result.putArray("schedules");
        for (ScheduleRun s : schedules.values()) {
            ObjectNode run = Json.object().put("connectorId", s.connectorId).put("status", s.status)
                .put("step", s.position).put("totalSteps", s.steps.size()).put("lastError", s.error)
                .put("pending", pending.values().stream().filter(p -> p.connectorId == s.connectorId && !p.action.equals("Heartbeat")).count());
            if (includeEvents) run.set("steps", Json.MAPPER.valueToTree(s.steps));
            runs.add(run);
        }
        result.set("chargePoint", chargePoint.deepCopy());
        result.set("connectors", Json.MAPPER.valueToTree(connectors.values())); if (includeEvents) result.set("events", log.snapshot()); return result;
    }
    @Override public void close() {
        try { disconnect().get(3, TimeUnit.SECONDS); } catch (Exception ignored) { if (link != null) link.close(); }
        worker.shutdownNow();
    }
}
