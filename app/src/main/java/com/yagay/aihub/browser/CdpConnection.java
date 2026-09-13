package com.yagay.aihub.browser;

import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public final class CdpConnection implements AutoCloseable {
    private final OkHttpClient http;
    private final String url;
    private final AtomicInteger sequence = new AtomicInteger();
    private final Map<Integer, CompletableFuture<JSONObject>> pending = new ConcurrentHashMap<>();
    private final CountDownLatch opened = new CountDownLatch(1);

    private volatile WebSocket socket;
    private volatile Throwable openError;
    private volatile BiConsumer<String, JSONObject> eventHandler = (method, params) -> {};

    public CdpConnection(OkHttpClient http, String url) {
        this.http = http;
        this.url = url;
    }

    public void connect(long timeoutMillis) throws Exception {
        Request request = new Request.Builder().url(url).build();
        socket = http.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                opened.countDown();
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                handleMessage(text);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable throwable, Response response) {
                openError = throwable;
                opened.countDown();
                failPending(throwable);
            }
        });
        if (!opened.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("CDP WebSocket connection timed out");
        }
        if (openError != null) throw new IllegalStateException("CDP WebSocket failed", openError);
    }

    public void setEventHandler(BiConsumer<String, JSONObject> handler) {
        eventHandler = handler == null ? (method, params) -> {} : handler;
    }

    public JSONObject command(String method, JSONObject params, long timeoutMillis) throws Exception {
        int id = sequence.incrementAndGet();
        JSONObject request = new JSONObject();
        request.put("id", id);
        request.put("method", method);
        if (params != null) request.put("params", params);

        CompletableFuture<JSONObject> future = new CompletableFuture<>();
        pending.put(id, future);
        WebSocket current = socket;
        if (current == null || !current.send(request.toString())) {
            pending.remove(id);
            throw new IllegalStateException("CDP socket is closed");
        }
        JSONObject reply = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        JSONObject error = reply.optJSONObject("error");
        if (error != null) {
            throw new IllegalStateException("CDP " + method + " failed: " + error.optString("message"));
        }
        JSONObject result = reply.optJSONObject("result");
        return result == null ? new JSONObject() : result;
    }

    private void handleMessage(String text) {
        try {
            JSONObject message = new JSONObject(text);
            if (message.has("id")) {
                CompletableFuture<JSONObject> future = pending.remove(message.optInt("id"));
                if (future != null) future.complete(message);
                return;
            }
            String method = message.optString("method", "");
            JSONObject params = message.optJSONObject("params");
            if (!method.isBlank()) eventHandler.accept(method, params == null ? new JSONObject() : params);
        } catch (Exception ignored) {}
    }

    private void failPending(Throwable error) {
        for (CompletableFuture<JSONObject> future : pending.values()) future.completeExceptionally(error);
        pending.clear();
    }

    @Override
    public void close() {
        WebSocket current = socket;
        socket = null;
        if (current != null) current.close(1000, "AIHub done");
        failPending(new IllegalStateException("CDP closed"));
    }
}
