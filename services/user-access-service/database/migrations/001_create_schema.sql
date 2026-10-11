-- User Access Service Oracle schema
-- Run as USER_ACCESS on FREEPDB1.

SET SERVEROUTPUT ON

DECLARE
    table_count NUMBER;
BEGIN
    SELECT COUNT(*)
      INTO table_count
      FROM USER_TABLES
     WHERE TABLE_NAME = 'APP_USERS';

    IF table_count = 0 THEN
        EXECUTE IMMEDIATE q'[
            CREATE TABLE APP_USERS (
                USER_ID VARCHAR2(100) PRIMARY KEY,
                USERNAME VARCHAR2(100) NOT NULL UNIQUE,
                ROLE VARCHAR2(50) NOT NULL,
                ACTIVE NUMBER(1) DEFAULT 1 NOT NULL,
                CONSTRAINT CHK_APP_USERS_ACTIVE
                    CHECK (ACTIVE IN (0, 1))
            )
        ]';
        DBMS_OUTPUT.PUT_LINE('Created APP_USERS.');
    ELSE
        DBMS_OUTPUT.PUT_LINE('APP_USERS already exists; left unchanged.');
    END IF;
END;
/

DECLARE
    table_count NUMBER;
BEGIN
    SELECT COUNT(*)
      INTO table_count
      FROM USER_TABLES
     WHERE TABLE_NAME = 'USER_PERMISSIONS';

    IF table_count = 0 THEN
        EXECUTE IMMEDIATE q'[
            CREATE TABLE USER_PERMISSIONS (
                USER_ID VARCHAR2(100) NOT NULL,
                PERMISSION VARCHAR2(100) NOT NULL,
                CONSTRAINT PK_USER_PERMISSIONS
                    PRIMARY KEY (USER_ID, PERMISSION),
                CONSTRAINT FK_USER_PERMISSIONS_USER
                    FOREIGN KEY (USER_ID)
                    REFERENCES APP_USERS(USER_ID)
                    ON DELETE CASCADE
            )
        ]';
        DBMS_OUTPUT.PUT_LINE('Created USER_PERMISSIONS.');
    ELSE
        DBMS_OUTPUT.PUT_LINE('USER_PERMISSIONS already exists; left unchanged.');
    END IF;
END;
/

DECLARE
    index_count NUMBER;
BEGIN
    SELECT COUNT(*)
      INTO index_count
      FROM USER_INDEXES
     WHERE INDEX_NAME = 'IX_USER_PERMISSIONS_USER_ID';

    IF index_count = 0 THEN
        EXECUTE IMMEDIATE
            'CREATE INDEX IX_USER_PERMISSIONS_USER_ID ON USER_PERMISSIONS (USER_ID)';
        DBMS_OUTPUT.PUT_LINE('Created IX_USER_PERMISSIONS_USER_ID.');
    ELSE
        DBMS_OUTPUT.PUT_LINE('IX_USER_PERMISSIONS_USER_ID already exists; left unchanged.');
    END IF;
END;
/

COMMIT;

SELECT TABLE_NAME
  FROM USER_TABLES
 WHERE TABLE_NAME IN ('APP_USERS', 'USER_PERMISSIONS')
 ORDER BY TABLE_NAME;
