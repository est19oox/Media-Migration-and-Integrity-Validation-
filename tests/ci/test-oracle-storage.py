#!/usr/bin/env python3
"""Run the production reclaim SQL against the disposable CI Oracle instance."""
import os
from pathlib import Path
import runpy
import subprocess

ROOT = Path(__file__).resolve().parents[2]
SQL = runpy.run_path(str(ROOT/'scripts/cd/docker-stack-remote.py'))['ORACLE_STORAGE_SQL']
SETUP = '''
WHENEVER OSERROR EXIT FAILURE
WHENEVER SQLERROR EXIT FAILURE
SET SERVEROUTPUT ON
ALTER SESSION SET CONTAINER=FREEPDB1;
VARIABLE before_users NUMBER
VARIABLE before_system NUMBER
VARIABLE after_max NUMBER
VARIABLE after_temp_max NUMBER
VARIABLE before_auto NUMBER
VARIABLE after_first NUMBER
DECLARE
  file_number NUMBER;
  size_mb NUMBER;
BEGIN
  EXECUTE IMMEDIATE 'CREATE TABLE USER_ACCESS.MMIV_STORAGE_PROBE (id NUMBER PRIMARY KEY, payload VARCHAR2(100))';
  EXECUTE IMMEDIATE 'INSERT INTO USER_ACCESS.MMIV_STORAGE_PROBE SELECT LEVEL, RPAD(''sample'', 80, ''x'') FROM DUAL CONNECT BY LEVEL <= 20000';
  COMMIT;
  SELECT MIN(file_id) INTO file_number FROM dba_data_files WHERE tablespace_name = 'USERS';
  SELECT CEIL(bytes / 1048576) + 64 INTO size_mb FROM dba_data_files WHERE file_id = file_number;
  EXECUTE IMMEDIATE 'ALTER DATABASE DATAFILE ' || file_number || ' RESIZE ' || size_mb || 'M';
  SELECT SUM(bytes), SUM(maxbytes), SUM(CASE WHEN autoextensible = 'YES' THEN 1 ELSE 0 END)
    INTO :before_users, :after_max, :before_auto
    FROM dba_data_files WHERE tablespace_name = 'USERS';
  SELECT SUM(bytes) INTO :before_system FROM dba_data_files WHERE tablespace_name <> 'USERS';
END;
/
'''
CHECK = '''
DECLARE
  file_bytes NUMBER;
  system_bytes NUMBER;
  growth_max NUMBER;
  growth_auto NUMBER;
  retained_rows NUMBER;
  quota_bytes NUMBER;
  bad_caps NUMBER;
  unlimited_grants NUMBER;
BEGIN
  SELECT COUNT(*) INTO retained_rows FROM USER_ACCESS.MMIV_STORAGE_PROBE
    WHERE id BETWEEN 1 AND 20000 AND payload = RPAD('sample', 80, 'x');
  SELECT SUM(bytes), SUM(maxbytes), SUM(CASE WHEN autoextensible = 'YES' THEN 1 ELSE 0 END)
    INTO file_bytes, growth_max, growth_auto
    FROM dba_data_files WHERE tablespace_name = 'USERS';
  SELECT SUM(bytes) INTO system_bytes FROM dba_data_files WHERE tablespace_name <> 'USERS';
  SELECT max_bytes INTO quota_bytes FROM dba_ts_quotas
    WHERE username = 'USER_ACCESS' AND tablespace_name = 'USERS';
  SELECT COUNT(*) INTO unlimited_grants FROM dba_sys_privs
    WHERE grantee = 'USER_ACCESS' AND privilege = 'UNLIMITED TABLESPACE';
  SELECT COUNT(*) INTO bad_caps FROM dba_data_files d JOIN dba_tablespaces t
    ON t.tablespace_name = d.tablespace_name WHERE d.tablespace_name = 'USERS'
    AND d.autoextensible = 'YES' AND
    (d.maxbytes <> GREATEST(512 * 1048576, CEIL(d.bytes / 1048576) * 1048576)
     OR d.increment_by * t.block_size <> 8 * 1048576);
  IF retained_rows <> 20000 OR file_bytes >= :before_users OR
     system_bytes <> :before_system OR growth_auto <> :before_auto OR
     quota_bytes <> 128 * 1048576 OR unlimited_grants <> 0 OR bad_caps <> 0 THEN
    RAISE_APPLICATION_ERROR(-20001, 'Storage reclaim failed data, shrink or growth-policy checks');
  END IF;
  SELECT COUNT(*) INTO bad_caps FROM dba_temp_files d JOIN dba_tablespaces t
    ON t.tablespace_name = d.tablespace_name WHERE d.tablespace_name = 'TEMP'
    AND d.autoextensible = 'YES' AND
    (d.maxbytes <> GREATEST(128 * 1048576, CEIL(d.bytes / 1048576) * 1048576)
     OR d.increment_by * t.block_size <> 8 * 1048576);
  IF bad_caps <> 0 THEN RAISE_APPLICATION_ERROR(-20003, 'TEMP growth budget incorrect'); END IF;
  :after_first := file_bytes;
  :after_max := growth_max;
  SELECT SUM(maxbytes) INTO :after_temp_max FROM dba_temp_files WHERE tablespace_name = 'TEMP';
END;
/
'''
REPEAT_CHECK = '''
DECLARE
  file_bytes NUMBER;
  retained_rows NUMBER;
  growth_max NUMBER;
  temp_max NUMBER;
BEGIN
  SELECT SUM(bytes) INTO file_bytes FROM dba_data_files WHERE tablespace_name = 'USERS';
  SELECT COUNT(*) INTO retained_rows FROM USER_ACCESS.MMIV_STORAGE_PROBE
    WHERE id BETWEEN 1 AND 20000 AND payload = RPAD('sample', 80, 'x');
  SELECT SUM(maxbytes) INTO growth_max FROM dba_data_files WHERE tablespace_name = 'USERS';
  SELECT SUM(maxbytes) INTO temp_max FROM dba_temp_files WHERE tablespace_name = 'TEMP';
  IF file_bytes <> :after_first OR retained_rows <> 20000 OR
     growth_max <> :after_max OR temp_max <> :after_temp_max THEN
    RAISE_APPLICATION_ERROR(-20002, 'Repeated reclaim must preserve size and data');
  END IF;
  EXECUTE IMMEDIATE 'DROP TABLE USER_ACCESS.MMIV_STORAGE_PROBE PURGE';
  DBMS_OUTPUT.PUT_LINE('PASS: Oracle reclaimed unused bytes, preserved 20,000 rows/system files, enforced quotas/growth budgets, and repeated safely.');
END;
/
EXIT SUCCESS
'''

def main():
    container = os.environ.get('ORACLE_CONTAINER')
    if not container:
        print('SKIPPED: disposable CI Oracle container is required for SQL regression.')
        return
    import json
    details = json.loads(subprocess.check_output(['docker', 'inspect', container], text=True))[0]
    if details['HostConfig']['Memory'] != 2 * 1024 ** 3:
        raise RuntimeError('CI must validate Oracle with the same 2 GiB memory limit as EC2')
    subprocess.run(['docker','exec','-i',container,'sqlplus','-L','-s','/','as','sysdba'],
                   input=SETUP+SQL+CHECK+SQL+REPEAT_CHECK, text=True, check=True, timeout=180)

if __name__ == '__main__':
    main()
