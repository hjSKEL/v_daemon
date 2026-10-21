package kr.co.kevit.vdaemon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class HttpTest {
    @TempDir Path directory;
    @Test void planApiGeneratesAuthorizeWithoutConnectingAndReportsInvalidInput() throws Exception {
        try (Main app = new Main(new Config(directory.resolve("test.properties")), 0)) {
            app.start(); String base = "http://127.0.0.1:" + app.port(); var client = HttpClient.newHttpClient();
            var input = Json.object().put("idTag", "CARD").put("connectorId", 1).put("meterStartWh", 0).put("energyWh", 1000)
                    .put("durationSeconds", 60).put("sampleSeconds", 10).put("speed", 1).put("startsAt", "").put("includeBoot", false).put("includeAuthorize", true);
            var generated = client.send(HttpRequest.newBuilder(URI.create(base + "/api/plan")).header("Origin", base).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(input.toString())).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, generated.statusCode());
            var plan = Json.MAPPER.readTree(generated.body());
            assertTrue(plan.isArray()); assertEquals("Authorize", plan.get(1).path("action").asText());
            assertEquals(Json.object().put("idTag", "CARD"), plan.get(1).path("payload"));
            for (var invalid : new com.fasterxml.jackson.databind.node.ObjectNode[]{input.deepCopy().put("idTag", ""), input.deepCopy().put("sampleSeconds", 61)}) {
                var rejected = client.send(HttpRequest.newBuilder(URI.create(base + "/api/plan")).header("Origin", base).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(invalid.toString())).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(400, rejected.statusCode()); assertFalse(Json.MAPPER.readTree(rejected.body()).path("error").asText().isBlank());
            }
            var fleet = client.send(HttpRequest.newBuilder(URI.create(base + "/api/chargers")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(Json.MAPPER.readTree(fleet.body()).isEmpty(), "Generating a plan must not create or connect a charger");
        }
    }
    @Test void servesUiAndRequiresSameOriginForMutations() throws Exception {
        Path settings = directory.resolve("test.properties");
        java.nio.file.Files.writeString(settings, "logs.directory=" + directory.toString().replace('\\', '/') + "/logs\n");
        try (Main app = new Main(new Config(settings), 0)) {
            app.start(); String base = "http://127.0.0.1:" + app.port(); var client = HttpClient.newHttpClient();
            var page = client.send(HttpRequest.newBuilder(URI.create(base)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, page.statusCode()); assertTrue(page.body().contains("BootNotification")); assertTrue(page.headers().firstValue("Content-Security-Policy").isPresent());
            for (String resource : new String[]{"app.js", "style.css"}) assertEquals(200, client.send(HttpRequest.newBuilder(URI.create(base + "/" + resource)).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            var crossSite = client.send(HttpRequest.newBuilder(URI.create(base + "/api/chargers")).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"id\":\"000001-01\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, crossSite.statusCode());
            var create = client.send(HttpRequest.newBuilder(URI.create(base + "/api/chargers")).header("Origin", base).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"id\":\"000001-01\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, create.statusCode()); assertTrue(create.body().contains("charger-000001-01"));
            var duplicate = client.send(HttpRequest.newBuilder(URI.create(base + "/api/chargers")).header("Origin", base).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"id\":\"000001-01\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, duplicate.statusCode());
            var check = client.send(HttpRequest.newBuilder(URI.create(base + "/api/validate")).header("Origin", base).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"chargerId\":\"000001-01\",\"idTag\":\"CARD\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, check.statusCode()); assertTrue(check.body().contains("db.url"));
            var malformed = client.send(HttpRequest.newBuilder(URI.create(base + "/api/chargers")).header("Origin", base).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{" )).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, malformed.statusCode());
        }
    }
}
