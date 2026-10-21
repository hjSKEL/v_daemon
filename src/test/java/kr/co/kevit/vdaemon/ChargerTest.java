package kr.co.kevit.vdaemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class ChargerTest {
    @TempDir Path directory;
    private final List<Charger> actors = new ArrayList<>();
    @AfterEach void closeActors() { actors.forEach(Charger::close); }
    private Charger connected(FakeWire wire, String id, Duration timeout, int heartbeat) throws Exception {
        Charger charger = new Charger(id, wire, timeout, directory); actors.add(charger);
        charger.connect("ws://localhost/ocpp16/{chargerId}", "", heartbeat).get();
        await(() -> state(charger).path("connection").asText().equals("CONNECTED")); return charger;
    }
    private static ObjectNode state(Charger charger) {
        try { return charger.state().get(1, TimeUnit.SECONDS); } catch (Exception e) { throw new RuntimeException(e); }
    }
    static void await(BooleanSupplier predicate) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!predicate.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        assertTrue(predicate.getAsBoolean(), "condition did not become true");
    }
    @Test void asynchronousConnectionFailurePreservesCauseInStateAndLog() throws Exception {
        var refused = new java.net.ConnectException();
        refused.initCause(new java.nio.channels.ClosedChannelException());
        Transport wire = (uri, id, password, events) -> CompletableFuture.failedFuture(new CompletionException(new CompletionException(refused)));
        Charger charger = new Charger("000001-01", wire, Duration.ofSeconds(1), directory); actors.add(charger);
        charger.connect("ws://localhost/ocpp16/{chargerId}", "", 0).get();
        await(() -> state(charger).path("connection").asText().equals("DISCONNECTED"));
        String expected = "연결 실패: ConnectException → ClosedChannelException";
        assertEquals(expected, state(charger).path("lastError").asText());
        assertEquals(expected, state(charger).path("events").get(1).path("data").asText());
        assertTrue(java.nio.file.Files.readString(directory.resolve("000001-01.jsonl")).contains(expected));
    }
    @Test void synchronousConnectionFailurePreservesTlsMessageAndCause() throws Exception {
        Transport wire = (uri, id, password, events) -> {
            var failure = new javax.net.ssl.SSLHandshakeException("certificate validation failed");
            failure.initCause(new java.security.cert.CertificateException("untrusted issuer"));
            throw new CompletionException(failure);
        };
        Charger charger = new Charger("000001-01", wire, Duration.ofSeconds(1), directory); actors.add(charger);
        var result = charger.connect("wss://localhost:8091/ocpp16/{chargerId}", "", 0).get();
        assertEquals("DISCONNECTED", result.path("connection").asText());
        assertEquals("연결 실패: SSLHandshakeException: certificate validation failed → CertificateException: untrusted issuer", result.path("lastError").asText());
    }
    @Test void rejectedWebSocketHandshakeShowsHttpStatus() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> { exchange.sendResponseHeaders(403, -1); exchange.close(); });
        server.start();
        try {
            Charger charger = new Charger("000001-01", Transport.jdk(java.net.http.HttpClient.newHttpClient()), Duration.ofSeconds(1), directory); actors.add(charger);
            charger.connect("ws://127.0.0.1:" + server.getAddress().getPort() + "/ocpp16/{chargerId}", "", 0).get();
            await(() -> state(charger).path("connection").asText().equals("DISCONNECTED"));
            String error = state(charger).path("lastError").asText();
            assertTrue(error.contains("WebSocketHandshakeException"), error);
            assertTrue(error.contains("HTTP 403"), error);
            assertFalse(error.contains("CompletionException"), error);
        } finally { server.stop(0); }
    }
    @Test void basicAuthSendsSelectedChargerCredentialsWithoutLoggingThem() throws Exception {
        var authorization = new java.util.concurrent.atomic.AtomicReference<String>();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(401, -1); exchange.close();
        });
        server.start();
        try {
            Charger charger = new Charger("000001-01", Transport.jdk(java.net.http.HttpClient.newHttpClient()), Duration.ofSeconds(1), directory); actors.add(charger);
            charger.connect("ws://127.0.0.1:" + server.getAddress().getPort() + "/ocpp16/{chargerId}", "local:password", 0).get();
            await(() -> state(charger).path("connection").asText().equals("DISCONNECTED"));
            String expected = "Basic " + Base64.getEncoder().encodeToString("000001-01:local:password".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(expected, authorization.get());
            assertTrue(state(charger).path("lastError").asText().contains("HTTP 401"));
            String log = java.nio.file.Files.readString(directory.resolve("000001-01.jsonl"));
            assertFalse(log.contains("local:password")); assertFalse(log.contains(expected));
        } finally { server.stop(0); }
    }
    @Test void sessionBindsDaemonTransactionToCorrectConnectorAndKeepsOrderedMessages() throws Exception {
        FakeWire wire = new FakeWire(); Charger charger = connected(wire, "000001-01", Duration.ofSeconds(1), 0);
        ArrayNode plan = SessionPlan.create(Json.object().put("connectorId", 2).put("idTag", "CARD1").put("durationSeconds", 2).put("sampleSeconds", 1).put("meterStartWh", 1000).put("energyWh", 500).put("startsAt", "2026-10-01T00:00:00Z").put("speed", 100));
        plan.forEach(step -> ((ObjectNode) step).put("delayMs", 0));
        charger.startSchedule(plan).get(); await(() -> state(charger).path("schedule").asText().equals("COMPLETED"));
        List<JsonNode> frames = wire.sent();
        assertEquals(List.of("StatusNotification", "DataTransfer", "StatusNotification", "StartTransaction", "StatusNotification", "MeterValues", "MeterValues", "StopTransaction", "StatusNotification", "StatusNotification"), frames.stream().map(f -> f.get(2).asText()).toList());
        for (JsonNode frame : frames) if (Set.of("MeterValues", "StopTransaction").contains(frame.get(2).asText())) {
            assertTrue(frame.get(3).get("transactionId").isIntegralNumber()); assertEquals(12345, frame.get(3).path("transactionId").asInt());
        }
        assertFalse(frames.get(7).get(3).has("connectorId"), "StopTransaction must not contain a non-standard connectorId");
        assertEquals(1500, frames.get(7).get(3).path("meterStop").asInt());
        JsonNode connector = state(charger).path("connectors").get(0);
        assertEquals(2, connector.path("connectorId").asInt()); assertEquals("Available", connector.path("status").asText()); assertFalse(connector.has("transactionId"));
        assertEquals("charger-000001-01", state(charger).path("worker").asText());
        assertTrue(wire.threads.stream().allMatch(n -> n.equals("charger-000001-01")));
    }
    @Test void stalledChargerDoesNotBlockAnotherChargerAndTimeoutStopsWithoutRetry() throws Exception {
        FakeWire slow = new FakeWire(); slow.hold = true;
        Charger first = connected(slow, "000001-01", Duration.ofMillis(100), 0);
        FakeWire fast = new FakeWire(); Charger second = connected(fast, "000001-02", Duration.ofSeconds(1), 0);
        var steps = Json.MAPPER.createArrayNode(); steps.add(Json.object().put("action", "Heartbeat").set("payload", Json.object())); steps.add(Json.object().put("action", "Heartbeat").set("payload", Json.object()));
        first.startSchedule(steps).get(); second.send("Heartbeat", Json.object()).get();
        await(() -> state(first).path("schedule").asText().equals("FAILED"));
        await(() -> state(second).path("pending").asInt() == 0);
        assertEquals(1, slow.sent().size()); assertEquals(1, fast.sent().size());
        assertTrue(state(first).path("lastError").asText().contains("시간 초과"));
    }
    @Test void rejectedBootStopsSequenceAndDoesNotMutateTransactions() throws Exception {
        FakeWire wire = new FakeWire(); wire.rejectBoot = true;
        Charger charger = connected(wire, "000001-01", Duration.ofSeconds(1), 0);
        var steps = Json.MAPPER.createArrayNode();
        steps.add(Json.object().put("action", "BootNotification").set("payload", Json.object()));
        steps.add(Json.object().put("action", "StartTransaction").set("payload", Json.object().put("connectorId", 1)));
        charger.startSchedule(steps).get(); await(() -> state(charger).path("schedule").asText().equals("FAILED"));
        assertEquals(1, wire.sent().size()); assertTrue(state(charger).path("lastError").asText().contains("Pending")); assertTrue(state(charger).path("connectors").isEmpty());
    }
    @Test void scheduleStopDoesNotSendLaterStepsOrCancelReceivedTransaction() throws Exception {
        FakeWire wire = new FakeWire(); wire.hold = true;
        Charger charger = connected(wire, "000001-01", Duration.ofSeconds(2), 0);
        var steps = Json.MAPPER.createArrayNode();
        steps.add(Json.object().put("action", "StartTransaction").set("payload", Json.object().put("connectorId", 1).put("meterStart", 10)));
        steps.add(Json.object().put("action", "Heartbeat").set("payload", Json.object()));
        charger.startSchedule(steps).get(); await(() -> !wire.sent().isEmpty()); charger.stopSchedule().get();
        assertThrows(ExecutionException.class, () -> charger.send("Heartbeat", Json.object()).get());
        wire.reply(wire.sent().get(0)); await(() -> state(charger).path("pending").asInt() == 0);
        assertEquals(12345, state(charger).path("connectors").get(0).path("transactionId").asInt()); assertEquals("STOPPED", state(charger).path("schedule").asText()); assertEquals(1, wire.sent().size());
    }
    @Test void heartbeatRunsWhileScheduleIsWaitingAndDisconnectFailsPendingCalls() throws Exception {
        FakeWire wire = new FakeWire(); Charger charger = connected(wire, "000001-01", Duration.ofSeconds(1), 1);
        var steps = Json.MAPPER.createArrayNode(); steps.add(Json.object().put("action", "StatusNotification").put("delayMs", 5000).set("payload", Json.object()));
        charger.startSchedule(steps).get(); await(() -> wire.sent().stream().anyMatch(f -> f.get(2).asText().equals("Heartbeat")));
        assertEquals("RUNNING", state(charger).path("schedule").asText()); charger.disconnect().get();
        assertEquals("DISCONNECTED", state(charger).path("connection").asText()); assertEquals("STOPPED", state(charger).path("schedule").asText());
    }
    @Test void callErrorStopsScheduleAndUnsupportedRemoteCallGetsResponse() throws Exception {
        FakeWire wire = new FakeWire(); wire.callError = true;
        Charger charger = connected(wire, "000001-01", Duration.ofSeconds(1), 0);
        var steps = Json.MAPPER.createArrayNode(); steps.add(Json.object().put("action", "Heartbeat").set("payload", Json.object()));
        charger.startSchedule(steps).get(); await(() -> state(charger).path("schedule").asText().equals("FAILED"));
        wire.events.text("[2,\"remote-id\",\"Reset\",{}]"); await(() -> wire.sent().size() == 2);
        assertEquals(4, wire.sent().get(1).get(0).asInt()); assertEquals("NotImplemented", wire.sent().get(1).get(2).asText());
        var before = state(charger);
        assertTrue(before.path("lastError").asText().contains("CALLERROR"));
        var cleared = charger.clearError().get();
        assertEquals("", cleared.path("lastError").asText());
        assertEquals(before.path("events"), cleared.path("events"));
        assertEquals(before.path("schedules"), cleared.path("schedules"));
        assertEquals(before.path("connection"), cleared.path("connection"));
        assertEquals(before.path("connectors"), cleared.path("connectors"));
        charger.send("Heartbeat", Json.object()).get();
        await(() -> !state(charger).path("lastError").asText().isEmpty());
        assertTrue(state(charger).path("lastError").asText().contains("CALLERROR"));
    }
    @Test void duplicateConnectAndUnsupportedMessagesAreRejected() throws Exception {
        FakeWire wire = new FakeWire(); Charger charger = connected(wire, "000001-01", Duration.ofSeconds(1), 0);
        assertThrows(ExecutionException.class, () -> charger.connect("ws://localhost", "", 0).get());
        assertThrows(ExecutionException.class, () -> charger.send("Reset", Json.object()).get());
        assertThrows(ExecutionException.class, () -> charger.send("DataTransfer", Json.object().put("messageId", "FixedTariff")).get());
        assertThrows(IllegalArgumentException.class, () -> new Charger("../bad", wire, Duration.ofSeconds(1), directory));
    }
    @Test void bootCannotResetAnActiveTransactionAndDuplicateStartCannotOverwriteIt() throws Exception {
        FakeWire wire = new FakeWire(); Charger charger = connected(wire, "000001-01", Duration.ofSeconds(1), 0);
        charger.send("StartTransaction", Json.object().put("connectorId", 2).put("meterStart", 100)).get();
        await(() -> state(charger).path("pending").asInt() == 0);
        assertThrows(ExecutionException.class, () -> charger.send("StartTransaction", Json.object().put("connectorId", 2)).get());
        assertEquals(12345, state(charger).path("connectors").get(0).path("transactionId").asInt());
        assertThrows(ExecutionException.class, () -> charger.send("BootNotification", Json.object()).get());
        charger.send("StopTransaction", Json.object().put("transactionId", 12345).put("meterStop", 100)).get();
        await(() -> state(charger).path("pending").asInt() == 0);
        charger.send("BootNotification", Json.object()).get(); await(() -> state(charger).path("pending").asInt() == 0);
        assertFalse(state(charger).path("connectors").get(0).has("transactionId"));
    }
    @Test void lateResponseAfterTimeoutCannotAdvanceSchedule() throws Exception {
        FakeWire wire = new FakeWire(); wire.hold = true; Charger charger = connected(wire, "000001-01", Duration.ofMillis(80), 0);
        var steps = Json.MAPPER.createArrayNode(); steps.add(Json.object().put("action", "Heartbeat").set("payload", Json.object())); steps.add(Json.object().put("action", "Heartbeat").set("payload", Json.object()));
        charger.startSchedule(steps).get(); await(() -> state(charger).path("schedule").asText().equals("FAILED"));
        wire.reply(wire.sent().get(0));
        await(() -> state(charger).path("events").toString().contains("uniqueId"));
        assertEquals("FAILED", state(charger).path("schedule").asText()); assertEquals(1, wire.sent().size());
    }
    @Test void authorizeSupportsManualAndScheduledCallsWithoutCreatingTransactions() throws Exception {
        FakeWire wire = new FakeWire(); Charger charger = connected(wire, "000001-01", Duration.ofSeconds(1), 0);
        charger.send("Authorize", Json.object().put("idTag", "CARD1")).get();
        await(() -> state(charger).path("pending").asInt() == 0);
        assertEquals("Authorize", wire.sent().get(0).get(2).asText());
        assertEquals("CARD1", wire.sent().get(0).get(3).path("idTag").asText());
        assertTrue(state(charger).path("connectors").isEmpty());
        var steps = Json.MAPPER.createArrayNode();
        steps.add(Json.object().put("action", "Authorize").set("payload", Json.object().put("idTag", "CARD2")));
        steps.add(Json.object().put("action", "Heartbeat").set("payload", Json.object()));
        charger.startSchedule(steps).get(); await(() -> state(charger).path("schedule").asText().equals("COMPLETED"));
        assertEquals(List.of("Authorize", "Authorize", "Heartbeat"), wire.sent().stream().map(frame -> frame.get(2).asText()).toList());
        assertTrue(state(charger).path("connectors").isEmpty());
    }
    @Test void rejectedOrMalformedAuthorizeCannotAdvanceToStartTransaction() throws Exception {
        for (String status : new String[]{"Blocked", "Expired", "Invalid", "ConcurrentTx", null}) {
            FakeWire wire = new FakeWire(); wire.authorizeStatus = status;
            Charger charger = connected(wire, "000001-01", Duration.ofSeconds(1), 0);
            var steps = Json.MAPPER.createArrayNode();
            steps.add(Json.object().put("action", "Authorize").set("payload", Json.object().put("idTag", "CARD")));
            steps.add(Json.object().put("action", "StartTransaction").set("payload", Json.object().put("connectorId", 1)));
            charger.startSchedule(steps).get(); await(() -> state(charger).path("schedule").asText().equals("FAILED"));
            assertEquals(1, wire.sent().size()); assertTrue(state(charger).path("lastError").asText().contains("Authorize"));
            assertTrue(state(charger).path("connectors").isEmpty());
        }
    }
    @Test void invalidAuthorizePayloadIsRejectedBeforeSending() throws Exception {
        FakeWire wire = new FakeWire(); Charger charger = connected(wire, "000001-01", Duration.ofSeconds(1), 0);
        for (ObjectNode payload : List.of(Json.object(), Json.object().put("idTag", " "), Json.object().put("idTag", 123), Json.object().put("idTag", "x".repeat(21))))
            assertThrows(ExecutionException.class, () -> charger.send("Authorize", payload).get());
        assertTrue(wire.sent().isEmpty());
    }
    private static JsonNode schedule(Charger charger, int connector) {
        for (JsonNode run : state(charger).path("schedules")) if (run.path("connectorId").asInt() == connector) return run;
        throw new AssertionError("Missing connector schedule " + connector);
    }
    private static ArrayNode heartbeatPlan() {
        return Json.MAPPER.createArrayNode().add(Json.object().put("action", "Heartbeat").set("payload", Json.object()));
    }
    @Test void twoSessionsKeepTheirOwnTransactionsAndSpaceStartsWithoutFailing() throws Exception {
        FakeWire wire = new FakeWire(); wire.uniqueTransactions = true;
        Charger charger = connected(wire, "000001-01", Duration.ofSeconds(2), 0);
        for (int connector : new int[]{1, 2}) {
            ArrayNode plan = SessionPlan.create(Json.object().put("connectorId", connector).put("idTag", "CARD" + connector)
                    .put("durationSeconds", 3).put("sampleSeconds", 1));
            plan.forEach(step -> ((ObjectNode) step).put("delayMs", step.path("action").asText().equals("MeterValues") ? 550 : 0));
            charger.startSchedule(connector, plan).get();
        }
        await(() -> schedule(charger, 1).path("status").asText().equals("RUNNING") && schedule(charger, 2).path("status").asText().equals("RUNNING"));
        await(() -> state(charger).path("connectors").size() == 2 && state(charger).path("connectors").findValues("transactionId").size() == 2);
        await(() -> schedule(charger, 1).path("status").asText().equals("COMPLETED") && schedule(charger, 2).path("status").asText().equals("COMPLETED"));
        for (JsonNode frame : wire.sent()) if (Set.of("MeterValues", "StopTransaction").contains(frame.get(2).asText())) {
            JsonNode payload = frame.get(3);
            int connector = frame.get(2).asText().equals("StopTransaction") ? Integer.parseInt(payload.path("idTag").asText().substring(4)) : payload.path("connectorId").asInt();
            assertEquals(12000 + connector, payload.path("transactionId").asInt());
            if (frame.get(2).asText().equals("StopTransaction")) assertFalse(payload.has("connectorId"));
        }
        List<Long> starts = wire.sent().stream().filter(f -> f.get(2).asText().equals("StartTransaction")).map(f -> wire.sentAt.get(f.get(1).asText())).toList();
        assertEquals(2, starts.size()); assertTrue(TimeUnit.NANOSECONDS.toMillis(starts.get(1) - starts.get(0)) >= 1100);
        assertTrue(wire.threads.stream().allMatch(n -> n.equals("charger-000001-01")));
    }
    @Test void selectedStopAndLateReplyDoNotStopTheOtherScheduleAndThirdRunIsRejected() throws Exception {
        FakeWire wire = new FakeWire(); wire.hold = true;
        Charger charger = connected(wire, "000001-01", Duration.ofSeconds(3), 0);
        ArrayNode two = heartbeatPlan(); two.add(heartbeatPlan().get(0));
        charger.startSchedule(1, two).get(); charger.startSchedule(2, two).get();
        await(() -> wire.sent().size() == 2);
        assertThrows(ExecutionException.class, () -> charger.startSchedule(1, two).get());
        assertThrows(ExecutionException.class, () -> charger.startSchedule(3, two).get());
        charger.stopSchedule(1).get();
        wire.reply(wire.sent().get(0));
        await(() -> schedule(charger, 1).path("pending").asInt() == 0);
        assertEquals("STOPPED", schedule(charger, 1).path("status").asText());
        assertEquals("RUNNING", schedule(charger, 2).path("status").asText());
        wire.reply(wire.sent().get(1)); await(() -> wire.sent().size() == 3);
        wire.reply(wire.sent().get(2)); await(() -> schedule(charger, 2).path("status").asText().equals("COMPLETED"));
        assertEquals(0, schedule(charger, 1).path("step").asInt());
        charger.startSchedule(3, heartbeatPlan()).get();
    }
    @Test void connectorFailureIsIndependentAndDisconnectStopsBoth() throws Exception {
        FakeWire wire = new FakeWire(); wire.hold = true;
        Charger charger = connected(wire, "000001-01", Duration.ofSeconds(3), 0);
        charger.startSchedule(1, heartbeatPlan()).get(); charger.startSchedule(2, heartbeatPlan()).get();
        await(() -> wire.sent().size() == 2);
        wire.events.text(Json.MAPPER.createArrayNode().add(4).add(wire.sent().get(0).get(1).asText()).add("InternalError").add("mock").add(Json.object()).toString());
        await(() -> schedule(charger, 1).path("status").asText().equals("FAILED"));
        assertEquals("RUNNING", schedule(charger, 2).path("status").asText());
        assertTrue(schedule(charger, 1).path("lastError").asText().contains("CALLERROR"));
        assertEquals("", schedule(charger, 2).path("lastError").asText());
        charger.disconnect().get();
        assertEquals("STOPPED", schedule(charger, 2).path("status").asText());
    }
    @Test void connectorTimeoutDoesNotCancelTheOtherRunAndLateReplyCannotAdvanceIt() throws Exception {
        FakeWire wire = new FakeWire(); wire.hold = true;
        Charger charger = connected(wire, "000001-01", Duration.ofMillis(250), 0);
        ArrayNode first = heartbeatPlan(); first.add(heartbeatPlan().get(0));
        charger.startSchedule(1, first).get(); charger.startSchedule(2, heartbeatPlan()).get();
        await(() -> wire.sent().size() == 2);
        wire.reply(wire.sent().get(1));
        await(() -> schedule(charger, 2).path("status").asText().equals("COMPLETED"));
        await(() -> schedule(charger, 1).path("status").asText().equals("FAILED"));
        wire.reply(wire.sent().get(0));
        await(() -> state(charger).path("events").toString().contains("uniqueId"));
        assertEquals("FAILED", schedule(charger, 1).path("status").asText());
        assertEquals("COMPLETED", schedule(charger, 2).path("status").asText());
        assertEquals(2, wire.sent().size());
    }
    @Test void rejectsMixedConnectorPayloadsReferencesAndBootDuringAnotherRun() throws Exception {
        FakeWire wire = new FakeWire(); Charger charger = connected(wire, "000001-01", Duration.ofSeconds(1), 0);
        for (ObjectNode step : List.<ObjectNode>of(
                Json.object().put("action", "StatusNotification").set("payload", Json.object().put("connectorId", 2)),
                Json.object().put("action", "DataTransfer").set("payload", Json.object().put("messageId", "Tariff").put("data", "{\"connectorId\":2}")),
                Json.object().put("action", "StopTransaction").set("payload", Json.object().put("transactionId", "$transactionId:2")))) {
            assertThrows(ExecutionException.class, () -> charger.startSchedule(1, Json.MAPPER.createArrayNode().add(step)).get());
        }
        ArrayNode waiting = heartbeatPlan(); ((ObjectNode) waiting.get(0)).put("delayMs", 5000);
        charger.startSchedule(1, waiting).get();
        ArrayNode boot = Json.MAPPER.createArrayNode().add(Json.object().put("action", "BootNotification").set("payload", Json.object()));
        assertThrows(ExecutionException.class, () -> charger.startSchedule(2, boot).get());
        charger.stopSchedule(1).get();
        ((ObjectNode) boot.get(0)).put("delayMs", 5000); charger.startSchedule(1, boot).get();
        assertThrows(ExecutionException.class, () -> charger.startSchedule(2, waiting).get());
    }
    @Test void manualMessagesOnAnIdleConnectorCannotTouchTheOtherConnectorsTransaction() throws Exception {
        FakeWire wire = new FakeWire(); wire.uniqueTransactions = true;
        Charger charger = connected(wire, "000001-01", Duration.ofSeconds(1), 0);
        charger.send("StartTransaction", Json.object().put("connectorId", 2).put("meterStart", 0)).get();
        await(() -> state(charger).path("pending").asInt() == 0);
        ArrayNode waiting = heartbeatPlan(); ((ObjectNode) waiting.get(0)).put("delayMs", 5000);
        charger.startSchedule(2, waiting).get();
        assertThrows(ExecutionException.class, () -> charger.send("StopTransaction", Json.object().put("transactionId", 12002).put("meterStop", 0), 1).get());
        assertThrows(ExecutionException.class, () -> charger.send("DataTransfer", Json.object().put("messageId", "Tariff").put("data", "{\"connectorId\":2}"), 1).get());
        charger.send("Authorize", Json.object().put("idTag", "CARD1"), 1).get();
        await(() -> state(charger).path("pending").asInt() == 0);
        assertEquals("RUNNING", schedule(charger, 2).path("status").asText());
        assertEquals(12002, state(charger).path("connectors").get(0).path("transactionId").asInt());
    }
    @Test void outgoingTimestampsUseMillisecondsForRealtimeAndHistoricalValues() throws Exception {
        FakeWire wire = new FakeWire();
        Charger charger = connected(wire, "000001-01", Duration.ofSeconds(1), 0);
        charger.send("StatusNotification", Json.object().put("connectorId", 1).put("timestamp", "$now")).get();
        String now = wire.sent().get(0).get(3).path("timestamp").asText();
        assertTrue(now.matches(".*\\.[0-9]{3}Z"), now);
        var payload = Json.object().put("connectorId", 1);
        payload.putArray("meterValue").addObject().put("timestamp", "2026-10-21T02:57:33.497904100Z");
        charger.send("MeterValues", payload).get();
        assertEquals("2026-10-21T02:57:33.497Z", wire.sent().get(1).get(3).path("meterValue").get(0).path("timestamp").asText());
    }
    @Test void runningScheduleStepsAreAvailableForRestorationWithoutChangingExecution() throws Exception {
        FakeWire wire = new FakeWire();
        Charger charger = connected(wire, "000001-01", Duration.ofSeconds(1), 0);
        ArrayNode plan = Json.MAPPER.createArrayNode();
        plan.add(Json.object().put("action", "Heartbeat").put("delayMs", 5000).set("payload", Json.object()));
        charger.startSchedule(2, plan).get();
        var snapshot = state(charger);
        assertEquals(plan, snapshot.path("schedules").get(0).path("steps"));
        assertEquals("RUNNING", snapshot.path("schedules").get(0).path("status").asText());
        assertFalse(charger.summary().get().path("schedules").get(0).has("steps"));
        ((ObjectNode) snapshot.path("schedules").get(0).path("steps").get(0)).put("delayMs", 1);
        assertEquals(5000, state(charger).path("schedules").get(0).path("steps").get(0).path("delayMs").asInt());
        assertTrue(wire.sent().isEmpty());
    }
    @Test void connectorZeroStatusIsSeparateFromChargingConnectorsAndCanRunDuringTheirSchedules() throws Exception {
        FakeWire wire = new FakeWire();
        Charger charger = connected(wire, "000001-01", Duration.ofSeconds(1), 0);
        charger.send("StartTransaction", Json.object().put("connectorId", 1).put("meterStart", 100).put("idTag", "CARD")).get();
        await(() -> state(charger).path("pending").asInt() == 0);
        JsonNode before = state(charger).path("connectors").deepCopy();
        for (int number : new int[]{1, 2}) {
            ArrayNode waiting = Json.MAPPER.createArrayNode();
            waiting.add(Json.object().put("action", "Heartbeat").put("delayMs", 5000).set("payload", Json.object()));
            charger.startSchedule(number, waiting).get();
        }
        charger.send("StatusNotification", Json.object().put("connectorId", 0).put("status", "Unavailable").put("errorCode", "NoError"), 1).get();
        await(() -> state(charger).path("chargePoint").path("status").asText().equals("Unavailable"));
        assertEquals(0, wire.sent().get(1).get(3).path("connectorId").asInt());
        assertEquals(before, state(charger).path("connectors"));
        assertEquals("RUNNING", schedule(charger, 1).path("status").asText());
        assertEquals("RUNNING", schedule(charger, 2).path("status").asText());
    }
    @Test void schedulesAcceptChargerStatusZeroButChargingMessagesStillRequirePositiveConnectors() throws Exception {
        FakeWire wire = new FakeWire();
        Charger charger = connected(wire, "000001-01", Duration.ofSeconds(1), 0);
        for (String action : List.of("StartTransaction", "MeterValues"))
            assertThrows(ExecutionException.class, () -> charger.send(action, Json.object().put("connectorId", 0)).get());
        for (JsonNode invalid : List.of(Json.MAPPER.getNodeFactory().textNode("0"), Json.MAPPER.getNodeFactory().numberNode(-1)))
            assertThrows(ExecutionException.class, () -> charger.send("StatusNotification", Json.object().set("connectorId", invalid)).get());
        assertTrue(wire.sent().isEmpty());
        ArrayNode plan = Json.MAPPER.createArrayNode();
        plan.add(Json.object().put("action", "StatusNotification").set("payload", Json.object().put("connectorId", 0).put("status", "Available").put("errorCode", "NoError")));
        plan.add(Json.object().put("action", "StatusNotification").set("payload", Json.object().put("connectorId", 2).put("status", "Preparing").put("errorCode", "NoError")));
        charger.startSchedule(plan).get();
        await(() -> state(charger).path("schedule").asText().equals("COMPLETED"));
        assertEquals("Available", state(charger).path("chargePoint").path("status").asText());
        assertEquals(1, state(charger).path("connectors").size());
        assertEquals(2, state(charger).path("connectors").get(0).path("connectorId").asInt());
        assertEquals("Preparing", state(charger).path("connectors").get(0).path("status").asText());
        assertThrows(ExecutionException.class, () -> charger.startSchedule(0, plan).get());
        ArrayNode bad = Json.MAPPER.createArrayNode();
        bad.add(Json.object().put("action", "StartTransaction").set("payload", Json.object().put("connectorId", 0)));
        assertThrows(ExecutionException.class, () -> charger.startSchedule(1, bad).get());
    }
    static final class FakeWire implements Transport {
        volatile Events events;
        volatile boolean hold, rejectBoot, callError, uniqueTransactions;
        final Map<String, Long> sentAt = new ConcurrentHashMap<>();
        String authorizeStatus = "Accepted";
        final List<JsonNode> frames = new CopyOnWriteArrayList<>();
        final List<String> threads = new CopyOnWriteArrayList<>();
        List<JsonNode> sent() { return List.copyOf(frames); }
        public CompletionStage<Link> connect(URI uri, String id, String password, Events events) {
            this.events = events;
            return CompletableFuture.completedFuture(new Link() {
                public CompletionStage<Void> send(String text) {
                    try { JsonNode frame = Json.MAPPER.readTree(text); frames.add(frame); sentAt.put(frame.get(1).asText(), System.nanoTime()); threads.add(Thread.currentThread().getName()); if (!hold && frame.get(0).asInt() == 2) reply(frame); }
                    catch (Exception e) { return CompletableFuture.failedFuture(e); }
                    return CompletableFuture.completedFuture(null);
                }
                public void close() {}
            });
        }
        void reply(JsonNode frame) {
            if (callError) { events.text(Json.MAPPER.createArrayNode().add(4).add(frame.get(1).asText()).add("InternalError").add("mock").add(Json.object()).toString()); return; }
            ObjectNode response = Json.object();
            if (frame.get(2).asText().equals("Authorize") && authorizeStatus != null) response.putObject("idTagInfo").put("status", authorizeStatus);
            if (frame.get(2).asText().equals("StartTransaction")) { response.put("transactionId", uniqueTransactions ? 12000 + frame.get(3).path("connectorId").asInt() : 12345); response.putObject("idTagInfo").put("status", "Accepted"); }
            if (frame.get(2).asText().equals("BootNotification")) response.put("status", rejectBoot ? "Pending" : "Accepted");
            events.text(Json.MAPPER.createArrayNode().add(3).add(frame.get(1).asText()).add(response).toString());
        }
    }
}
