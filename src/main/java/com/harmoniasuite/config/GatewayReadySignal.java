package com.harmoniasuite.config;

import java.util.regex.Pattern;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Emits the bound ephemeral port only when Electron supplied an instance handshake token. */
@Component
public class GatewayReadySignal implements ApplicationListener<WebServerInitializedEvent> {

    private static final Pattern SAFE_INSTANCE = Pattern.compile("[A-Za-z0-9_-]{16,128}");
    private final Environment environment;

    public GatewayReadySignal(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        String instance = environment.getProperty("harmonia.gateway-instance");
        int port = event.getWebServer().getPort();
        if (instance == null || !SAFE_INSTANCE.matcher(instance).matches() || port <= 0) {
            return;
        }
        System.out.println("HARMONIA_GATEWAY_READY instance=" + instance + " port=" + port);
        System.out.flush();
    }
}
