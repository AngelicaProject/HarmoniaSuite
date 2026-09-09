package com.harmoniasuite;

import com.harmoniasuite.config.HarmoniaProperties;
import com.harmoniasuite.config.WorkspacePaths;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootApplication
@EnableConfigurationProperties(HarmoniaProperties.class)
public class HarmoniaSuiteApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(HarmoniaSuiteApplication.class);
        application.addListeners(new LoggingFileListener());
        application.run(args);
    }

    static void configureLogging(ConfigurableEnvironment environment) {
        String configuredLog = environment.getProperty("logging.file.name");
        if (configuredLog != null && !configuredLog.isBlank()) {
            return;
        }
        Path logFile = WorkspacePaths.resolveRoot(WorkspacePaths.configuredWorkspace(environment))
                .resolve("logs").resolve("harmonia.log");
        try {
            Files.createDirectories(logFile.getParent());
        } catch (Exception ignored) {
        }
        System.setProperty("logging.file.name", logFile.toString());
    }

    private static final class LoggingFileListener
            implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }

        @Override
        public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
            configureLogging(event.getEnvironment());
        }
    }
}
