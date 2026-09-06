package com.harmoniasuite.db;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import org.sqlite.Function;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

public final class SqliteDataSources {

    private SqliteDataSources() {
    }

    public static final class Uuid6Function extends Function {
        @Override
        protected void xFunc() throws SQLException {
            result(Uuid6.generate());
        }
    }

    public static DataSource create(Path dbFile) throws Exception {
        java.nio.file.Files.createDirectories(dbFile.getParent());
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        config.setBusyTimeout(30_000);
        config.enforceForeignKeys(true);
        config.setCacheSize(-262144);
        SQLiteDataSource dataSource = new SQLiteDataSource(config) {
            @Override
            public Connection getConnection() throws SQLException {
                Connection connection = super.getConnection();
                Function.create(connection, "uuid6", new Uuid6Function());
                return connection;
            }
        };
        dataSource.setUrl("jdbc:sqlite:" + dbFile);
        return dataSource;
    }

    public static PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
