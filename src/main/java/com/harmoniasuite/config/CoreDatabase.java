package com.harmoniasuite.config;

import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Owns the canonical database connection surface without exporting a generic datasource bean. */
public final class CoreDatabase implements AutoCloseable {

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public CoreDatabase(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    public JdbcTemplate jdbc() {
        return jdbcTemplate;
    }

    @Override
    public void close() {
        if (dataSource instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception exception) {
                throw new IllegalStateException("canonical database could not be closed", exception);
            }
        }
    }
}
