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
import java.util.List;
import java.util.Map;

public class MaxApiClient {
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final String baseUrl;
  private final String token;

  public MaxApiClient(String baseUrl, String token, ObjectMapper objectMapper) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.token = token;
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

    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(url.toString()))
        .header("Authorization", token)
        .GET()
        .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() >= 300) {
      throw new IOException("Max API /updates failed: " + response.statusCode() + " " + response.body());
    }
    return objectMapper.readTree(response.body());
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
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("Authorization", token)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json))
        .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() >= 300) {
      throw new IOException("Max API POST failed: " + response.statusCode() + " " + response.body());
    }
    return objectMapper.readTree(response.body());
  }

  private String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
