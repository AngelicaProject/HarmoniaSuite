package com.harmoniasuite.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class StartupLink implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(StartupLink.class);

    private final Environment environment;

    public StartupLink(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        String link = url(environment.getProperty("local.server.port"));
        log.info("Откройте в браузере: {}", link);
        if (System.getProperty("jpackage.app-path") != null) {
            openBrowser(link);
        }
    }

    public static void openBrowser(String link) {
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().browse(new java.net.URI(link));
            }
        } catch (Exception e) {
            log.warn("Не открыт браузер: {}", e.getMessage());
        }
    }

    static String url(String port) {
        return "http://127.0.0.1:" + (port == null || port.isBlank() ? "8765" : port);
    }
}
