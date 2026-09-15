package com.harmoniasuite.source.infrastructure.scheduling;

import com.harmoniasuite.source.application.SourceIngestionMaintenanceService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public final class SourceIngestionScheduler {

    private final SourceIngestionMaintenanceService maintenance;

    public SourceIngestionScheduler(SourceIngestionMaintenanceService maintenance) {
        this.maintenance = maintenance;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        maintenance.recoverPersistedProcessing();
        maintenance.cleanupExpired();
    }

    @Scheduled(fixedDelay = 3_600_000L)
    public void scheduledMaintenance() {
        maintenance.recoverQueued();
        maintenance.cleanupExpired();
    }
}
