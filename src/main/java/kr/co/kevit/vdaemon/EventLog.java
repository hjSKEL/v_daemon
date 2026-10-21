package kr.co.kevit.vdaemon;

import com.fasterxml.jackson.databind.node.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.ArrayDeque;

final class EventLog {
    private final Path file;
    private final ArrayDeque<ObjectNode> recent = new ArrayDeque<>();
    private long sequence;
    private String error = "";
    EventLog(Path directory, String id) { file = directory.resolve(id + ".jsonl"); }
    void add(String direction, Object value) {
        ObjectNode row = Json.object().put("sequence", ++sequence).put("time", Instant.now().toString()).put("direction", direction);
        row.set("data", Json.MAPPER.valueToTree(value));
        recent.addLast(row); if (recent.size() > 200) recent.removeFirst();
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file) && Files.size(file) > 5_000_000) {
                for (int i = 2; i >= 1; i--) {
                    Path from = Path.of(file + "." + i);
                    if (Files.exists(from)) Files.move(from, Path.of(file + "." + (i + 1)), StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(file, Path.of(file + ".1"), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(file, row + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            error = "";
        } catch (IOException e) { error = "로그 파일 저장 실패: " + e.getClass().getSimpleName(); }
    }
    ArrayNode snapshot() { return Json.MAPPER.valueToTree(recent); }
    String error() { return error; }
}
