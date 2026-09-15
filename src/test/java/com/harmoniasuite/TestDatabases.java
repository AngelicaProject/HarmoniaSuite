package com.harmoniasuite;

import com.harmoniasuite.db.SqliteDataSources;
import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;

public final class TestDatabases {

    private TestDatabases() {
    }

    public static JdbcTemplate coreSqlite(Path dir) {
        try {
            javax.sql.DataSource dataSource =
                    SqliteDataSources.create(dir.resolve("canonical-test.db").toAbsolutePath());
            Flyway.configure().dataSource(dataSource)
                    .locations("classpath:db/core/migration/sqlite")
                    .load().migrate();
            return new JdbcTemplate(dataSource);
        } catch (Exception e) {
            throw new IllegalStateException("canonical test database setup failed", e);
        }
    }

    public static JdbcTemplate canonicalSqlite(Path dir) {
        return coreSqlite(dir);
    }
}
