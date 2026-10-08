-- 仅用于从旧版 career-orbit 升级；新库由 schema.sql 自动创建。
ALTER TABLE interview_session ADD COLUMN current_question_text TEXT NULL AFTER current_question;
ALTER TABLE interview_session ADD COLUMN question_type VARCHAR(24) NOT NULL DEFAULT 'BASE' AFTER current_question_text;
ALTER TABLE interview_session ADD COLUMN parent_answer_id BIGINT NULL AFTER question_type;
ALTER TABLE interview_answer ADD COLUMN question_type VARCHAR(24) NOT NULL DEFAULT 'BASE' AFTER question_text;
ALTER TABLE interview_answer ADD COLUMN parent_answer_id BIGINT NULL AFTER question_type;
ALTER TABLE resume ADD COLUMN source_diagnosis_id BIGINT NULL AFTER parent_id;
ALTER TABLE resume ADD COLUMN source_suggestion_id VARCHAR(128) NULL AFTER source_diagnosis_id;
ALTER TABLE knowledge_document ADD COLUMN object_key VARCHAR(500) NULL AFTER source_uri;
ALTER TABLE knowledge_document ADD COLUMN error_reason VARCHAR(1000) NULL AFTER status;
CREATE TABLE IF NOT EXISTS prompt_template (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, prompt_key VARCHAR(80) NOT NULL UNIQUE, name VARCHAR(120) NOT NULL,
  content LONGTEXT NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
