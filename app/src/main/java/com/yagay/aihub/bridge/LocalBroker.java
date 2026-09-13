package com.yagay.aihub.bridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Loopback-only IPC. It never calls an AI provider API; Titanium content scripts do the web work. */
public final class LocalBroker implements Closeable {
    public interface ProviderLauncher { void launch(String provider); }

    private static final int PORT = 3847;
    private static final Map<String, String> MODEL_TO_PROVIDER = Map.of(
            "chatgpt-web", "chatgpt",
            "claude-web", "claude",
            "gemini-web", "gemini",
            "deepseek-web", "deepseek",
            "grok-web", "grok"
    );

    private final ProviderLauncher launcher;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final Object lock = new Object();
    private final ArrayDeque<Command> queue = new ArrayDeque<>();
    private final Map<String, CompletableFuture<Result>> pending = new HashMap<>();
    private volatile boolean running;
    private ServerSocket server;

    public LocalBroker(ProviderLauncher launcher) { this.launcher = launcher; }

    public void start() {
        if (running) return;
        running = true;
        workers.execute(() -> {
            try {
                server = new ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1"));
                while (running) {
                    Socket socket = server.accept();
                    workers.execute(() -> handle(socket));
                }
            } catch (Exception ignored) {
                running = false;
            }
        });
    }

