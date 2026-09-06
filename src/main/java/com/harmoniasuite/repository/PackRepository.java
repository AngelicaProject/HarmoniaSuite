package com.harmoniasuite.repository;

import com.harmoniasuite.domain.PackAuthor;
import com.harmoniasuite.domain.PackMeta;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class PackRepository {

    private static final String SELECT_PACK = "SELECT * FROM pack WHERE project_id = ?";

    private static final String SELECT_GAME_VERSION = """
            SELECT game_version FROM pack WHERE project_id = ?""";

    private static final String SELECT_AUTHORS = """
            SELECT name, role, contact FROM pack_authors WHERE project_id = ? ORDER BY name""";

    private static final String SELECT_LANGUAGES = """
            SELECT lang FROM pack_languages WHERE project_id = ? ORDER BY lang""";

    private static final String SELECT_VERSIONS = """
            SELECT version FROM pack_compatible_versions WHERE project_id = ? ORDER BY version""";

    private static final String UPDATE_PACK = """
            UPDATE pack SET
                pack_id = ?, translation_version = ?, game_version = ?,
                vendor_id = ?, vendor_name = ?, vendor_url = ?, vendor_contact = ?,
                title = ?, description = ?, changelog = ?, homepage = ?,
                license = ?, min_plugin_version = ?, updated_at = ?
            WHERE project_id = ?""";

    private static final String INSERT_PACK = """
            INSERT INTO pack (
                project_id, pack_id, translation_version, game_version, vendor_id,
                vendor_name, vendor_url, vendor_contact, title, description, changelog,
                homepage, license, min_plugin_version, created_at, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    private static final String DELETE_AUTHORS = "DELETE FROM pack_authors WHERE project_id = ?";

    private static final String INSERT_AUTHOR = """
            INSERT INTO pack_authors (project_id, name, role, contact, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)""";

    private static final String DELETE_LANGUAGES = "DELETE FROM pack_languages WHERE project_id = ?";

    private static final String INSERT_LANGUAGE = """
            INSERT INTO pack_languages (project_id, lang, created_at, updated_at)
            VALUES (?, ?, ?, ?)""";

    private static final String DELETE_VERSIONS =
            "DELETE FROM pack_compatible_versions WHERE project_id = ?";

    private static final String INSERT_VERSION = """
            INSERT INTO pack_compatible_versions (project_id, version, created_at, updated_at)
            VALUES (?, ?, ?, ?)""";

    private static final String AUTHORS_TABLE = "pack_authors";
    private static final String LANGUAGES_TABLE = "pack_languages";
    private static final String VERSIONS_TABLE = "pack_compatible_versions";
    private static final RowMapper<PackMeta> ROW_MAPPER = (rs, i) -> {
        PackMeta pack = new PackMeta();
        pack.setPackId(nullIfBlank(rs.getString("pack_id")));
        pack.setTranslationVersion(nullIfBlank(rs.getString("translation_version")));
        pack.setGameVersion(nullIfBlank(rs.getString("game_version")));
        pack.setVendorId(nullIfBlank(rs.getString("vendor_id")));
        pack.setVendorName(nullIfBlank(rs.getString("vendor_name")));
        pack.setVendorUrl(nullIfBlank(rs.getString("vendor_url")));
        pack.setVendorContact(nullIfBlank(rs.getString("vendor_contact")));
        pack.setTitle(nullIfBlank(rs.getString("title")));
        pack.setDescription(nullIfBlank(rs.getString("description")));
        pack.setChangelog(nullIfBlank(rs.getString("changelog")));
        pack.setHomepage(nullIfBlank(rs.getString("homepage")));
        pack.setLicense(nullIfBlank(rs.getString("license")));
        pack.setMinPluginVersion(nullIfBlank(rs.getString("min_plugin_version")));
        return pack;
    };

    private static final RowMapper<PackAuthor> AUTHOR_MAPPER = (rs, i) -> {
        PackAuthor author = new PackAuthor();
        author.setName(rs.getString(1));
        author.setRole(nullIfBlank(rs.getString(2)));
        author.setContact(nullIfBlank(rs.getString(3)));
        return author;
    };

    private final JdbcTemplate jdbc;

    public PackRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String gameVersion(UUID projectId) {
        List<String> rows = jdbc.query(SELECT_GAME_VERSION, (rs, i) -> rs.getString(1), projectId);
        if (rows.isEmpty() || rows.get(0) == null || rows.get(0).isBlank()) {
            return null;
        }
        return rows.get(0);
    }

    public PackMeta load(UUID projectId) {
        List<PackMeta> rows = jdbc.query(SELECT_PACK, ROW_MAPPER, projectId);
        PackMeta pack = rows.isEmpty() ? new PackMeta() : rows.get(0);
        pack.setAuthors(jdbc.query(SELECT_AUTHORS, AUTHOR_MAPPER, projectId));
        pack.setLanguages(jdbc.query(SELECT_LANGUAGES, (rs, i) -> rs.getString(1), projectId));
        pack.setCompatibleGameVersions(
                jdbc.query(SELECT_VERSIONS, (rs, i) -> rs.getString(1), projectId));
        return pack;
    }

    public void save(UUID projectId, PackMeta pack, String now) {
        int updated = jdbc.update(UPDATE_PACK,
                str(pack.getPackId()), str(pack.getTranslationVersion()), str(pack.getGameVersion()),
                str(pack.getVendorId()), str(pack.getVendorName()), str(pack.getVendorUrl()),
                str(pack.getVendorContact()), str(pack.getTitle()), str(pack.getDescription()),
                str(pack.getChangelog()), str(pack.getHomepage()), str(pack.getLicense()),
                str(pack.getMinPluginVersion()), now, projectId);
        if (updated == 0) {
            jdbc.update(INSERT_PACK,
                    projectId, str(pack.getPackId()), str(pack.getTranslationVersion()),
                    str(pack.getGameVersion()), str(pack.getVendorId()), str(pack.getVendorName()),
                    str(pack.getVendorUrl()), str(pack.getVendorContact()), str(pack.getTitle()),
                    str(pack.getDescription()), str(pack.getChangelog()), str(pack.getHomepage()),
                    str(pack.getLicense()), str(pack.getMinPluginVersion()), now, now);
        }
        saveAuthors(projectId, pack.getAuthors(), now);
        saveStrings(VERSIONS_TABLE, INSERT_VERSION, projectId, pack.getCompatibleGameVersions(), now);
        saveStrings(LANGUAGES_TABLE, INSERT_LANGUAGE, projectId, pack.getLanguages(), now);
    }

    private void saveAuthors(UUID project, List<PackAuthor> authors, String now) {
        jdbc.update(DELETE_AUTHORS, project);
        if (authors == null) {
            return;
        }
        List<Object[]> batch = new ArrayList<>();
        for (PackAuthor author : authors) {
            if (author == null) {
                continue;
            }
            batch.add(new Object[]{project, str(author.getName()), str(author.getRole()),
                    str(author.getContact()), now, now});
        }
        if (batch.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(INSERT_AUTHOR, batch);
    }

    private void saveStrings(String table, String insertSql, UUID project,
            List<String> values, String now) {
        SqlBuilder filter = SqlBuilder.deleteFrom(table).and("project_id = ?", project);
        jdbc.update(filter.text(), filter.params());
        if (values == null) {
            return;
        }
        List<Object[]> batch = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                batch.add(new Object[]{project, value.trim(), now, now});
            }
        }
        if (batch.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(insertSql, batch);
    }

    private static String str(String value) {
        return value == null ? "" : value;
    }

    private static String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
