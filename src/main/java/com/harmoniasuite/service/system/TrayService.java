package com.harmoniasuite.service.system;

import com.harmoniasuite.config.StartupLink;
import com.harmoniasuite.util.IcoSupport;
import jakarta.annotation.PreDestroy;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
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
            var image = loadTrayImage();
            if (image == null) {
                return;
            }
            PopupMenu menu = new PopupMenu();
            MenuItem open = new MenuItem("Открыть в браузере");
            open.addActionListener(e -> StartupLink.openBrowser(link()));
            MenuItem exit = new MenuItem("Выйти");
            exit.addActionListener(e -> close());
            menu.add(open);
            menu.addSeparator();
            menu.add(exit);
            icon = new TrayIcon(image, "Harmonia Suite");
            icon.setImageAutoSize(false);
            icon.setPopupMenu(menu);
            icon.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getButton() == MouseEvent.BUTTON1) {
                        StartupLink.openBrowser(link());
                    }
                }
            });
            SystemTray.getSystemTray().add(icon);
        } catch (Exception e) {
            log.warn("Трей недоступен: {}", e.getMessage());
        }
    }

    private Image loadTrayImage() throws Exception {
        var size = SystemTray.getSystemTray().getTrayIconSize();
        try (var in = getClass().getResourceAsStream("/tray/yuki-tray-icon.ico")) {
            if (in != null) {
                try {
                    return IcoSupport.readBest(in.readAllBytes(), size.width, size.height);
                } catch (Exception e) {
                    log.warn("ICO трея не прочиталась, использую PNG: {}", e.getMessage());
                }
            }
        }
        var fallback = ImageIO.read(getClass().getResourceAsStream("/static/img/yuki-icon.png"));
        return fallback == null ? null : scaleIcon(fallback);
    }

    private String link() {
        String port = environment.getProperty("local.server.port");
        return "http://127.0.0.1:" + (port == null || port.isBlank() ? "8765" : port);
    }

    private Image scaleIcon(BufferedImage source) {
        var size = SystemTray.getSystemTray().getTrayIconSize();
        int width = Math.max(16, size.width);
        int height = Math.max(16, size.height);
        if (source.getWidth() == width && source.getHeight() == height) {
            return source;
        }
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(source, 0, 0, width, height, null);
        g.dispose();
        return scaled;
    }

    private void close() {
        try {
            ((ConfigurableApplicationContext) context).close();
        } finally {
            Runtime.getRuntime().halt(0);
        }
    }

    @PreDestroy
    public void remove() {
        try {
            if (icon != null) {
                TrayIcon gone = icon;
                icon = null;
                java.awt.EventQueue.invokeLater(() -> {
                    try {
                        SystemTray.getSystemTray().remove(gone);
                    } catch (Exception ignored) {
                    }
                });
            }
        } catch (Exception ignored) {
        }
    }
}
