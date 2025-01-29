CREATE DATABASE reminder_bot;
\c reminder_bot
CREATE TABLE events
(
    message_id  SERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL,
    event_epoch BIGINT NOT NULL,
    topic       TEXT
);
CREATE INDEX idx_event_epoch ON events (event_epoch);
CREATE TABLE users
(
    user_id     BIGINT PRIMARY KEY,
    last_msg_epoch BIGINT,
    timezone_offset INT
);