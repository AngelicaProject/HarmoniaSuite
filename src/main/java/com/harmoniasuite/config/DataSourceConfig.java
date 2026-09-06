package com.harmoniasuite.config;

import com.harmoniasuite.db.SqliteDataSources;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@Profile("!postgres")
public class DataSourceConfig {

    @Bean
    public DataSource dataSource(HarmoniaProperties properties) throws Exception {
        Path workspace = Paths.get(properties.getWorkspace()).toAbsolutePath().normalize();
        Path db = Paths.get(properties.getDbPath());
        if (!db.isAbsolute()) {
            db = workspace.resolve(db).normalize();
        }
        return SqliteDataSources.create(db);
    }

    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return SqliteDataSources.transactionManager(dataSource);
    }
}
