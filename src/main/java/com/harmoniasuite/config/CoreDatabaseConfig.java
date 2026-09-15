package com.harmoniasuite.config;

import com.harmoniasuite.db.SqliteDataSources;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Creates and migrates the canonical database independently of the legacy database. */
@Configuration
public class CoreDatabaseConfig {

    private static final String SQLITE_MIGRATIONS = "classpath:db/core/migration/sqlite";
    private static final String POSTGRES_MIGRATIONS = "classpath:db/core/migration/postgresql";
    private static final Pattern POSTGRES_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    @Bean
    @Profile("postgres")
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties canonicalDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "coreDatabase", destroyMethod = "close")
    @Profile("!postgres")
    public CoreDatabase sqliteCoreDatabase(HarmoniaProperties properties,
                                           WorkspacePaths workspace) throws Exception {
        Path databasePath = workspace.resolve(properties.getCore().getDbPath());
        DataSource dataSource = SqliteDataSources.create(databasePath);
        migrate(dataSource, SQLITE_MIGRATIONS, null);
        return new CoreDatabase(dataSource);
    }

    @Bean(name = "coreDatabase", destroyMethod = "close")
    @Profile("postgres")
    public CoreDatabase postgresCoreDatabase(DataSourceProperties dataSourceProperties,
                                             HarmoniaProperties properties) {
        String schema = validatedSchema(properties.getCore().getPostgresSchema());
        String url = withCurrentSchema(dataSourceProperties.getUrl(), schema);
        DataSource dataSource = dataSourceProperties.initializeDataSourceBuilder()
                .url(url)
                .build();
        migrate(dataSource, POSTGRES_MIGRATIONS, schema);
        return new CoreDatabase(dataSource);
    }

    private static void migrate(DataSource dataSource, String location, String schema) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations(location);
        if (schema != null) {
            configuration.schemas(schema).defaultSchema(schema);
        }
        configuration.load().migrate();
    }

    private static String withCurrentSchema(String url, String schema) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("PostgreSQL datasource URL is not configured");
        }
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + "currentSchema="
                + URLEncoder.encode(schema, StandardCharsets.UTF_8);
    }

    private static String validatedSchema(String schema) {
        if (schema == null || !POSTGRES_IDENTIFIER.matcher(schema).matches()) {
            throw new IllegalStateException("canonical PostgreSQL schema is invalid");
        }
        return schema;
    }
}
