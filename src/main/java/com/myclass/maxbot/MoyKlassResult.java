package com.myclass.maxbot;

public class MoyKlassResult {
  private final boolean success;
  private final String message;
  private final String data;

  private MoyKlassResult(boolean success, String message, String data) {
    this.success = success;
    this.message = message;
    this.data = data;
  }

  public static MoyKlassResult success(String message, String data) {
    return new MoyKlassResult(true, message, data);
  }

  public static MoyKlassResult failure(String message) {
    return new MoyKlassResult(false, message, null);
  }

  public boolean isSuccess() {
    return success;
  }

  public String getMessage() {
    return message;
  }

  public String getData() {
    return data;
  }
}
