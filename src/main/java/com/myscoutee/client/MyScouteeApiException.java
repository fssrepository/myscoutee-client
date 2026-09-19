package com.myscoutee.client;

public class MyScouteeApiException extends RuntimeException {
    private final int statusCode;
    private final String responseBody;

    public MyScouteeApiException(int statusCode, String responseBody) {
        super("MyScoutee API returned HTTP " + statusCode);
        this.statusCode = statusCode;
        this.responseBody = responseBody == null ? "" : responseBody;
    }

    public int statusCode() {
        return statusCode;
    }

    public String responseBody() {
        return responseBody;
    }
}
