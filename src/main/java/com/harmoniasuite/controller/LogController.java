package com.harmoniasuite.controller;

import com.harmoniasuite.service.system.LogService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LogController {

    private final LogService logs;

    public LogController(LogService logs) {
        this.logs = logs;
    }

    @GetMapping(value = "/api/log", produces = MediaType.TEXT_PLAIN_VALUE)
    public String tail(@RequestParam(defaultValue = "500") int tail) throws Exception {
        return logs.readTail(tail);
    }
}
