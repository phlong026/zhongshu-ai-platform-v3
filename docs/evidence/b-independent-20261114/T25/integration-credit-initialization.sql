BEGIN;
SET LOCAL ROLE platform_owner;
DO $$ DECLARE b bigint; st text; old_delta bigint; BEGIN
 IF current_database()<>'zhongshu_platform_b' OR current_user<>'platform_owner' THEN RAISE EXCEPTION 'Wrong B target'; END IF;
 IF NOT EXISTS(SELECT 1 FROM system_dept d JOIN pf_org_unit u ON u.dept_id=d.id WHERE d.id=119 AND d.tenant_id=1 AND d.name='众墅之家联调公司' AND u.org_type='store' AND u.status='ACTIVE') THEN RAISE EXCEPTION 'Not the isolated integration company'; END IF;
 SELECT balance,status INTO STRICT b,st FROM org_point_account WHERE org_id=119 FOR UPDATE;
 SELECT available_delta INTO old_delta FROM org_point_ledger WHERE biz_key='integration-init-100-20261115:119';
 IF FOUND THEN IF old_delta<>100 THEN RAISE EXCEPTION 'Initialization key conflict'; END IF; RETURN; END IF;
 IF b<>0 OR st<>'ACTIVE' OR EXISTS(SELECT 1 FROM org_point_ledger WHERE org_id=119) THEN RAISE EXCEPTION 'Existing account activity needs review'; END IF;
 INSERT INTO org_point_ledger(id,org_id,biz_key,operation_time,type,actor_user_id,available_delta,ref_type,note)
 VALUES(202611150100,119,'integration-init-100-20261115:119',now(),'MANUAL_ADJUST',145,100,'INTEGRATION_BOOTSTRAP','NON_PRODUCTION: 用户明确授权一次性100测试积分；测试初始化，非充值、非双人复核，不修改A');
 UPDATE org_point_account SET balance=balance+100,version=version+1,update_time=now() WHERE org_id=119;
 IF (SELECT balance FROM org_point_account WHERE org_id=119)<>(SELECT sum(available_delta) FROM org_point_ledger WHERE org_id=119) THEN RAISE EXCEPTION 'Ledger/account mismatch'; END IF;
END $$;
COMMIT;
SELECT json_build_object('orgId',a.org_id,'balance',a.balance,'ledgerSum',(SELECT sum(available_delta) FROM org_point_ledger WHERE org_id=a.org_id),'grantCount',(SELECT count(*) FROM org_point_ledger WHERE biz_key='integration-init-100-20261115:119')) FROM org_point_account a WHERE org_id=119;
