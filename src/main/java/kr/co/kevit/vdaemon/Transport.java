package kr.co.kevit.vdaemon;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

interface Transport {
    interface Link { CompletionStage<Void> send(String text); void close(); }
    interface Events { void text(String text); void closed(String reason); }
    CompletionStage<Link> connect(URI uri, String id, String password, Events events);

    static String errorDetails(Throwable error) {
        var details = new StringJoiner(" → ");
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = error; cause != null && seen.add(cause); cause = cause.getCause()) {
            if ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) continue;
            String message = cause.getMessage();
            String detail = cause.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
            if (cause instanceof WebSocketHandshakeException handshake) detail += " (HTTP " + handshake.getResponse().statusCode() + ")";
            details.add(detail);
        }
        return details.toString();
    }

    static Transport jdk(HttpClient client) {
        return (uri, id, password, events) -> {
            var builder = client.newWebSocketBuilder().subprotocols("ocpp1.6").connectTimeout(Duration.ofSeconds(10));
            if (!password.isEmpty()) builder.header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString((id + ":" + password).getBytes(StandardCharsets.UTF_8)));
            return builder.buildAsync(uri, new WebSocket.Listener() {
                private final StringBuilder buffer = new StringBuilder();
                @Override public void onOpen(WebSocket socket) { socket.request(1); }
                @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
                    if (buffer.length() + data.length() > 1_048_576) {
                        socket.abort(); events.closed("응답 크기 제한 초과");
                        return CompletableFuture.completedFuture(null);
                    }
                    buffer.append(data);
                    if (last) { events.text(buffer.toString()); buffer.setLength(0); }
                    socket.request(1); return CompletableFuture.completedFuture(null);
                }
                @Override public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
                    events.closed("WebSocket 종료: " + status); return CompletableFuture.completedFuture(null);
                }
                @Override public void onError(WebSocket socket, Throwable error) { events.closed("WebSocket 연결 오류: " + errorDetails(error)); }
            }).thenApply(socket -> new Link() {
                @Override public CompletionStage<Void> send(String text) { return socket.sendText(text, true).thenApply(w -> null); }
                @Override public void close() { socket.abort(); }
            });
        };
    }
}
