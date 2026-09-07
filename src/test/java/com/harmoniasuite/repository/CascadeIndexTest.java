package com.harmoniasuite.repository;

import com.harmoniasuite.TestDatabases;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CascadeIndexTest {

    @TempDir
    java.nio.file.Path dbDir;

    @Test
    @DisplayName("cascade delete keys are covered by indexes")
    void cascadeKeysAreIndexed() {
        JdbcTemplate jdbc = TestDatabases.sqlite(dbDir);
        assertEquals(List.of("file_id"), jdbc.query(
                "PRAGMA index_info(idx_entries_file_id)",
                (rs, i) -> rs.getString("name")));
        assertEquals(List.of("entry_id"), jdbc.query(
                "PRAGMA index_info(idx_entry_history_entry_id)",
                (rs, i) -> rs.getString("name")));
    }
}
