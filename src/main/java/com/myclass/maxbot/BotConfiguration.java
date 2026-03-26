package com.myclass.maxbot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BotConfiguration {
  @Bean
  public MaxApiClient maxApiClient(BotProperties properties, ObjectMapper objectMapper) {
    return new MaxApiClient(properties.getMax().getBaseUrl(), properties.getMax().getToken(), objectMapper);
  }

  @Bean
  public KeyboardFactory keyboardFactory() {
    return new KeyboardFactory();
  }
}
