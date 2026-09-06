package com.harmoniasuite.repository;

import com.harmoniasuite.domain.JobState;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class JobRepository {

    private final ConcurrentHashMap<String, JobState> jobs = new ConcurrentHashMap<>();

    public void put(JobState job) {
        jobs.put(job.getId(), job);
    }

    public JobState get(String id) {
        return jobs.get(id);
    }

    public void evictOlderThan(long millis) {
        Instant cutoff = Instant.now().minusMillis(millis);
        jobs.entrySet().removeIf(e -> e.getValue().getCreatedAt().isBefore(cutoff)
                && !"running".equals(e.getValue().getStatus()));
    }
}
