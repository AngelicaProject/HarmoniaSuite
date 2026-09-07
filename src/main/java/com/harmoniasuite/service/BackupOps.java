package com.harmoniasuite.service;

import java.nio.file.Path;
import java.util.List;

public interface BackupOps {
    Path create() throws Exception;
    List<Path> list() throws Exception;
    Path resolve(String name) throws Exception;
    void delete(String name) throws Exception;
    int retention();
    void setRetention(int n);
    int autoIntervalMinutes();
    void setAutoIntervalMinutes(int minutes);
    long usedBytes() throws Exception;
    long estimatedBytes() throws Exception;
    void openFolder() throws Exception;
}
