package com.harmoniasuite.db;

import java.nio.file.Path;
import javax.sql.DataSource;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

public final class SqliteDataSources {

    private SqliteDataSources() {
    }

    public static DataSource create(Path dbFile) throws Exception {
        java.nio.file.Files.createDirectories(dbFile.getParent());
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        config.setBusyTimeout(30_000);
        config.enforceForeignKeys(true);
        config.setCacheSize(-262144);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + dbFile);
        return dataSource;
    }

}
