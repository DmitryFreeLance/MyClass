package com.myclass.maxbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(BotProperties.class)
public class MaxBotApplication {
  public static void main(String[] args) {
    SpringApplication.run(MaxBotApplication.class, args);
  }
}
