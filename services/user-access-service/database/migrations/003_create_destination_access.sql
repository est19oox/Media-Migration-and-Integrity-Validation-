-- User Access Service destination authorization schema.
-- Run after 001_create_schema.sql and 002_add_password_hash.sql.
-- Run as USER_ACCESS on FREEPDB1.

DECLARE
    table_count NUMBER;
BEGIN
    SELECT COUNT(*)
      INTO table_count
      FROM USER_TABLES
     WHERE TABLE_NAME = 'USER_DESTINATION_ACCESS';

    IF table_count = 0 THEN
        EXECUTE IMMEDIATE q'[
            CREATE TABLE USER_DESTINATION_ACCESS (
                USER_ID VARCHAR2(100) NOT NULL,
                DESTINATION VARCHAR2(255) NOT NULL,
                PERMISSION VARCHAR2(20) NOT NULL,
                CONSTRAINT PK_USER_DESTINATION_ACCESS
                    PRIMARY KEY (USER_ID, DESTINATION),
                CONSTRAINT FK_USER_DESTINATION_ACCESS_USER
                    FOREIGN KEY (USER_ID)
                    REFERENCES APP_USERS(USER_ID)
                    ON DELETE CASCADE,
                CONSTRAINT CHK_USER_DESTINATION_PERMISSION
                    CHECK (PERMISSION IN ('READ', 'WRITE'))
            )
        ]';
    END IF;
END;
/
