package com.harmoniasuite.service;

import com.harmoniasuite.config.StartupLink;
import jakarta.annotation.PreDestroy;
import java.awt.GraphicsEnvironment;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.imageio.ImageIO;
import javax.swing.JDialog;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.UIManager;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
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
            UIManager.setLookAndFeel("com.sun.java.swing.plaf.windows.WindowsLookAndFeel");
            var image = ImageIO.read(getClass().getResourceAsStream("/static/img/yuki-icon.png"));
            if (image == null) {
                return;
            }
            JDialog owner = new JDialog();
            owner.setUndecorated(true);
            owner.setAlwaysOnTop(true);
            JPopupMenu menu = new JPopupMenu();
            JMenuItem open = new JMenuItem("Открыть в браузере");
            open.addActionListener(e -> StartupLink.openBrowser(link()));
            JMenuItem exit = new JMenuItem("Выйти");
            exit.addActionListener(e -> close());
            menu.add(open);
            menu.add(exit);
            menu.addPopupMenuListener(new PopupMenuListener() {
                @Override
                public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                    owner.setVisible(false);
                }

                @Override
                public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                }

                @Override
                public void popupMenuCanceled(PopupMenuEvent e) {
                    owner.setVisible(false);
                }
            });
            icon = new TrayIcon(image, "Harmonia Suite");
            icon.setImageAutoSize(true);
            icon.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getButton() == MouseEvent.BUTTON1) {
                        StartupLink.openBrowser(link());
                    }
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    if (e.isPopupTrigger()) {
                        showMenu(e);
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (e.isPopupTrigger()) {
                        showMenu(e);
                    }
                }

                private void showMenu(MouseEvent e) {
                    owner.setLocation(e.getXOnScreen(), e.getYOnScreen() - menu.getPreferredSize().height);
                    owner.setVisible(true);
                    menu.show(owner, 0, 0);
                }
            });
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
