package com.harmoniasuite.service;

import com.harmoniasuite.config.StartupLink;
import jakarta.annotation.PreDestroy;
import java.awt.GraphicsEnvironment;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class TrayService implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(TrayService.class);

    private final ApplicationContext context;
    private final Environment environment;

    private TrayIcon icon;

    public TrayService(ApplicationContext context, Environment environment) {
        this.context = context;
        this.environment = environment;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (System.getProperty("jpackage.app-path") == null
                || GraphicsEnvironment.isHeadless()
                || !SystemTray.isSupported()) {
            return;
        }
        try {
            var image = ImageIO.read(getClass().getResourceAsStream("/static/img/yuki-icon.png"));
            if (image == null) {
                return;
            }
            PopupMenu menu = new PopupMenu();
            MenuItem open = new MenuItem("Открыть в браузере");
            open.addActionListener(e -> StartupLink.openBrowser(link()));
            MenuItem exit = new MenuItem("Выйти");
            exit.addActionListener(e -> close());
            menu.add(open);
            menu.add(exit);
            icon = new TrayIcon(image, "Harmonia Suite", menu);
            icon.setImageAutoSize(true);
            SystemTray.getSystemTray().add(icon);
        } catch (Exception e) {
            log.warn("Трей недоступен: {}", e.getMessage());
        }
    }

    private String link() {
        String port = environment.getProperty("local.server.port");
        return "http://127.0.0.1:" + (port == null || port.isBlank() ? "8765" : port);
    }

    private void close() {
        try {
            ((ConfigurableApplicationContext) context).close();
        } finally {
            System.exit(0);
        }
    }

    @PreDestroy
    public void remove() {
        try {
            if (icon != null) {
                SystemTray.getSystemTray().remove(icon);
            }
        } catch (Exception ignored) {
        }
    }
}
