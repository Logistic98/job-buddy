-- Remove legacy job_buddy_ table prefix while preserving historical data.
-- Keep this as a separate migration instead of editing old migrations, otherwise
-- Flyway checksum validation will fail for databases that already ran them.

ALTER TABLE IF EXISTS job_buddy_auth_state RENAME TO auth_state;
ALTER TABLE IF EXISTS job_buddy_blacklist_item RENAME TO blacklist_item;
ALTER TABLE IF EXISTS job_buddy_chat_message_log RENAME TO chat_message_log;
ALTER TABLE IF EXISTS job_buddy_chat_session_state RENAME TO chat_session_state;
ALTER TABLE IF EXISTS job_buddy_interview_exam_question RENAME TO interview_exam_question;
ALTER TABLE IF EXISTS job_buddy_interview_exam RENAME TO interview_exam;
ALTER TABLE IF EXISTS job_buddy_interview_question RENAME TO interview_question;
ALTER TABLE IF EXISTS job_buddy_job_favorite RENAME TO job_favorite;
ALTER TABLE IF EXISTS job_buddy_journey_record RENAME TO journey_record;
ALTER TABLE IF EXISTS job_buddy_journey_target RENAME TO journey_target;
ALTER TABLE IF EXISTS job_buddy_project_deep_dive_material RENAME TO project_deep_dive_material;
ALTER TABLE IF EXISTS job_buddy_project_deep_dive_project RENAME TO project_deep_dive_project;
ALTER TABLE IF EXISTS job_buddy_project_deep_dive_question RENAME TO project_deep_dive_question;
ALTER TABLE IF EXISTS job_buddy_resume_record RENAME TO resume_record;
