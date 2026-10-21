package kr.co.kevit.vdaemon;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SessionPlanTest {
    @Test void authorizeIsOptionalAndPrecedesTariffAndStartTransaction() {
        var input = Json.object().put("idTag", "CARD").put("includeAuthorize", true).put("includeBoot", true);
        var plan = SessionPlan.create(input);
        assertEquals("BootNotification", plan.get(0).path("action").asText());
        assertEquals("Authorize", plan.get(2).path("action").asText());
        assertEquals(Json.object().put("idTag", "CARD"), plan.get(2).path("payload"));
        assertEquals("DataTransfer", plan.get(3).path("action").asText());
        assertEquals("StartTransaction", plan.get(5).path("action").asText());
        assertFalse(SessionPlan.create(input.deepCopy().put("includeAuthorize", false)).toString().contains("Authorize"));
        assertThrows(IllegalArgumentException.class, () -> SessionPlan.create(input.deepCopy().put("idTag", "x".repeat(21))));
    }
    @Test void historicalPlanHasExactFinalMeterAndPreservesTimestampsAtSpeed() {
        var plan = SessionPlan.create(Json.object().put("idTag", "CARD").put("durationSeconds", 11).put("sampleSeconds", 4).put("meterStartWh", 1500).put("energyWh", 1000).put("startsAt", "2026-10-01T00:00:00Z").put("speed", 10));
        var values = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        plan.forEach(s -> { if (s.path("action").asText().equals("MeterValues")) values.add(s); });
        assertEquals(3, values.size()); assertEquals(300, values.get(2).path("delayMs").asInt());
        var last = values.get(2).path("payload").path("meterValue").get(0);
        assertEquals("2026-10-01T00:00:11Z", last.path("timestamp").asText());
        assertEquals("2500", last.path("sampledValue").get(0).path("value").asText());
    }
    @Test void realtimeUsesSendTimeAndInputsAreBounded() {
        var input = Json.object().put("idTag", "CARD");
        var plan = SessionPlan.create(input);
        assertTrue(plan.toString().contains("$now")); assertFalse(plan.toString().contains("BootNotification"));
        assertThrows(IllegalArgumentException.class, () -> SessionPlan.create(input.deepCopy().put("speed", 2)));
        assertThrows(IllegalArgumentException.class, () -> SessionPlan.create(input.deepCopy().put("durationSeconds", 86400).put("sampleSeconds", 1)));
        assertThrows(IllegalArgumentException.class, () -> SessionPlan.create(input.deepCopy().put("meterStartWh", Integer.MAX_VALUE)));
        assertThrows(IllegalArgumentException.class, () -> SessionPlan.create(input.deepCopy().put("connectorId", 0)));
    }
}
