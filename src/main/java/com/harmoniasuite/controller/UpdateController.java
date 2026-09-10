package com.harmoniasuite.controller;

import com.harmoniasuite.dto.JobDto;
import com.harmoniasuite.dto.RunRequest;
import com.harmoniasuite.dto.UpdateStatusDto;
import com.harmoniasuite.service.job.JobService;
import com.harmoniasuite.service.update.UpdateService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UpdateController {

    private final UpdateService updateService;
    private final JobService jobService;

    public UpdateController(UpdateService updateService, JobService jobService) {
        this.updateService = updateService;
        this.jobService = jobService;
    }

    @GetMapping("/api/update/status")
    public UpdateStatusDto status() {
        return updateService.status();
    }

    @PostMapping("/api/update")
    @ResponseStatus(HttpStatus.CREATED)
    public JobDto run() {
        return jobService.start(new RunRequest("update", null, null, null, null, null, null, null));
    }
}
