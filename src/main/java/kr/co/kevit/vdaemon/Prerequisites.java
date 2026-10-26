package kr.co.kevit.vdaemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

final class Prerequisites {
    private final Config config;
    Prerequisites(Config config) { this.config = config; }
    ArrayNode meters(String chargerId) throws SQLException {
        if (!chargerId.matches("[0-9]{6}-[0-9]{2}")) throw new IllegalArgumentException("충전기 ID 형식 오류");
        String[] ids = chargerId.split("-");
        try (Connection db = connect()) {
            db.setReadOnly(true);
            return query(db, "SELECT EVSE_ID,CA_ELE_NRG FROM TB_CHCS005 WHERE CP_ID=? AND CS_ID=? ORDER BY EVSE_ID", ids[0], ids[1]);
        }
    }
    private Connection connect() throws SQLException {
        String url = config.get("db.url", "");
        if (url.isBlank()) throw new IllegalStateException("config/local.properties에 조회할 db.url, db.username, db.password를 설정하세요.");
        if (!url.startsWith("jdbc:mysql://")) throw new IllegalArgumentException("db.url은 jdbc:mysql:// 형식이어야 합니다.");
        var properties = new Properties(); properties.setProperty("user", config.get("db.username", "")); properties.setProperty("password", config.get("db.password", ""));
        properties.setProperty("connectTimeout", "5000"); properties.setProperty("socketTimeout", "5000");
        return DriverManager.getConnection(url, properties);
    }
    ObjectNode inspect(JsonNode input) throws SQLException {
        String chargerId = Json.required(input, "chargerId"), idTag = Json.required(input, "idTag");
        if (!chargerId.matches("[0-9]{6}-[0-9]{2}")) throw new IllegalArgumentException("충전기 ID 형식 오류");
        String[] ids = chargerId.split("-");
        Instant timestamp = Instant.parse(input.path("timestamp").asText(Instant.now().toString()));
        String date = timestamp.atZone(ZoneId.of("Asia/Seoul")).toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE);
        ObjectNode result = Json.object().put("chargerId", chargerId).put("date", date).put("readOnly", true);
        ArrayNode checks = result.putArray("checks");
        try (Connection db = connect()) {
            db.setReadOnly(true);
            ArrayNode site = query(db, "SELECT CP_ID FROM TB_CHCP001 WHERE CP_ID=?", ids[0]);
            check(checks, "충전소 TB_CHCP001", !site.isEmpty(), site);
            ArrayNode charger = query(db, "SELECT CP_ID,CS_ID,CS_CHN_CNT,US_YN,LH_CP_CS_ID,PRD_TP FROM TB_CHCS001 WHERE CP_ID=? AND CS_ID=?", ids[0], ids[1]);
            check(checks, "충전기 TB_CHCS001 / 사용·LH 매핑", !charger.isEmpty() && !charger.get(0).path("US_YN").asText().equals("N") && !charger.get(0).path("LH_CP_CS_ID").asText("").isBlank(), charger);
            ArrayNode csm = query(db, "SELECT CP_ID,CS_ID FROM TB_CHCS002 WHERE CP_ID=? AND CS_ID=?", ids[0], ids[1]);
            check(checks, "충전기 부가정보 TB_CHCS002", !csm.isEmpty(), csm);
            ArrayNode states = query(db, "SELECT EVSE_ID,CS_STAT_CD,CA_ELE_NRG,RC_ID FROM TB_CHCS005 WHERE CP_ID=? AND CS_ID=? ORDER BY EVSE_ID", ids[0], ids[1]);
            int expected = charger.isEmpty() ? 0 : charger.get(0).path("CS_CHN_CNT").asInt();
            boolean complete = expected > 0;
            for (int number = 1; number <= expected; number++) {
                final int n = number;
                boolean found = false; for (JsonNode state : states) if (state.path("EVSE_ID").asInt() == n) found = true;
                complete &= found;
            }
            check(checks, "커넥터 상태 TB_CHCS005 (meterStart 참고: CA_ELE_NRG는 kWh)", complete, states);
            ArrayNode card = query(db, "SELECT CUT_CRD_NO,STOP_YN,DEL_YN FROM TB_CUCU002 WHERE CUT_CRD_NO=?", idTag);
            check(checks, "회원카드 TB_CUCU002", !card.isEmpty() && !card.get(0).path("STOP_YN").asText().equals("Y") && !card.get(0).path("DEL_YN").asText().equals("Y"), card);
            String product = charger.isEmpty() ? "" : charger.get(0).path("PRD_TP").asText("");
            ArrayNode policy = query(db, "SELECT ID,NAME FROM TB_PDPD001 WHERE ID=?", product);
            check(checks, "요금정책 TB_CHCS001.PRD_TP → TB_PDPD001.ID", !policy.isEmpty(), policy);
            ArrayNode prices = query(db, "SELECT ID,PROD_TP,STRT_DT,END_DT,FEE FROM TB_PDPD002 WHERE PROD_TP LIKE CONCAT(?, '%') AND STRT_DT<=? AND END_DT>? ORDER BY SEQ", product, date, date);
            check(checks, "적용 단가 TB_PDPD002 (STRT_DT ≤ 기준일 < END_DT)", !prices.isEmpty(), prices);
            boolean ok = true; for (JsonNode item : checks) ok &= item.path("ok").asBoolean();
            result.put("ok", ok); result.put("database", db.getCatalog());
        }
        return result;
    }
    private void check(ArrayNode checks, String name, boolean ok, ArrayNode rows) {
        checks.add(Json.object().put("name", name).put("ok", ok).set("rows", rows));
    }
    private ArrayNode query(Connection db, String sql, String... args) throws SQLException {
        ArrayNode result = Json.MAPPER.createArrayNode();
        try (PreparedStatement statement = db.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            for (int i = 0; i < args.length; i++) statement.setString(i + 1, args[i]);
            try (ResultSet rows = statement.executeQuery()) {
                var columns = rows.getMetaData();
                while (rows.next()) {
                    ObjectNode row = result.addObject();
                    for (int i = 1; i <= columns.getColumnCount(); i++) row.set(columns.getColumnLabel(i), Json.MAPPER.valueToTree(rows.getObject(i)));
                }
            }
        }
        return result;
    }
}
