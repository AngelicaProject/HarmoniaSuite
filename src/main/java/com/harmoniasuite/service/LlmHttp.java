package com.harmoniasuite.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

final class LlmHttp {

    private static final Pattern RETRY_DELAY = Pattern.compile("retryDelay'?:?\\s*['\"]?(\\d+(?:\\.\\d+)?)s?");
    private static final Pattern RETRY_IN = Pattern.compile("retry in (\\d+(?:\\.\\d+)?)s", Pattern.CASE_INSENSITIVE);

    private LlmHttp() {
    }

    static String postForText(RestClient restClient, String url, Map<String, String> headers, ObjectNode body) {
        return restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> headers.forEach(h::set))
                .body(body)
                .exchange((req, response) -> {
                    byte[] raw;
                    try (InputStream in = response.getBody()) {
                        raw = in.readAllBytes();
                    }
                    String text = new String(raw, StandardCharsets.UTF_8);
                    if (response.getStatusCode().isError()) {
                        throw new IllegalStateException(response.getStatusCode().value()
                                + " " + response.getStatusCode() + ": " + text);
                    }
                    return text;
                });
    }

    static String getForText(RestClient restClient, String url, Map<String, String> headers) {
        return restClient.get()
                .uri(url)
                .headers(h -> headers.forEach(h::set))
                .exchange((req, response) -> {
                    byte[] raw;
                    try (InputStream in = response.getBody()) {
                        raw = in.readAllBytes();
                    }
                    String text = new String(raw, StandardCharsets.UTF_8);
                    if (response.getStatusCode().isError()) {
                        throw new IllegalStateException(response.getStatusCode().value()
                                + " " + response.getStatusCode() + ": " + text);
                    }
                    return text;
                });
    }

    static double waitSeconds(String error, int attempt, double baseWaitSeconds) {
        double wait = baseWaitSeconds * (attempt + 1);
        if (error != null) {
            Matcher m1 = RETRY_DELAY.matcher(error);
            Matcher m2 = RETRY_IN.matcher(error);
            if (m1.find()) {
                wait = Double.parseDouble(m1.group(1)) + 1.0;
            } else if (m2.find()) {
                wait = Double.parseDouble(m2.group(1)) + 1.0;
            }
        }
        return wait;
    }
}
