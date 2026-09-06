package com.harmoniasuite.repository;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlBuilderTest {

    @Test
    @DisplayName("первое условие идёт после WHERE, остальные через AND")
    void whereThenAnd() {
        SqlBuilder builder = SqlBuilder.where("project_id = ?", "p1")
                .and("status = ?", "untranslated");
        assertEquals(" WHERE project_id = ? AND status = ?", builder.text());
        assertEquals(2, builder.params().length);
    }

    @Test
    @DisplayName("пустой IN превращается в ложное условие")
    void emptyInIsFalse() {
        SqlBuilder builder = SqlBuilder.where("project_id = ?", "p1")
                .andIn("file_path", List.of());
        assertEquals(" WHERE project_id = ? AND 1 = 0", builder.text());
    }

    @Test
    @DisplayName("IN раскрывается в плейсхолдеры по числу значений")
    void inExpandsPlaceholders() {
        SqlBuilder builder = SqlBuilder.where("project_id = ?", "p1")
                .andIn("file_path", List.of("a.csv", "b.csv"))
                .orderBy("file_path")
                .limitOffset(10, 20);
        assertEquals(" WHERE project_id = ? AND file_path IN (?, ?) ORDER BY file_path LIMIT ? OFFSET ?",
                builder.text());
        assertEquals(5, builder.params().length);
    }
}
