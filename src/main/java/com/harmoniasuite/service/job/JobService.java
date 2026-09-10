package com.harmoniasuite.service.job;

import com.harmoniasuite.config.WorkspacePaths;
import com.harmoniasuite.domain.JobState;
import com.harmoniasuite.dto.JobDto;
import com.harmoniasuite.dto.RunRequest;
import com.harmoniasuite.exception.HarmoniaSuiteBadRequestException;
import com.harmoniasuite.exception.UpdateException;
import com.harmoniasuite.repository.JobRepository;
import com.harmoniasuite.repository.ProjectRepository;
import com.harmoniasuite.service.source.SourceService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class JobService {

    private static final Logger logger = LoggerFactory.getLogger(JobService.class);

    private final JobRepository jobs;
    private final ProjectRepository projectRepository;
    private final Executor jobExecutor;
    private final WorkspacePaths workspace;
    private final List<JobHandler> handlers;
    private final SourceService sources;
    private final ConcurrentHashMap<String, Future<?>> running = new ConcurrentHashMap<>();

    public JobService(JobRepository jobs, ProjectRepository projectRepository,
            @Qualifier("jobExecutor") Executor jobExecutor,
            WorkspacePaths workspace,
            List<JobHandler> handlers,
            SourceService sources
    ) {
        this.jobs = jobs;
        this.projectRepository = projectRepository;
        this.jobExecutor = jobExecutor;
        this.workspace = workspace;
        this.handlers = handlers;
        this.sources = sources;
    }

    public JobDto start(RunRequest request) {
        if (request.action() == null || request.action().isBlank()) {
            throw new HarmoniaSuiteBadRequestException("unknown action");
        }
        JobHandler handler = handlers.stream()
                .filter(h -> h.action().equals(request.action()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown action"));
        if ("sync-sources".equals(request.action()) || "update".equals(request.action())) {
            return submit(handler, request, new JobPaths(null, null, workspace.root(), null));
        }
        if (request.projectId() == null) {
            throw new HarmoniaSuiteBadRequestException("projectId is required");
        }
        ProjectRepository.ProjectRow project = projectRepository.findById(request.projectId());
        Path projectDir = Path.of(project.projectDir());
        Path root = request.root() == null || request.root().isBlank()
                ? sources.activeRoot()
                : workspace.resolve(request.root());
        Path output = request.output() == null
                ? Path.of(project.outputDir())
                : workspace.resolve(request.output());
        JobPaths paths = new JobPaths(request.projectId(), projectDir, root, output);
        return submit(handler, request, paths);
    }

    private JobDto submit(JobHandler handler, RunRequest request, JobPaths paths) {
        String jobId = UUID.randomUUID().toString().replace("-", "");
        JobState job = new JobState(jobId, request.action());
        jobs.put(job);
        FutureTask<Void> task = new FutureTask<>(() -> {
            run(job, handler, request, paths);
            return null;
        });
        running.put(jobId, task);
        try {
            jobExecutor.execute(task);
        } catch (RuntimeException e) {
            running.remove(jobId);
            job.setCode(1);
            job.setStatus("failed");
            job.append(e.getMessage() == null ? e.toString() : e.getMessage());
            throw e;
        }
        return toDto(job);
    }

    public JobDto status(String id) {
        JobState job = jobs.get(id);
        if (job == null) {
            return new JobDto(id, null, "unknown", null, null);
        }
        return toDto(job);
    }

    public JobDto cancel(String id) {
        JobState job = jobs.get(id);
        if (job == null) {
            return new JobDto(id, null, "unknown", null, null);
        }
        if ("running".equals(job.getStatus()) || "queued".equals(job.getStatus())) {
            job.setStatus("cancelled");
            job.append("Отмена запрошена…");
            Future<?> future = running.get(id);
            if (future != null) {
                future.cancel(true);
                running.remove(id);
            }
        }
        return toDto(job);
    }

    private static JobDto toDto(JobState job) {
        String output = job.getOutput();
        return new JobDto(job.getId(), job.getAction(), job.getStatus(),
                output.isEmpty() ? null : output, job.getCode());
    }

    private void run(JobState job, JobHandler handler, RunRequest request, JobPaths paths) {
        job.setStatus("running");
        Consumer<String> log = job::append;
        try {
            handler.execute(request, paths, log);
            if (!"cancelled".equals(job.getStatus())) {
                job.setCode(0);
                job.setStatus("completed");
            }
        } catch (Exception e) {
            if ("cancelled".equals(job.getStatus())) {
                return;
            }
            if (e instanceof UpdateException updateFailure) {
                logger.error("job {} ({}) failed [{}]: {}", job.getId(), job.getAction(),
                        updateFailure.code(), updateFailure.diagnosticMessage());
                job.append(updateFailure.getMessage());
            } else {
                logger.error("job {} ({}) failed", job.getId(), job.getAction(), e);
                job.append(e.getMessage() == null ? e.toString() : e.getMessage());
            }
            job.setCode(1);
            job.setStatus("failed");
        } finally {
            running.remove(job.getId());
        }
    }
}
