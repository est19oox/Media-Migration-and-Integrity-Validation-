-- MMIV User Access authentication migration
-- Run as USER_ACCESS in FREEPDB1
-- Existing users remain unchanged.

DECLARE
    column_count NUMBER;
BEGIN
    SELECT COUNT(*)
    INTO column_count
    FROM user_tab_columns
    WHERE table_name = 'APP_USERS'
      AND column_name = 'PASSWORD_HASH';

    IF column_count = 0 THEN
        EXECUTE IMMEDIATE
            'ALTER TABLE app_users ADD (password_hash VARCHAR2(255))';
    END IF;
END;
/
