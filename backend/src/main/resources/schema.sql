CREATE TABLE IF NOT EXISTS app_user (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, email VARCHAR(128) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL, display_name VARCHAR(64) NOT NULL,
  role VARCHAR(24) NOT NULL DEFAULT 'USER', target_role VARCHAR(128), created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS resume (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, file_name VARCHAR(255) NOT NULL,
  object_key VARCHAR(255), file_url VARCHAR(600), raw_text LONGTEXT, parsed_json LONGTEXT,
  parent_id BIGINT, source_diagnosis_id BIGINT, source_suggestion_id VARCHAR(128), version_no INT NOT NULL DEFAULT 1, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_resume_user(user_id)
);
CREATE TABLE IF NOT EXISTS resume_diagnosis (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, resume_id BIGINT NOT NULL, result_json LONGTEXT NOT NULL,
  accepted_items_json TEXT, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_diagnosis_resume(resume_id)
);
CREATE TABLE IF NOT EXISTS knowledge_document (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, title VARCHAR(255) NOT NULL, category VARCHAR(64), difficulty VARCHAR(32),
  source_uri VARCHAR(500), object_key VARCHAR(500), status VARCHAR(24) NOT NULL DEFAULT 'READY', error_reason VARCHAR(1000), created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS knowledge_chunk (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, document_id BIGINT NOT NULL, chunk_index INT NOT NULL,
  content TEXT NOT NULL, category VARCHAR(64), technology VARCHAR(64), difficulty VARCHAR(32), source VARCHAR(500),
  vector_id VARCHAR(128), created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_chunk_doc(document_id)
);
CREATE TABLE IF NOT EXISTS question_set (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, resume_id BIGINT NOT NULL, title VARCHAR(255),
  job_title VARCHAR(160), job_description LONGTEXT, config_json TEXT, questions_json LONGTEXT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, INDEX idx_question_user(user_id)
);
CREATE TABLE IF NOT EXISTS interview_session (
  id VARCHAR(64) PRIMARY KEY, user_id BIGINT NOT NULL, target_role VARCHAR(160), status VARCHAR(24) NOT NULL,
  question_set_id BIGINT, current_question INT NOT NULL DEFAULT 0, current_question_text TEXT,
  question_type VARCHAR(24) NOT NULL DEFAULT 'BASE', parent_answer_id BIGINT,
  follow_up_count INT NOT NULL DEFAULT 0, snapshot_json LONGTEXT,
  started_at TIMESTAMP, ended_at TIMESTAMP
);
CREATE TABLE IF NOT EXISTS interview_answer (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, session_id VARCHAR(64) NOT NULL, question_text TEXT NOT NULL,
  question_type VARCHAR(24) NOT NULL DEFAULT 'BASE', parent_answer_id BIGINT,
  answer_text TEXT, evaluation_json TEXT, score DECIMAL(5,2), created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_answer_session(session_id), INDEX idx_answer_parent(parent_answer_id)
);
CREATE TABLE IF NOT EXISTS interview_report (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, session_id VARCHAR(64) NOT NULL UNIQUE, user_id BIGINT NOT NULL,
  overall_score DECIMAL(5,2) NOT NULL, report_json LONGTEXT NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_report_user(user_id)
);
CREATE TABLE IF NOT EXISTS ai_call_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, trace_id VARCHAR(64), provider VARCHAR(48), model VARCHAR(80), operation VARCHAR(80),
  input_tokens INT DEFAULT 0, output_tokens INT DEFAULT 0, latency_ms BIGINT, success BOOLEAN, error_code VARCHAR(64), created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS prompt_template (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, prompt_key VARCHAR(80) NOT NULL UNIQUE, name VARCHAR(120) NOT NULL,
  content LONGTEXT NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
