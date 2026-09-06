package com.harmoniasuite.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class HttpClientConfig {

    private final HarmoniaProperties properties;

    public HttpClientConfig(HarmoniaProperties properties) {
        this.properties = properties;
    }

    @Bean
    public RestClient.Builder restClientBuilder() {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout((int) properties.getHttp().getConnectTimeoutMs());
        rf.setReadTimeout((int) properties.getHttp().getReadTimeoutMs());
        return RestClient.builder().requestFactory(rf);
    }
}
