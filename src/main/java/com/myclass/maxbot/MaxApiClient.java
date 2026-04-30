package com.myclass.maxbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;

public class MaxApiClient {
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final String baseUrl;
  private final String token;
  private final List<String> authHeaders;
  private final Object rateLock = new Object();
  private final Queue<Long> requestTimestampsMs = new ArrayDeque<>();

  // Webhook mode benefits from a higher ceiling; 429 retry/backoff still protects API.
  private static final int MAX_RPS = 6;
  private static final long RATE_WINDOW_MS = 1000L;
  private static final int RETRY_429_ATTEMPTS = 4;
  private static final long RETRY_429_BASE_DELAY_MS = 500L;

  public MaxApiClient(String baseUrl, String token, ObjectMapper objectMapper) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.token = token;
    this.authHeaders = buildAuthHeaders(token);
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
  }

  public JsonNode getUpdates(Long marker, int limit, int timeoutSec, List<String> types)
      throws IOException, InterruptedException {
    StringBuilder url = new StringBuilder(baseUrl + "/updates?");
    url.append("limit=").append(limit);
    url.append("&timeout=").append(timeoutSec);
    if (marker != null) {
      url.append("&marker=").append(marker);
    }
    if (types != null && !types.isEmpty()) {
      url.append("&types=").append(encode(String.join(",", types)));
    }

    return getJsonWithAuth(url.toString(), "Max API /updates failed");
  }

  public JsonNode sendMessageToUser(long userId, Map<String, Object> body)
      throws IOException, InterruptedException {
    String url = baseUrl + "/messages?user_id=" + userId;
    return postJson(url, body);
  }

  public JsonNode sendMessageToChat(long chatId, Map<String, Object> body)
      throws IOException, InterruptedException {
    String url = baseUrl + "/messages?chat_id=" + chatId;
    return postJson(url, body);
  }

  public JsonNode answerCallback(String callbackId, Map<String, Object> body)
      throws IOException, InterruptedException {
    String url = baseUrl + "/answers?callback_id=" + encode(callbackId);
    return postJson(url, body);
  }

  private JsonNode postJson(String url, Map<String, Object> body) throws IOException, InterruptedException {
    String json = objectMapper.writeValueAsString(body);
    return postJsonWithAuth(url, json, "Max API POST failed");
  }

  private JsonNode getJsonWithAuth(String url, String errorPrefix) throws IOException, InterruptedException {
    IOException last = null;
    for (String auth : authHeaders) {
      for (int attempt = 0; attempt <= RETRY_429_ATTEMPTS; attempt++) {
        waitForRateSlot();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", auth)
            .GET()
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 300) {
          return objectMapper.readTree(response.body());
        }
        last = new IOException(errorPrefix + ": " + response.statusCode() + " " + response.body());
        if (response.statusCode() == 429 && attempt < RETRY_429_ATTEMPTS) {
          sleep429(attempt);
          continue;
        }
        if (response.statusCode() != 401) {
          break;
        }
        // 401: try next auth header variant.
        break;
      }
    }
    throw last == null ? new IOException(errorPrefix + ": unknown error") : last;
  }

  private JsonNode postJsonWithAuth(String url, String json, String errorPrefix)
      throws IOException, InterruptedException {
    IOException last = null;
    for (String auth : authHeaders) {
      for (int attempt = 0; attempt <= RETRY_429_ATTEMPTS; attempt++) {
        waitForRateSlot();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", auth)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 300) {
          return objectMapper.readTree(response.body());
        }
        last = new IOException(errorPrefix + ": " + response.statusCode() + " " + response.body());
        if (response.statusCode() == 429 && attempt < RETRY_429_ATTEMPTS) {
          sleep429(attempt);
          continue;
        }
        if (response.statusCode() != 401) {
          break;
        }
        // 401: try next auth header variant.
        break;
      }
    }
    throw last == null ? new IOException(errorPrefix + ": unknown error") : last;
  }

  private void waitForRateSlot() throws InterruptedException {
    while (true) {
      long waitMs;
      synchronized (rateLock) {
        long now = System.currentTimeMillis();
        while (!requestTimestampsMs.isEmpty() && now - requestTimestampsMs.peek() >= RATE_WINDOW_MS) {
          requestTimestampsMs.poll();
        }
        if (requestTimestampsMs.size() < MAX_RPS) {
          requestTimestampsMs.add(now);
          return;
        }
        long oldest = requestTimestampsMs.peek();
        waitMs = Math.max(10L, RATE_WINDOW_MS - (now - oldest));
      }
      Thread.sleep(waitMs);
    }
  }

  private void sleep429(int attempt) throws InterruptedException {
    long delay = RETRY_429_BASE_DELAY_MS * (1L << Math.min(attempt, 4));
    Thread.sleep(Math.min(delay, 30_000L));
  }

  private List<String> buildAuthHeaders(String rawToken) {
    if (rawToken == null) {
      return List.of("");
    }
    String trimmed = rawToken.trim();
    if (trimmed.isEmpty()) {
      return List.of("");
    }
    if (trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
      return List.of(trimmed);
    }
    return List.of("Bearer " + trimmed, trimmed);
  }

  private String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
