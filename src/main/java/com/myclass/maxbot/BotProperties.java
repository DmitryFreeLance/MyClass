package com.myclass.maxbot;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bot")
public class BotProperties {
  private Max max = new Max();
  private Admin admin = new Admin();
  private Moyklass moyklass = new Moyklass();

  public Max getMax() {
    return max;
  }

  public void setMax(Max max) {
    this.max = max;
  }

  public Admin getAdmin() {
    return admin;
  }

  public void setAdmin(Admin admin) {
    this.admin = admin;
  }

  public Moyklass getMoyklass() {
    return moyklass;
  }

  public void setMoyklass(Moyklass moyklass) {
    this.moyklass = moyklass;
  }

  public static class Max {
    private String baseUrl;
    private String token;
    private long adminUserId;
    private int longPollTimeoutSec;
    private int longPollLimit;

    public String getBaseUrl() {
      return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
    }

    public String getToken() {
      return token;
    }

    public void setToken(String token) {
      this.token = token;
    }

    public long getAdminUserId() {
      return adminUserId;
    }

    public void setAdminUserId(long adminUserId) {
      this.adminUserId = adminUserId;
    }

    public int getLongPollTimeoutSec() {
      return longPollTimeoutSec;
    }

    public void setLongPollTimeoutSec(int longPollTimeoutSec) {
      this.longPollTimeoutSec = longPollTimeoutSec;
    }

    public int getLongPollLimit() {
      return longPollLimit;
    }

    public void setLongPollLimit(int longPollLimit) {
      this.longPollLimit = longPollLimit;
    }
  }

  public static class Admin {
    private String panelToken;
    private String panelUrl;

    public String getPanelToken() {
      return panelToken;
    }

    public void setPanelToken(String panelToken) {
      this.panelToken = panelToken;
    }

    public String getPanelUrl() {
      return panelUrl;
    }

    public void setPanelUrl(String panelUrl) {
      this.panelUrl = panelUrl;
    }
  }

  public static class Moyklass {
    private boolean enabled;
    private String baseUrl;
    private String token;
    private int timeoutSec;
    private Long leadStateId;
    private String maxIdAttributeAlias;
    private String payLinkBase;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getBaseUrl() {
      return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
    }

    public String getToken() {
      return token;
    }

    public void setToken(String token) {
      this.token = token;
    }

    public int getTimeoutSec() {
      return timeoutSec;
    }

    public void setTimeoutSec(int timeoutSec) {
      this.timeoutSec = timeoutSec;
    }

    public Long getLeadStateId() {
      return leadStateId;
    }

    public void setLeadStateId(Long leadStateId) {
      this.leadStateId = leadStateId;
    }

    public String getMaxIdAttributeAlias() {
      return maxIdAttributeAlias;
    }

    public void setMaxIdAttributeAlias(String maxIdAttributeAlias) {
      this.maxIdAttributeAlias = maxIdAttributeAlias;
    }

    public String getPayLinkBase() {
      return payLinkBase;
    }

    public void setPayLinkBase(String payLinkBase) {
      this.payLinkBase = payLinkBase;
    }
  }
}
