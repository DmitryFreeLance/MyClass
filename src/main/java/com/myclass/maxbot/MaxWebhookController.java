package com.myclass.maxbot;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MaxWebhookController {
  private final MaxBotService maxBotService;

  public MaxWebhookController(MaxBotService maxBotService) {
    this.maxBotService = maxBotService;
  }

  @PostMapping("/max/webhook")
  public ResponseEntity<Map<String, Object>> webhook(@RequestBody(required = false) JsonNode payload) {
    int processed = maxBotService.handleWebhookPayload(payload);
    return ResponseEntity.ok(Map.of(
        "ok", true,
        "processed", processed
    ));
  }
}
