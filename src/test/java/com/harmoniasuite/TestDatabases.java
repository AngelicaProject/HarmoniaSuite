package com.harmoniasuite;

import com.harmoniasuite.db.SqliteDataSources;
import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;

public final class TestDatabases {

    private TestDatabases() {
    }

    public static JdbcTemplate sqlite(Path dir) {
        try {
            javax.sql.DataSource dataSource =
                    SqliteDataSources.create(dir.resolve("test.db").toAbsolutePath());
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration/sqlite")
                    .load().migrate();
            return new JdbcTemplate(dataSource);
        } catch (Exception e) {
            throw new IllegalStateException("test database setup failed", e);
        }
    }
}
