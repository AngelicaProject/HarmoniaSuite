package com.harmoniasuite.service.job;

import com.harmoniasuite.repository.JobRepository;
import com.harmoniasuite.domain.JobState;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class JobCleanupScheduler {
    private final JobRepository registry;
    public JobCleanupScheduler(JobRepository registry){ this.registry=registry; }
    @Scheduled(fixedDelay = 3600000)
    public void evictOld(){ registry.evictOlderThan(24*3600*1000L); }
}
