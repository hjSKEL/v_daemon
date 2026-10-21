package kr.co.kevit.vdaemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.time.Instant;

final class SessionPlan {
    static ArrayNode create(JsonNode input) {
        int connector = Json.integer(input, "connectorId", 1, 1, 64);
        String card = Json.required(input, "idTag");
        if (input.path("includeAuthorize").asBoolean(false) && card.length() > 20)
            throw new IllegalArgumentException("Authorize idTag는 20자 이하여야 합니다.");
        int start = Json.integer(input, "meterStartWh", 0, 0, Integer.MAX_VALUE);
        int energy = Json.integer(input, "energyWh", 1000, 1, 1_000_000);
        if ((long) start + energy > Integer.MAX_VALUE) throw new IllegalArgumentException("계량값 정수 범위 초과");
        int duration = Json.integer(input, "durationSeconds", 60, 1, 86400);
        int sample = Json.integer(input, "sampleSeconds", 10, 1, duration);
        int speed = Json.integer(input, "speed", 1, 1, 3600);
        if ((duration + sample - 1) / sample > 1000) throw new IllegalArgumentException("MeterValues는 세션당 1000개 이하로 설정하세요.");
        String startText = input.path("startsAt").asText("").trim();
        boolean historical = !startText.isBlank();
        if (!historical && speed != 1) throw new IllegalArgumentException("배속 재생은 기준 시각(startsAt)을 지정하세요.");
        Instant base = historical ? Instant.parse(startText) : Instant.now();
        ArrayNode steps = Json.MAPPER.createArrayNode();
        if (input.path("includeBoot").asBoolean(false)) add(steps, 0, "BootNotification", Json.object().put("chargePointVendor", "KEVIT").put("chargePointModel", "VirtualCharger").put("firmwareVersion", "v-daemon-1.0"));
        String firstTime = historical ? base.toString() : "$now";
        add(steps, 0, "StatusNotification", status(connector, "Available", firstTime));
        if (input.path("includeAuthorize").asBoolean(false)) add(steps, 0, "Authorize", Json.object().put("idTag", card));
        add(steps, 0, "DataTransfer", Json.object().put("vendorId", "kr.co.kevit").put("messageId", "Tariff")
                .put("data", Json.object().put("connectorId", connector).put("idTag", card).put("timestamp", firstTime).toString()));
        add(steps, 0, "StatusNotification", status(connector, "Preparing", firstTime));
        add(steps, 1200, "StartTransaction", Json.object().put("connectorId", connector).put("idTag", card).put("meterStart", start).put("timestamp", firstTime));
        add(steps, 0, "StatusNotification", status(connector, "Charging", firstTime));
        int previous = 0;
        for (int second = Math.min(sample, duration); ; second = Math.min(second + sample, duration)) {
            int meter = start + (int) ((long) energy * second / duration);
            ObjectNode payload = Json.object().put("connectorId", connector).put("transactionId", "$transactionId");
            ObjectNode value = payload.putArray("meterValue").addObject().put("timestamp", historical ? base.plusSeconds(second).toString() : "$now");
            value.putArray("sampledValue").addObject().put("value", String.valueOf(meter)).put("measurand", "Energy.Active.Import.Register").put("unit", "Wh");
            add(steps, Math.max(1, (second - previous) * 1000 / speed), "MeterValues", payload);
            previous = second; if (second == duration) break;
        }
        String end = historical ? base.plusSeconds(duration).toString() : "$now";
        add(steps, 0, "StopTransaction", Json.object().put("transactionId", "$transactionId:" + connector).put("idTag", card).put("meterStop", start + energy).put("timestamp", end).put("reason", "Local"));
        add(steps, 0, "StatusNotification", status(connector, "Finishing", end));
        add(steps, 0, "StatusNotification", status(connector, "Available", end));
        return steps;
    }
    private static ObjectNode status(int connector, String status, String timestamp) {
        return Json.object().put("connectorId", connector).put("status", status).put("errorCode", "NoError").put("timestamp", timestamp);
    }
    private static void add(ArrayNode steps, int delay, String action, ObjectNode payload) {
        steps.add(Json.object().put("delayMs", delay).put("action", action).set("payload", payload));
    }
}
