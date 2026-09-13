package com.yagay.aihub.bridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Loopback-only bridge between the NextChat UI and the Titanium companion extension.
 *
 * NextChat uses the tiny OpenAI-compatible HTTP surface. Titanium uses a persistent
 * RFC6455 WebSocket at /bridge. No provider API key or provider HTTP API is used here;
 * the extension drives the logged-in web pages directly.
 */
public final class LocalBroker implements Closeable {
    public interface ProviderLauncher { void launch(String provider); }

    public static final int PORT = 3847;
    private static final String UI_ORIGIN = "https://appassets.androidplatform.net";
    private static final int MAX_HTTP_BODY = 2 * 1024 * 1024;
    private static final int MAX_WS_MESSAGE = 2 * 1024 * 1024;

    private static final Map<String, String> MODEL_TO_PROVIDER = new LinkedHashMap<>();
    static {
        MODEL_TO_PROVIDER.put("chatgpt-web", "chatgpt");
        MODEL_TO_PROVIDER.put("claude-web", "claude");
        MODEL_TO_PROVIDER.put("gemini-web", "gemini");
        MODEL_TO_PROVIDER.put("deepseek-web", "deepseek");
        MODEL_TO_PROVIDER.put("grok-web", "grok");
    }

    private final ProviderLauncher launcher;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final Object lock = new Object();
    private final ArrayDeque<Command> queue = new ArrayDeque<>();
    private final Set<String> queuedIds = new HashSet<>();
    private final Map<String, Command> commands = new HashMap<>();
    private final Map<String, CompletableFuture<Result>> pending = new HashMap<>();
    private final Set<WsClient> clients = new LinkedHashSet<>();

    private volatile boolean running;
    private ServerSocket server;

    public LocalBroker(ProviderLauncher launcher) {
        this.launcher = launcher;
    }

    public void start() {
        if (running) return;
        running = true;
        workers.execute(() -> {
            try {
                server = new ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1"));
                while (running) {
                    Socket socket = server.accept();
                    socket.setTcpNoDelay(true);
                    workers.execute(() -> handle(socket));
                }
            } catch (Exception ignored) {
                running = false;
            }
        });
    }

    /** Privacy-safe live state for one-click diagnostics. No prompts or responses are included. */
    public String diagnosticSnapshot() {
        JSONObject root = new JSONObject();
        try {
            root.put("running", running);
            root.put("port", PORT);
            root.put("serverBound", server != null && server.isBound() && !server.isClosed());
            synchronized (lock) {
                root.put("bridgeClients", clients.size());
                root.put("queuedCommands", queue.size());
                root.put("pendingCommands", pending.size());
                root.put("knownCommands", commands.size());

                JSONArray clientArray = new JSONArray();
                int index = 0;
                for (WsClient client : clients) {
                    JSONObject item = new JSONObject();
                    item.put("index", index++);
                    item.put("open", client.open);
                    item.put("providers", new JSONArray(client.providers));
                    item.put("assignedCount", client.assigned.size());
                    clientArray.put(item);
                }
                root.put("clients", clientArray);
            }
        } catch (Exception ignored) {
        }
        return root.toString();
    }

