package com.myclass.maxbot;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MoyKlassConfiguration {
  @Bean
  @ConditionalOnProperty(name = "bot.moyklass.enabled", havingValue = "true")
  public MoyKlassClient moyKlassHttpClient(BotProperties properties, UserRepository userRepository) {
    return new MoyKlassHttpClient(properties.getMoyklass(), userRepository);
  }

  @Bean
  @ConditionalOnMissingBean(MoyKlassClient.class)
  public MoyKlassClient moyKlassDisabledClient() {
    return new MoyKlassDisabledClient();
  }
}
