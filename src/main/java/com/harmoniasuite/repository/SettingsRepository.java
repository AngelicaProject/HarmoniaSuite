package com.harmoniasuite.repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SettingsRepository {

    private static final String SELECT_VALUE = """
            SELECT value FROM app_settings WHERE key = ?""";

    private static final String UPSERT = """
            INSERT INTO app_settings (key, value) VALUES (?, ?)
            ON CONFLICT (key) DO UPDATE SET value = excluded.value""";

    private static final String SELECT_ALL = """
            SELECT key, value FROM app_settings""";

    private final JdbcTemplate jdbc;

    public SettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String get(String key) {
        List<String> values = jdbc.queryForList(SELECT_VALUE, String.class, key);
        return values.isEmpty() ? null : values.get(0);
    }

    public void set(String key, String value) {
        jdbc.update(UPSERT, key, value == null ? "" : value);
    }

    public Map<String, String> all() {
        Map<String, String> map = new LinkedHashMap<>();
        jdbc.query(SELECT_ALL, rs -> {
            map.put(rs.getString(1), rs.getString(2));
        });
        return map;
    }
}