    private void handle(Socket socket) {
        try {
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());
            Request request = readRequest(in);
            if (request == null) {
                socket.close();
                return;
            }

            if (request.isWebSocketUpgrade() && "/bridge".equals(request.path)) {
                handleWebSocket(socket, in, out, request);
                return;
            }

            try (Socket ignored = socket;
                 BufferedInputStream ignoredIn = in;
                 BufferedOutputStream ignoredOut = out) {
                handleHttp(out, request);
            }
        } catch (Exception ignored) {
            try { socket.close(); } catch (Exception ignoredClose) {}
        }
    }

    private void handleHttp(OutputStream out, Request request) throws Exception {
        if ("OPTIONS".equals(request.method)) {
            write(out, 204, "text/plain", "", request.origin);
            return;
        }

        if ("GET".equals(request.method) && "/health".equals(request.path)) {
            writeJson(out, 200, new JSONObject()
                    .put("status", "ok")
                    .put("transport", "titanium-websocket")
                    .put("bridgeClients", connectedClientCount()), request.origin);
            return;
        }

        if (!isUiOriginAllowed(request.origin)) {
            writeJson(out, 403, new JSONObject().put("error", "forbidden_origin"), null);
            return;
        }

        if ("GET".equals(request.method) && "/v1/models".equals(request.path)) {
            JSONArray data = new JSONArray();
            for (String model : MODEL_TO_PROVIDER.keySet()) {
                data.put(new JSONObject()
                        .put("id", model)
                        .put("object", "model")
                        .put("root", model));
            }
            writeJson(out, 200,
                    new JSONObject().put("object", "list").put("data", data),
                    request.origin);
            return;
        }

        if ("POST".equals(request.method) && "/v1/chat/completions".equals(request.path)) {
            chat(out, new JSONObject(request.body), request.origin);
            return;
        }

        writeJson(out, 404, new JSONObject().put("error", "not_found"), request.origin);
    }

    private int connectedClientCount() {
        synchronized (lock) {
            return clients.size();
        }
    }

    private static boolean isUiOriginAllowed(String origin) {
        return UI_ORIGIN.equals(origin);
    }

    private void chat(OutputStream out, JSONObject body, String origin) throws Exception {
        String model = body.optString("model", "chatgpt-web");
        String provider = MODEL_TO_PROVIDER.get(model);
        if (provider == null) {
            writeJson(out, 400,
                    new JSONObject().put("error",
                            new JSONObject().put("message", "Unsupported web model: " + model)),
                    origin);
            return;
        }

        String id = UUID.randomUUID().toString();
        CompletableFuture<Result> future = new CompletableFuture<>();
        Command command = new Command(id, provider,
                flattenMessages(body.optJSONArray("messages")));

        synchronized (lock) {
            pending.put(id, future);
            commands.put(id, command);
            enqueueLocked(command);
        }

        launcher.launch(provider);
        dispatchQueued();

        Result result;
        try {
            result = future.get(210, TimeUnit.SECONDS);
        } catch (Exception timeout) {
            result = new Result("",
                    "Titanium extension timed out. Open Titanium, confirm the AIHub extension is enabled, and make sure the provider is logged in.");
        } finally {
            synchronized (lock) {
                pending.remove(id);
                commands.remove(id);
                queuedIds.remove(id);
                queue.removeIf(c -> id.equals(c.id));
                for (WsClient client : clients) {
                    client.assigned.remove(id);
                }
            }
        }

        if (!result.error.isEmpty()) {
            writeJson(out, 502,
                    new JSONObject().put("error",
                            new JSONObject().put("message", result.error)),
                    origin);
            return;
        }

        if (body.optBoolean("stream", true)) {
            String chunk = new JSONObject()
                    .put("id", "chatcmpl-" + id)
                    .put("object", "chat.completion.chunk")
                    .put("choices", new JSONArray().put(new JSONObject()
                            .put("index", 0)
                            .put("delta", new JSONObject().put("content", result.response))
                            .put("finish_reason", JSONObject.NULL)))
                    .toString();
            String done = new JSONObject()
                    .put("id", "chatcmpl-" + id)
                    .put("object", "chat.completion.chunk")
                    .put("choices", new JSONArray().put(new JSONObject()
                            .put("index", 0)
                            .put("delta", new JSONObject())
                            .put("finish_reason", "stop")))
                    .toString();
            write(out, 200, "text/event-stream",
                    "data: " + chunk + "\n\ndata: " + done + "\n\ndata: [DONE]\n\n",
                    origin);
        } else {
            JSONObject response = new JSONObject()
                    .put("id", "chatcmpl-" + id)
                    .put("object", "chat.completion")
                    .put("choices", new JSONArray().put(new JSONObject()
                            .put("index", 0)
                            .put("message", new JSONObject()
                                    .put("role", "assistant")
                                    .put("content", result.response))
                            .put("finish_reason", "stop")));
            writeJson(out, 200, response, origin);
        }
    }

    private void handleWebSocket(Socket socket,
                                 BufferedInputStream in,
                                 BufferedOutputStream out,
                                 Request request) throws Exception {
        String origin = request.headers.get("origin");
        if (origin == null || !origin.startsWith("chrome-extension://")) {
            write(out, 403, "text/plain", "Forbidden", null);
            socket.close();
            return;
        }

        String key = request.headers.get("sec-websocket-key");
        if (key == null || key.trim().isEmpty()) {
            write(out, 400, "text/plain", "Missing WebSocket key", null);
            socket.close();
            return;
        }

        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + websocketAccept(key) + "\r\n\r\n";
        out.write(response.getBytes(StandardCharsets.US_ASCII));
        out.flush();

        WsClient client = new WsClient(socket, in, out);
        synchronized (lock) {
            clients.add(client);
        }
        client.sendJson(new JSONObject().put("type", "ready").put("protocol", 1));

        ByteArrayOutputStream fragmented = null;
        int fragmentedOpcode = -1;
        try {
            while (running && !socket.isClosed()) {
                WsFrame frame = readFrame(in);
                if (frame == null || frame.opcode == 0x8) {
                    break;
                }
                if (frame.opcode == 0x9) {
                    client.sendFrame(0xA, frame.payload);
                    continue;
                }
                if (frame.opcode == 0xA) {
                    continue;
                }

                if (frame.opcode == 0x1 || frame.opcode == 0x2) {
                    if (frame.fin) {
                        if (frame.opcode == 0x1) {
                            processWebSocketMessage(client,
                                    new String(frame.payload, StandardCharsets.UTF_8));
                        }
                    } else {
                        if (frame.payload.length > MAX_WS_MESSAGE) {
                            throw new IllegalArgumentException("WebSocket message too large");
                        }
                        fragmented = new ByteArrayOutputStream();
                        fragmented.write(frame.payload);
                        fragmentedOpcode = frame.opcode;
                    }
                    continue;
                }

                if (frame.opcode == 0x0 && fragmented != null) {
                    if (fragmented.size() + frame.payload.length > MAX_WS_MESSAGE) {
                        throw new IllegalArgumentException("WebSocket message too large");
                    }
                    fragmented.write(frame.payload);
                    if (frame.fin) {
                        if (fragmentedOpcode == 0x1) {
                            processWebSocketMessage(client,
                                    new String(fragmented.toByteArray(), StandardCharsets.UTF_8));
                        }
                        fragmented = null;
                        fragmentedOpcode = -1;
                    }
                }
            }
        } finally {
            onClientClosed(client);
        }
    }

    private void processWebSocketMessage(WsClient client, String text) {
        try {
            JSONObject message = new JSONObject(text);
            String type = message.optString("type", "");

            if ("hello".equals(type) || "providers".equals(type)) {
                Set<String> providers = new HashSet<>();
                JSONArray array = message.optJSONArray("providers");
                if (array != null) {
                    for (int i = 0; i < array.length(); i++) {
                        String provider = array.optString(i, "");
                        if (MODEL_TO_PROVIDER.containsValue(provider)) {
                            providers.add(provider);
                        }
                    }
                }
                synchronized (lock) {
                    client.providers.clear();
                    client.providers.addAll(providers);
                }
                dispatchQueued();
                return;
            }

            if ("result".equals(type)) {
                String id = message.optString("id", "");
                CompletableFuture<Result> future;
                synchronized (lock) {
                    client.assigned.remove(id);
                    future = pending.get(id);
                }
                if (future != null) {
                    future.complete(new Result(
                            message.optString("response", ""),
                            message.optString("error", "")));
                }
                return;
            }

            if ("ping".equals(type)) {
                client.sendJson(new JSONObject().put("type", "pong"));
            }
        } catch (Exception ignored) {
        }
    }

    private void dispatchQueued() {
        workers.execute(() -> {
            while (running) {
                Command command = null;
                WsClient client = null;

                synchronized (lock) {
                    Iterator<Command> iterator = queue.iterator();
                    while (iterator.hasNext() && command == null) {
                        Command candidate = iterator.next();
                        for (WsClient candidateClient : clients) {
                            if (candidateClient.open
                                    && candidateClient.providers.contains(candidate.provider)) {
                                command = candidate;
                                client = candidateClient;
                                iterator.remove();
                                queuedIds.remove(candidate.id);
                                candidateClient.assigned.add(candidate.id);
                                break;
                            }
                        }
                    }
                }

                if (command == null || client == null) {
                    return;
                }

                try {
                    client.sendJson(command.toJson());
                } catch (Exception sendFailed) {
                    synchronized (lock) {
                        client.assigned.remove(command.id);
                        if (pending.containsKey(command.id)) {
                            enqueueLocked(command);
                        }
                    }
                    onClientClosed(client);
                }
            }
        });
    }

    private void onClientClosed(WsClient client) {
        Set<String> retry = new HashSet<>();
        synchronized (lock) {
            if (!clients.remove(client)) {
                return;
            }
            client.open = false;
            retry.addAll(client.assigned);
            client.assigned.clear();
            for (String id : retry) {
                Command command = commands.get(id);
                if (command != null && pending.containsKey(id)) {
                    enqueueLocked(command);
                }
            }
        }
        client.closeQuietly();
        if (!retry.isEmpty()) {
            dispatchQueued();
        }
    }

    private void enqueueLocked(Command command) {
        if (queuedIds.add(command.id)) {
            queue.addLast(command);
        }
    }

    private static String flattenMessages(JSONArray messages) {
        if (messages == null || messages.length() == 0) {
            return "";
        }

        StringBuilder text = new StringBuilder();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) {
                continue;
            }
            String role = message.optString("role", "user");
            Object content = message.opt("content");
            String value = content instanceof String
                    ? (String) content
                    : String.valueOf(content == null ? "" : content);
            if (value.trim().isEmpty()) {
                continue;
            }
            text.append(role.toUpperCase(Locale.ROOT))
                    .append(": ")
                    .append(value)
                    .append("\n\n");
        }
        text.append("Answer the latest USER message while respecting the conversation above.");
        return text.toString();
    }

    private static Request readRequest(InputStream in) throws Exception {
        String first = readLine(in);
        if (first == null || first.trim().isEmpty()) {
            return null;
        }

        String[] parts = first.split(" ", 3);
        if (parts.length < 2) {
            return null;
        }

        Map<String, String> headers = new HashMap<>();
        int length = 0;
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            headers.put(name, value);
            if ("content-length".equals(name)) {
                length = Integer.parseInt(value);
            }
        }

        if (length < 0 || length > MAX_HTTP_BODY) {
            throw new IllegalArgumentException("HTTP body too large");
        }

        byte[] body = readExact(in, length);
        URI uri = URI.create(parts[1]);
        return new Request(
                parts[0],
                uri.getPath(),
                new String(body, StandardCharsets.UTF_8),
                headers,
                headers.get("origin"));
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                out.write(b);
            }
            if (out.size() > 16 * 1024) {
                throw new IllegalArgumentException("HTTP header line too large");
            }
        }
        if (b < 0 && out.size() == 0) {
            return null;
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static byte[] readExact(InputStream in, int length) throws Exception {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = in.read(data, offset, length - offset);
            if (count < 0) {
                throw new EOFException();
            }
            offset += count;
        }
        return data;
    }

    private static String websocketAccept(String key) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-1")
                .digest((key.trim() + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                        .getBytes(StandardCharsets.US_ASCII));
        return Base64.getEncoder().encodeToString(digest);
    }

    private static WsFrame readFrame(InputStream in) throws Exception {
        int b0 = in.read();
        if (b0 < 0) {
            return null;
        }
        int b1 = in.read();
        if (b1 < 0) {
            throw new EOFException();
        }

        boolean fin = (b0 & 0x80) != 0;
        int opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        if (!masked) {
            throw new IllegalArgumentException("Client WebSocket frames must be masked");
        }

        long length = b1 & 0x7F;
        if (length == 126) {
            byte[] ext = readExact(in, 2);
            length = ((ext[0] & 0xFFL) << 8) | (ext[1] & 0xFFL);
        } else if (length == 127) {
            byte[] ext = readExact(in, 8);
            length = ByteBuffer.wrap(ext).getLong();
        }

        if (length < 0 || length > MAX_WS_MESSAGE) {
            throw new IllegalArgumentException("WebSocket frame too large");
        }

        byte[] mask = readExact(in, 4);
        byte[] payload = readExact(in, (int) length);
        for (int i = 0; i < payload.length; i++) {
            payload[i] ^= mask[i & 3];
        }
        return new WsFrame(fin, opcode, payload);
    }

    private static void writeJson(OutputStream out,
                                  int status,
                                  JSONObject json,
                                  String origin) throws Exception {
        write(out, status, "application/json; charset=utf-8", json.toString(), origin);
    }

    private static void write(OutputStream out,
                              int status,
                              String type,
                              String body,
                              String origin) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String reason = status == 200 ? "OK"
                : status == 204 ? "No Content"
                : status == 400 ? "Bad Request"
                : status == 403 ? "Forbidden"
                : status == 404 ? "Not Found"
                : status == 502 ? "Bad Gateway"
                : "Status";
        StringBuilder headers = new StringBuilder();
        headers.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
                .append("Content-Type: ").append(type).append("\r\n")
                .append("Content-Length: ").append(bytes.length).append("\r\n")
                .append("Connection: close\r\n");
        if (origin != null && isUiOriginAllowed(origin)) {
            headers.append("Access-Control-Allow-Origin: ").append(UI_ORIGIN).append("\r\n")
                    .append("Access-Control-Allow-Headers: Content-Type, Authorization\r\n")
                    .append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
                    .append("Vary: Origin\r\n");
        }
        headers.append("\r\n");
        out.write(headers.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    @Override
    public void close() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        synchronized (lock) {
            for (WsClient client : new HashSet<>(clients)) {
                client.closeQuietly();
            }
            clients.clear();
            for (CompletableFuture<Result> future : pending.values()) {
                future.complete(new Result("", "AIHub broker stopped"));
            }
            pending.clear();
            commands.clear();
            queue.clear();
            queuedIds.clear();
        }
        workers.shutdownNow();
    }

    private static final class Request {
        final String method;
        final String path;
        final String body;
        final Map<String, String> headers;
        final String origin;

        Request(String method, String path, String body,
                Map<String, String> headers, String origin) {
            this.method = method;
            this.path = path;
            this.body = body;
            this.headers = headers;
            this.origin = origin;
        }

        boolean isWebSocketUpgrade() {
            return "websocket".equalsIgnoreCase(headers.get("upgrade"))
                    && headers.getOrDefault("connection", "")
                    .toLowerCase(Locale.ROOT).contains("upgrade");
        }
    }

    private static final class Command {
        final String id;
        final String provider;
        final String prompt;

        Command(String id, String provider, String prompt) {
            this.id = id;
            this.provider = provider;
            this.prompt = prompt;
        }

        JSONObject toJson() throws org.json.JSONException {
            return new JSONObject()
                    .put("type", "ask")
                    .put("id", id)
                    .put("provider", provider)
                    .put("prompt", prompt);
        }
    }

    private static final class Result {
        final String response;
        final String error;

        Result(String response, String error) {
            this.response = response == null ? "" : response;
            this.error = error == null ? "" : error;
        }
    }

    private static final class WsFrame {
        final boolean fin;
        final int opcode;
        final byte[] payload;

        WsFrame(boolean fin, int opcode, byte[] payload) {
            this.fin = fin;
            this.opcode = opcode;
            this.payload = payload;
        }
    }

    private static final class WsClient {
        final Socket socket;
        final InputStream in;
        final OutputStream out;
        final Set<String> providers = new HashSet<>();
        final Set<String> assigned = new HashSet<>();
        final Object sendLock = new Object();
        volatile boolean open = true;

        WsClient(Socket socket, InputStream in, OutputStream out) {
            this.socket = socket;
            this.in = in;
            this.out = out;
        }

        void sendJson(JSONObject json) throws Exception {
            sendFrame(0x1, json.toString().getBytes(StandardCharsets.UTF_8));
        }

        void sendFrame(int opcode, byte[] payload) throws Exception {
            synchronized (sendLock) {
                if (!open) throw new EOFException("WebSocket client closed");
                int length = payload.length;
                out.write(0x80 | (opcode & 0x0F));
                if (length < 126) {
                    out.write(length);
                } else if (length <= 0xFFFF) {
                    out.write(126);
                    out.write((length >>> 8) & 0xFF);
                    out.write(length & 0xFF);
                } else {
                    out.write(127);
                    long value = length;
                    for (int shift = 56; shift >= 0; shift -= 8) {
                        out.write((int) ((value >>> shift) & 0xFF));
                    }
                }
                out.write(payload);
                out.flush();
            }
        }

        void closeQuietly() {
            open = false;
            try { socket.close(); } catch (Exception ignored) {}
        }
    }
}
