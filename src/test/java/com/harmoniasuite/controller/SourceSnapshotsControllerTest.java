package com.harmoniasuite.controller;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harmoniasuite.config.JacksonConfig;
import com.harmoniasuite.dto.SourceSnapshotPreflightRequest;
import com.harmoniasuite.service.source.SourceSnapshotService;
import com.harmoniasuite.source.store.SourceSheet;
import com.harmoniasuite.source.store.SourceSnapshot;
import com.harmoniasuite.source.store.SourceSnapshotStore;
import com.harmoniasuite.source.store.SourceStringCell;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class SourceSnapshotsControllerTest {

    private static final String SNAPSHOT_ID = "sha256:" + "a".repeat(64);
    private static final String CONTENT_ID = "sha256:" + "b".repeat(64);

    private Registry registry;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        registry = new Registry();
        ObjectMapper mapper = new JacksonConfig().objectMapper();
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new SourceSnapshotsController(new SourceSnapshotService(registry)))
                .setControllerAdvice(new com.harmoniasuite.exception.GlobalExceptionHandler())
                .setValidator(validator)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    @Test
    void missingPreflightReturnsUploadRequired() throws Exception {
        mockMvc.perform(post("/api/source-snapshots/preflight")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UPLOAD_REQUIRED")))
                .andExpect(jsonPath("$.snapshot_id", is(SNAPSHOT_ID)))
                .andExpect(jsonPath("$.snapshot").doesNotExist());
    }

    @Test
    void invalidPreflightReturnsBadRequest() throws Exception {
        SourceSnapshotPreflightRequest invalid = new SourceSnapshotPreflightRequest(
                1, "7.2.0", "en", "full", "sha256:bad", CONTENT_ID,
                "extractor-test", "lumina-test", 2L, 3L, 3L);

        mockMvc.perform(post("/api/source-snapshots/preflight")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(invalid)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void conflictingPreflightReturnsConflict() throws Exception {
        registry.snapshots.add(snapshot());
        SourceSnapshotPreflightRequest conflicting = new SourceSnapshotPreflightRequest(
                1, "different-game", "en", "full", SNAPSHOT_ID, CONTENT_ID,
                "extractor-test", "lumina-test", 2L, 3L, 3L);

        mockMvc.perform(post("/api/source-snapshots/preflight")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(conflicting)))
                .andExpect(status().isConflict());
    }

    @Test
    void missingSnapshotDetailReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/source-snapshots/{snapshotId}", SNAPSHOT_ID))
                .andExpect(status().isNotFound());
    }

    private static String json(Object value) throws Exception {
        return new JacksonConfig().objectMapper().writeValueAsString(value);
    }

    private static SourceSnapshotPreflightRequest request() {
        return new SourceSnapshotPreflightRequest(
                1, "7.2.0", "en", "full", SNAPSHOT_ID, CONTENT_ID,
                "extractor-test", "lumina-test", 2L, 3L, 3L);
    }

    private static SourceSnapshot snapshot() {
        return new SourceSnapshot(7, SNAPSHOT_ID, CONTENT_ID, 1, "7.2.0", "en", "full",
                "extractor-test", "lumina-test", 2, 3, 3);
    }

    private static final class Registry implements SourceSnapshotStore {
        private final List<SourceSnapshot> snapshots = new ArrayList<>();

        @Override
        public List<SourceSnapshot> listSnapshots() {
            return List.copyOf(snapshots);
        }

        @Override
        public Optional<SourceSnapshot> findBySnapshotId(String snapshotId) {
            return snapshots.stream().filter(value -> value.snapshotId().equals(snapshotId))
                    .findFirst();
        }

        @Override
        public List<SourceSheet> findSheets(String snapshotId) {
            return List.of();
        }

        @Override
        public Optional<SourceSheet> findSheet(String snapshotId, String sheetName) {
            return Optional.empty();
        }

        @Override
        public Optional<SourceStringCell> findStringCell(String snapshotId, String sheetName,
                                                          long rowId, int subrowId,
                                                          int columnIndex) {
            return Optional.empty();
        }

        @Override
        public long countRows(String snapshotId) {
            return 0;
        }

        @Override
        public long countStringCells(String snapshotId) {
            return 0;
        }
    }
}