    private void handle(Socket socket) {
        try (socket; BufferedInputStream in = new BufferedInputStream(socket.getInputStream()); BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream())) {
            Request request = readRequest(in);
            if (request == null) return;

            if ("OPTIONS".equals(request.method)) {
                write(out, 204, "text/plain", "");
                return;
            }
            if ("GET".equals(request.method) && "/health".equals(request.path)) {
                writeJson(out, 200, new JSONObject().put("status", "ok").put("transport", "titanium-extension"));
                return;
            }
            if ("GET".equals(request.method) && "/v1/models".equals(request.path)) {
                JSONArray data = new JSONArray();
                for (String model : MODEL_TO_PROVIDER.keySet()) {
                    data.put(new JSONObject().put("id", model).put("object", "model").put("root", model));
                }
                writeJson(out, 200, new JSONObject().put("object", "list").put("data", data));
                return;
            }
            if ("POST".equals(request.method) && "/v1/chat/completions".equals(request.path)) {
                chat(out, new JSONObject(request.body));
                return;
            }
            if ("GET".equals(request.method) && "/bridge/poll".equals(request.path)) {
                Set<String> providers = queryProviders(request.query);
                Command command = takeFor(providers);
                if (command == null) write(out, 204, "application/json", "");
                else writeJson(out, 200, command.toJson());
                return;
            }
            if ("POST".equals(request.method) && "/bridge/result".equals(request.path)) {
                JSONObject body = new JSONObject(request.body);
                String id = body.optString("id", "");
                CompletableFuture<Result> future;
                synchronized (lock) { future = pending.get(id); }
                if (future != null) future.complete(new Result(body.optString("response", ""), body.optString("error", "")));
                writeJson(out, 200, new JSONObject().put("ok", true));
                return;
            }
            writeJson(out, 404, new JSONObject().put("error", "not_found"));
        } catch (Exception ignored) {}
    }

    private void chat(OutputStream out, JSONObject body) throws Exception {
        String model = body.optString("model", "chatgpt-web");
        String provider = MODEL_TO_PROVIDER.get(model);
        if (provider == null) {
            writeJson(out, 400, new JSONObject().put("error", new JSONObject().put("message", "Unsupported web model: " + model)));
            return;
        }

        String id = UUID.randomUUID().toString();
        CompletableFuture<Result> future = new CompletableFuture<>();
        Command command = new Command(id, provider, flattenMessages(body.optJSONArray("messages")));
        synchronized (lock) {
            pending.put(id, future);
            queue.addLast(command);
        }
        launcher.launch(provider);

        Result result;
        try {
            result = future.get(210, TimeUnit.SECONDS);
        } catch (Exception timeout) {
            result = new Result("", "Titanium extension timed out. Open Titanium and confirm the AIHub extension and website login are active.");
        } finally {
            synchronized (lock) { pending.remove(id); }
        }

        if (!result.error.isEmpty()) {
            writeJson(out, 502, new JSONObject().put("error", new JSONObject().put("message", result.error)));
            return;
        }

        if (body.optBoolean("stream", true)) {
            String chunk = new JSONObject()
                    .put("id", "chatcmpl-" + id)
                    .put("object", "chat.completion.chunk")
                    .put("choices", new JSONArray().put(new JSONObject().put("index", 0)
                            .put("delta", new JSONObject().put("content", result.response))
                            .put("finish_reason", JSONObject.NULL)))
                    .toString();
            String done = new JSONObject()
                    .put("id", "chatcmpl-" + id)
                    .put("object", "chat.completion.chunk")
                    .put("choices", new JSONArray().put(new JSONObject().put("index", 0)
                            .put("delta", new JSONObject())
                            .put("finish_reason", "stop")))
                    .toString();
            write(out, 200, "text/event-stream", "data: " + chunk + "\n\ndata: " + done + "\n\ndata: [DONE]\n\n");
        } else {
            JSONObject response = new JSONObject()
                    .put("id", "chatcmpl-" + id)
                    .put("object", "chat.completion")
                    .put("choices", new JSONArray().put(new JSONObject().put("index", 0)
                            .put("message", new JSONObject().put("role", "assistant").put("content", result.response))
                            .put("finish_reason", "stop")));
            writeJson(out, 200, response);
        }
    }

    private Command takeFor(Set<String> providers) {
        synchronized (lock) {
            Iterator<Command> iterator = queue.iterator();
            while (iterator.hasNext()) {
                Command command = iterator.next();
                if (providers.contains(command.provider)) {
                    iterator.remove();
                    return command;
                }
            }
            return null;
        }
    }

    private static Set<String> queryProviders(String query) {
        Set<String> values = new HashSet<>();
        if (query == null) return values;
        for (String part : query.split("&")) {
            if (!part.startsWith("providers=")) continue;
            String raw = part.substring("providers=".length());
            for (String provider : raw.split(",")) if (!provider.isBlank()) values.add(provider);
        }
        return values;
    }

    private static String flattenMessages(JSONArray messages) {
        if (messages == null || messages.length() == 0) return "";
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) continue;
            String role = message.optString("role", "user");
            Object content = message.opt("content");
            String value = content instanceof String ? (String) content : String.valueOf(content == null ? "" : content);
            if (value.isBlank()) continue;
            text.append(role.toUpperCase()).append(": ").append(value).append("\n\n");
        }
        text.append("Answer the latest USER message while respecting the conversation above.");
        return text.toString();
    }

    private static Request readRequest(InputStream in) throws Exception {
        String first = readLine(in);
        if (first == null || first.isBlank()) return null;
        String[] parts = first.split(" ", 3);
        if (parts.length < 2) return null;
        int length = 0;
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0 && "content-length".equalsIgnoreCase(line.substring(0, colon).trim())) {
                length = Integer.parseInt(line.substring(colon + 1).trim());
            }
        }
        byte[] body = in.readNBytes(length);
        URI uri = URI.create(parts[1]);
        return new Request(parts[0], uri.getPath(), uri.getRawQuery(), new String(body, StandardCharsets.UTF_8));
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') break;
            if (b != '\r') out.write(b);
        }
        if (b < 0 && out.size() == 0) return null;
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void writeJson(OutputStream out, int status, JSONObject json) throws Exception {
        write(out, status, "application/json; charset=utf-8", json.toString());
    }

    private static void write(OutputStream out, int status, String type, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String reason = status == 200 ? "OK" : status == 204 ? "No Content" : "Error";
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Headers: *\r\n"
                + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                + "Access-Control-Allow-Private-Network: true\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }

    @Override public void close() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        workers.shutdownNow();
    }

    private record Request(String method, String path, String query, String body) {}
    private record Result(String response, String error) {}
    private record Command(String id, String provider, String prompt) {
        JSONObject toJson() { return new JSONObject().put("id", id).put("provider", provider).put("prompt", prompt); }
    }
}
