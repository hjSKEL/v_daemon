package kr.co.kevit.vdaemon;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

final class Json {
    private static final java.time.format.DateTimeFormatter OCPP_TIME = new java.time.format.DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    static String timestamp(java.time.Instant instant) { return OCPP_TIME.format(instant); }
    static final ObjectMapper MAPPER = new ObjectMapper();
    static ObjectNode object() { return MAPPER.createObjectNode(); }
    static String required(JsonNode node, String name) {
        String value = node.path(name).asText("").trim();
        if (value.isBlank()) throw new IllegalArgumentException(name + " 값이 필요합니다.");
        return value;
    }
    static int integer(JsonNode node, String name, int fallback, int min, int max) {
        JsonNode value = node.get(name);
        if (value != null && (!value.isIntegralNumber() || !value.canConvertToInt()))
            throw new IllegalArgumentException(name + " 정수가 필요합니다.");
        int result = value == null ? fallback : value.intValue();
        if (result < min || result > max) throw new IllegalArgumentException(name + " 범위: " + min + "~" + max);
        return result;
    }
}
