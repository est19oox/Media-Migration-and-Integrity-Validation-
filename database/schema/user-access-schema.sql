```sql
-- User Access Service - Oracle Schema
-- Run as USER_ACCESS on FREEPDB1

CREATE TABLE app_users (
    user_id VARCHAR2(100) PRIMARY KEY,
    username VARCHAR2(100) NOT NULL UNIQUE,
    role VARCHAR2(50) NOT NULL,
    active NUMBER(1) DEFAULT 1 NOT NULL,
    CONSTRAINT chk_app_users_active
        CHECK (active IN (0, 1))
);

CREATE TABLE user_permissions (
    user_id VARCHAR2(100) NOT NULL,
    permission VARCHAR2(100) NOT NULL,

    CONSTRAINT pk_user_permissions
        PRIMARY KEY (user_id, permission),

    CONSTRAINT fk_user_permissions_user
        FOREIGN KEY (user_id)
        REFERENCES app_users(user_id)
        ON DELETE CASCADE
);

COMMIT;
```