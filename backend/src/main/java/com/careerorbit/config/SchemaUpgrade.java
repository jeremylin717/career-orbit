package com.careerorbit.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/**
 * 轻量的幂等数据库升级桥：为早期原型已建好的旧库补齐新字段。
 * 全新库由 schema.sql 直接建好，这里只是逐个检查“列是否存在”，缺了才 ALTER。
 */
@Component
public class SchemaUpgrade implements ApplicationRunner {

    /** 数据源（用于读取列元数据）。 */
    private final DataSource dataSource;

    /** JDBC 工具（用于执行 ALTER 语句）。 */
    private final JdbcTemplate jdbc;

    public SchemaUpgrade(DataSource dataSource, JdbcTemplate jdbc) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        add("interview_session", "current_question_text", "ALTER TABLE interview_session ADD COLUMN current_question_text TEXT NULL");
        add("interview_session", "question_type", "ALTER TABLE interview_session ADD COLUMN question_type VARCHAR(24) NOT NULL DEFAULT 'BASE'");
        add("interview_session", "parent_answer_id", "ALTER TABLE interview_session ADD COLUMN parent_answer_id BIGINT NULL");
        add("interview_answer", "question_type", "ALTER TABLE interview_answer ADD COLUMN question_type VARCHAR(24) NOT NULL DEFAULT 'BASE'");
        add("interview_answer", "parent_answer_id", "ALTER TABLE interview_answer ADD COLUMN parent_answer_id BIGINT NULL");
        add("resume", "source_diagnosis_id", "ALTER TABLE resume ADD COLUMN source_diagnosis_id BIGINT NULL");
        add("resume", "source_suggestion_id", "ALTER TABLE resume ADD COLUMN source_suggestion_id VARCHAR(128) NULL");
        add("knowledge_document", "object_key", "ALTER TABLE knowledge_document ADD COLUMN object_key VARCHAR(500) NULL");
        add("knowledge_document", "error_reason", "ALTER TABLE knowledge_document ADD COLUMN error_reason VARCHAR(1000) NULL");
    }

    private void add(String table, String column, String statement) throws SQLException {
        if (!hasColumn(table, column)) jdbc.execute(statement);
    }

    /** 通过 JDBC 元数据判断列是否存在（大小写不敏感，兼容不同数据库）。 */
    private boolean hasColumn(String table, String column) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            DatabaseMetaData meta = c.getMetaData();
            String catalog = c.getCatalog();
            for (String t : List.of(table, table.toUpperCase(Locale.ROOT))) {
                try (ResultSet rs = meta.getColumns(catalog, null, t, column)) {
                    if (rs.next()) return true;
                }
                try (ResultSet rs = meta.getColumns(catalog, null, t, column.toUpperCase(Locale.ROOT))) {
                    if (rs.next()) return true;
                }
            }
            return false;
        }
    }
}
