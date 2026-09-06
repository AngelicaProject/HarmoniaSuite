package com.harmoniasuite.controller;

import com.harmoniasuite.dto.JobDto;
import com.harmoniasuite.dto.RunRequest;
import com.harmoniasuite.service.JobService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/jobs")
public class JobsController {

    private final JobService jobService;

    public JobsController(JobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public JobDto start(@RequestBody RunRequest body) {
        return jobService.start(body);
    }

    @GetMapping("/{id}")
    public JobDto job(@PathVariable String id) {
        return jobService.status(id);
    }

    @DeleteMapping("/{id}")
    public JobDto cancel(@PathVariable String id) {
        return jobService.cancel(id);
    }
}
