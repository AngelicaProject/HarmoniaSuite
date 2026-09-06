package com.harmoniasuite.config;

import com.harmoniasuite.db.SqliteDataSources;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@Profile("!postgres")
public class DataSourceConfig {

    @Bean
    public DataSource dataSource(HarmoniaProperties properties, WorkspacePaths workspace) throws Exception {
        Path db = workspace.resolve(properties.getDbPath());
        return SqliteDataSources.create(db);
    }

    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return SqliteDataSources.transactionManager(dataSource);
    }
}
