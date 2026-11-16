"""Controlled HTTP acceptance. Credentials stay in process memory; only fixture IDs are saved."""
import argparse
import hashlib
import json
import os
import secrets
import string
import subprocess
import time
import sys
sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8")
import urllib.error
import urllib.request
from pathlib import Path

BASE='https://platform.zszhj.com/admin-api'
FILE=Path('E:/zs-admin-ops-20261116/live-fixture.json')
KEY='C:/Users/Administrator/.ssh/zhongshu-cvm'
HOST='ubuntu@124.223.61.18'
def api(path,data=None,token=None,method=None,headers=None,raw=False):
    h={'tenant-id':'1','Content-Type':'application/json'}
    if token:h['Authorization']='Bearer '+token
    h.update(headers or {})
    request=urllib.request.Request(BASE+path,data=json.dumps(data,ensure_ascii=False).encode() if data is not None else None,headers=h,method=method)
    try:
        with urllib.request.urlopen(request,timeout=30) as response:result=json.load(response)
    except urllib.error.HTTPError as error:
        try:result=json.load(error)
        except Exception:result={'code':error.code,'msg':'HTTP error'}
    if raw:return result
    assert result.get('code')==0,f'{path}: code={result.get("code")}, {result.get("msg")}'
    return result.get('data')
def login(username,password):return api('/system/auth/login',{'username':username,'password':password})
def sql(statement):
    script='statement='+repr(statement)+'\n'+r'''
import subprocess,json
result=subprocess.run(['docker','exec','zhongshu-platform-b-integration-postgres-b-1','sh','-c','exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -XAt -v ON_ERROR_STOP=1 -c "$1"','sh',statement],text=True,capture_output=True)
if result.returncode:raise RuntimeError(result.stderr)
print(result.stdout)
'''
    result=subprocess.run(['ssh','-i',KEY,'-oBatchMode=yes',HOST,'sudo python3 -'],input=script,text=True,encoding="utf-8",capture_output=True)
    assert result.returncode==0,"Fixture SQL failed: "+result.stderr
    lines=result.stdout.strip().splitlines()
    return json.loads(lines[-1]) if lines else None
def save(fixture):FILE.write_text(json.dumps(fixture,ensure_ascii=False,indent=2),encoding='utf-8')
def quote(value):return "'"+str(value).replace("'","''")+"'"
def setup(admin):
    if FILE.exists():
        fixture=json.loads(FILE.read_text(encoding='utf-8'));assert not fixture['organizations'],'Fixture already exists; reuse it or clean it first';tag=fixture['tag']
    else:
        tag=''.join(secrets.choice(string.ascii_lowercase) for _ in range(7))
        fixture={'tag':tag,'organizations':[],'checks':[],'createdAt':time.strftime('%Y-%m-%dT%H:%M:%S')};save(fixture)
    owner_tokens=[]
    for index in range(2):
        mobile='199000'+str(secrets.randbelow(100000)).zfill(5)
        count=sql('SELECT COUNT(*) FROM system_users WHERE deleted=0 AND mobile='+quote(mobile))
        assert count==0,'Generated fixture mobile already exists'
        result=api('/platform/org/franchise-onboard',{'brand':'合家美宅','province':'江西省','city':'南昌市','area':'青山湖区','adminName':'联调'+tag+('甲' if index==0 else '乙'),'adminMobile':mobile,'remark':'组织运营功能联调测试，完成后关闭并软删除'},admin)
        assert not result['reusedOrg'] and not result['reusedUser']
        initial=result.pop('initialPassword');result['mobile']=mobile
        fixture['organizations'].append(result);save(fixture)
        # Use the existing sign-in endpoint; no token forging or password resets.
        response=api('/system/auth/login',{'username':result['loginName'],'password':initial},raw=True)
        if response.get('code')==0:owner_tokens.append(response['data']['accessToken'])
        else:owner_tokens.append(None)
    a,b=fixture['organizations'];org=int(a['orgId']);other=int(b['orgId'])
    profile=api(f'/platform/operations/organizations/{org}/profile',token=admin)
    policy=api(f'/platform/operations/organizations/{org}/capabilities',token=admin)
    assert policy['version']==0 and not any(item['enabled'] for item in policy['items'])
    fixture['checks'].append('new organization defaults to no capabilities')
    # Test-only membership setup, preserving all existing users and organizations.
    post=8_000_000_000_000_000_000+int(time.time()*1000)
    sql(f"INSERT INTO pf_staff_post(id,user_id,org_id,role_code,status) VALUES({post},{int(b['adminUserId'])},{org},'sales','ACTIVE'); SELECT {post}")
    candidates=api(f'/platform/operations/organizations/{org}/owner-candidates',token=admin)
    assert str(b['adminUserId']) in [str(row['id']) for row in candidates]
    fixture['checks'].append('owner candidates include existing active members')
    if owner_tokens[0]:
        denied=api(f'/platform/operations/organizations/{org}/profile',token=owner_tokens[0],raw=True)
        assert denied.get('code') in (401,403),str(denied.get('code'))
        fixture['checks'].append('franchise owner cannot access headquarters operations')
    else:fixture['checks'].append('franchise login requires miniapp account flow; HTTP role boundary covered in isolated integration tests')
    denied=api(f'/platform/operations/organizations/{org}/profile',token=admin,headers={'tenant-id':'2'},raw=True)
    assert denied.get('code') in (401,403,404)
    assert api(f'/platform/operations/organizations/{org}/profile',token=admin)['version']==profile['version']
    fixture['checks'].append('wrong tenant denied; original session remains valid')
    save(fixture)
    seed(admin,fixture)

def seed(admin,fixture):
    a,b=fixture["organizations"];org=int(a["orgId"]);other=int(b["orgId"]);tag=fixture["tag"];post=8_000_000_000_000_000_000+int(time.time()*1000)
    prices=api('/platform/org-points/prices',token=admin)
    price=next(row for row in prices if row['subject']=='digital')
    quantity=30;credits=max((quantity*int(price['priceMicros'])+999999)//1000000,int(price['minCharge']))
    assert 0<credits<=10,'Fixture budget exceeds ten credits per task'
    api(f'/platform/org-points/{org}/credits',{'credits':credits*3,'reason':'联调测试赠送积分，仅供本次验收，无供应商调用','requestKey':'ops-fixture-credit:'+tag},admin)
    fixture['quoteCredits']=credits
    snapshot={'subject':'digital','unit':price['unit'],'priceMicrosPerUnit':int(price['priceMicros']),'priceVersion':price['versionText'],'minCharge':int(price['minCharge']),'quantity':quantity,'credits':credits}
    fixture['tasks']=[]
    statements=['BEGIN',f'SELECT org_id FROM org_point_account WHERE org_id={org} FOR UPDATE']
    for index,state in enumerate(['SUBMISSION_UNCERTAIN','SUBMISSION_UNCERTAIN','FAILED']):
        task=post+100+index;operation=post+200+index;ledger=post+300+index;biz=f'ops-fixture:{tag}:{index}'
        fixture['tasks'].append({'id':str(task),'operation':str(operation),'key':'operation:'+str(operation),'state':state})
        statements.append(f"INSERT INTO fr_content_task(id,company_id,author_user_id,kind,input_snapshot,input_sha256,provider,status,pool_biz_key,pool_reserved_units,pool_terminal_intent,pool_reconcile_after,failed_reason) VALUES({task},{org},{int(a['adminUserId'])},'digital','{{}}',{quote(hashlib.sha256(biz.encode()).hexdigest())},'fake',{quote(state)},{quote(biz)},30,'RELEASE',now()+interval '1 day','联调测试：模拟供应商状态，不进行真实生成')")
        statements.append(f"INSERT INTO org_point_ledger(id,org_id,operation_id,biz_key,operation_time,type,subject,actor_user_id,actor_post_id,available_delta,billing_month,price_snapshot,ref_type,ref_id,note) VALUES({ledger},{org},{operation},{quote(biz)},now(),'RESERVE','digital',{int(a['adminUserId'])},{int(a['postId'])},-{credits},to_char(now(),'YYYY-MM'),{quote(json.dumps(snapshot))}::jsonb,'content_task',{task},'联调验收：模拟预扣，不调用供应商')")
    statements.extend([f'UPDATE org_point_account SET balance=balance-{credits*3},version=version+1,update_time=now() WHERE org_id={org}', 'COMMIT',f"SELECT json_build_object('balance',balance) FROM org_point_account WHERE org_id={org}"])
    save(fixture)
    assert sql(';'.join(statements))['balance']==0
    for task in fixture['tasks']:
        detail=api('/platform/operations/tasks/'+task['key'],token=admin)
        assert detail['task']['operation_id']==task['operation']
        assert detail['task']['billing_state']=='PENDING'
    fixture['checks'].append('synthetic reserve fixtures preserve 64-bit IDs and real ledger balance')
    save(fixture)
    print(json.dumps({'orgId':org,'otherOrgId':other,'taskKeys':[task['key'] for task in fixture['tasks']],'checks':fixture['checks']},ensure_ascii=False))

def configure(admin,fixture):
    org=int(fixture['organizations'][0]['orgId'])
    profile=api(f'/platform/operations/organizations/{org}/profile',token=admin)
    assert profile['contactName']=='联调联系人甲' and profile['contactMobile']=='+86 19900081874'
    assert profile['organization']['loginName']==fixture['organizations'][0]['loginName']
    fixture['checks'].append('browser profile save persisted contacts and retained login account')
    root=f'/platform/operations/organizations/{org}/capabilities'
    current=api(root,token=admin);assert current['version']==0 and not any(row['enabled'] for row in current['items'])
    fixture['checks'].append('new organization defaults to all capabilities closed')
    command={'expectedVersion':0,'enabledSubjects':['digital','copy'],'reason':'联调验收：开通后验证关闭及在途结算','requestKey':'ops-cap:'+fixture['tag']}
    policy=api(root,command,admin);assert policy['version']==1
    assert {row['subject'] for row in policy['items'] if row['enabled']}=={'digital','copy'}
    assert api(root,command,admin)['version']==1
    stale=api(root,{**command,'requestKey':'ops-stale:'+fixture['tag']},admin,raw=True);assert stale.get('code')==409
    mismatch=api(root,{**command,'enabledSubjects':[]},admin,raw=True);assert mismatch.get('code')==409
    fixture['checks'].append('capability update replay is idempotent; stale and reused conflicting requests return 409')
    denied=api(f'/platform/operations/organizations/{org}/profile',token=admin,headers={'tenant-id':'2'},raw=True)
    assert denied.get('code') in (401,403,404)
    assert api(root,token=admin)['version']==1
    fixture['checks'].append('wrong tenant denied without invalidating original administrator session')
    save(fixture);print(json.dumps({'orgId':org,'capabilityVersion':1,'enabled':['digital','copy'],'checks':fixture['checks']},ensure_ascii=False))

def verify(admin,fixture):
    a,b=fixture['organizations'];org=int(a['orgId']);tag=fixture['tag']
    policy=api(f'/platform/operations/organizations/{org}/capabilities',token=admin)
    assert policy['version']==2 and not any(item['enabled'] for item in policy['items'])
    pending=api(f'/platform/org/directory/{org}/points',token=admin)
    assert int(pending['month_used'])==0 and int(pending['in_flight'])==fixture['quoteCredits']*3
    fixture['checks'].append('organization pending reserves are excluded from displayed consumption')
    fixture['checks'].append('browser closed capabilities and persisted version 2')
    current=api(f'/platform/operations/organizations/{org}/profile',token=admin)
    command={'expectedVersion':current['version'],'newOwnerUserId':str(b['adminUserId']),'confirmedName':current['organization']['orgName'],'reason':'联调验收：交接老板，保留原账号及个人凭据','requestKey':'ops-owner:'+tag}
    changed=api(f'/platform/operations/organizations/{org}/owner',command,admin)
    assert changed['ownerUserId']==str(b['adminUserId']) and changed['organization']['loginName']==a['loginName']
    assert api(f'/platform/operations/organizations/{org}/owner',command,admin)['version']==changed['version']
    check=sql(f"SELECT json_build_object('oldBoss',(SELECT COUNT(*) FROM pf_staff_post WHERE org_id={org} AND user_id={int(a['adminUserId'])} AND role_code='boss' AND status='ACTIVE'),'newBoss',(SELECT COUNT(*) FROM pf_staff_post WHERE org_id={org} AND user_id={int(b['adminUserId'])} AND role_code='boss' AND status='ACTIVE'),'otherBoss',(SELECT COUNT(*) FROM pf_staff_post WHERE org_id={int(b['orgId'])} AND user_id={int(b['adminUserId'])} AND role_code='boss' AND status='ACTIVE'),'sales',(SELECT COUNT(*) FROM pf_staff_post WHERE org_id={org} AND user_id={int(b['adminUserId'])} AND role_code='sales' AND status='ACTIVE'))")
    assert check=={'oldBoss':0,'newBoss':1,'otherBoss':1,'sales':1},check
    assert changed['mobileBlockedReason'],'Shared owner mobile must not be changed from one organization'
    fixture['checks'].append('owner transfer revoked only original boss, preserved target sales and other organization boss, retained login alias')
    for task,decision in zip(fixture['tasks'],['DELIVERED','NOT_DELIVERED','SYNC_TERMINAL']):
        root='/platform/operations/tasks/'+task['key'];before=api(root,token=admin)
        command={'expectedVersion':int(before['task']['case_version']),'decision':decision,'reason':'联调验收：核对模拟任务并关闭账务','evidence':'测试工单 OPS-'+decision+'，任务及原预扣均为本次联调数据，无供应商生成调用','requestKey':'ops-review:'+tag+':'+decision}
        result=api(root+'/review',command,admin)
        expected='SETTLED' if decision=='DELIVERED' else 'RELEASED'
        assert result['task']['billing_state']==expected and result['task']['case_status']=='RESOLVED'
        replay=api(root+'/review',command,admin);assert replay['task']['case_version']==result['task']['case_version']
        assert len([row for row in replay['ledger'] if row['type'] in ('SETTLE','RELEASE')])==1
        assert result['task']['charged']==str(fixture['quoteCredits'] if decision=='DELIVERED' else 0)
        fixture['checks'].append(decision+' succeeded after capability closure and original author removal; replay produced one terminal')
    summary=api(f'/platform/org/directory/{org}/points',token=admin)
    assert int(summary['in_flight'])==0
    assert int(summary['month_used'])==fixture['quoteCredits'] and int(summary['total_used'])==fixture['quoteCredits']
    assert int(summary['balance'])==fixture['quoteCredits']*2
    fixture['checks'].append('settled consumption excludes reserves and refunds; account matches ledger')
    fixture['summary']=summary;save(fixture)
    print(json.dumps({'orgId':org,'summary':summary,'checks':fixture['checks']},ensure_ascii=False))

def cleanup(admin,fixture):
    a,b=fixture['organizations'];org=int(a['orgId']);tag=fixture['tag']
    assert all(task['key'].startswith('operation:8') for task in fixture['tasks'])
    for task in fixture['tasks']:
        assert api('/platform/operations/tasks/'+task['key'],token=admin)['task']['billing_state'] in ('SETTLED','RELEASED')
    users=[int(a['adminUserId']),int(b['adminUserId'])];orgs=[int(a['orgId']),int(b['orgId'])]
    user_ids=','.join(map(str,users));org_ids=','.join(map(str,orgs))
    assert sql(f"SELECT COUNT(*) FROM pf_staff_post WHERE user_id IN ({user_ids}) AND org_id NOT IN ({org_ids}) AND status='ACTIVE'")==0,'Fixture users have unexpected memberships'
    assert sql(f"SELECT COUNT(*) FROM pf_franchise_account WHERE user_id IN ({user_ids}) AND org_id NOT IN ({org_ids})")==0
    adjustment=8_000_000_000_000_000_000+int(time.time()*1000)+999
    reclaim='ops-fixture-reclaim:'+tag
    # The fixture is synthetic, funded only by the test gift. Preserve append-only ledger history.
    sql(f"""BEGIN;
      SELECT org_id FROM org_point_account WHERE org_id={org} FOR UPDATE;
      INSERT INTO org_point_ledger(id,org_id,biz_key,type,actor_user_id,available_delta,note)
        SELECT {adjustment},org_id,{quote(reclaim)},'MANUAL_ADJUST',
          (SELECT id FROM system_users WHERE tenant_id=1 AND username='badmin' AND deleted=0),-balance,
          '联调验收结束：回收未使用的测试赠送积分，非现金退款'
        FROM org_point_account WHERE org_id={org} AND balance>0
        AND NOT EXISTS(SELECT 1 FROM org_point_ledger WHERE biz_key={quote(reclaim)});
      UPDATE org_point_account SET balance=(SELECT COALESCE(SUM(available_delta),0) FROM org_point_ledger WHERE org_id={org}),version=version+1,update_time=now() WHERE org_id={org};
      COMMIT;SELECT json_build_object('balance',balance,'ledger',(SELECT SUM(available_delta) FROM org_point_ledger WHERE org_id={org})) FROM org_point_account WHERE org_id={org}""")
    summary=api(f'/platform/org/directory/{org}/points',token=admin)
    assert int(summary['balance'])==0 and int(summary['in_flight'])==0
    for organization in fixture['organizations']:
        identity=int(organization['orgId']);current=api(f'/platform/org/directory/{identity}',token=admin)['organization']
        for action,state in [('close','DISABLED'),('delete','DELETED')]:
            if current['status']=='DELETED' or (action=='close' and current['status']=='DISABLED'):continue
            current=api(f'/platform/org/directory/{identity}/lifecycle',{'action':action,'confirmedName':current['orgName'],'reason':'组织运营联调完成，归档本次测试组织','requestKey':'ops-'+action+':'+tag+':'+str(identity)},admin)['organization']
            assert current['status']==state
    # Release test login names; original aliases are test-only and never represented real users.
    for organization in fixture['organizations']:
        identity=int(organization['orgId']);user=int(organization['adminUserId']);account=quote(organization['accountId'])
        sql(f"BEGIN; DELETE FROM pf_franchise_account_alias WHERE tenant_id=1 AND account_id={account}; UPDATE pf_franchise_account SET login_name={quote('ops-fixture-'+tag+'-'+str(identity))} WHERE tenant_id=1 AND org_id={identity} AND account_id={account}; UPDATE system_users SET status=1,mobile='',username={quote('ops-fixture-'+tag+'-'+str(user))},update_time=now() WHERE tenant_id=1 AND id={user}; COMMIT;SELECT {identity}")
    assert sql(f"SELECT COUNT(*) FROM system_users WHERE id IN ({user_ids}) AND status<>1")==0
    final=api(f'/platform/org/directory/{org}/points',token=admin)
    assert int(final['balance'])==0 and int(final['in_flight'])==0
    fixture['cleaned']=True;fixture['cleanup']={'archivedOrganizations':orgs,'disabledUsers':users,'balance':final['balance'],'inFlight':final['in_flight'],'preservedSettlement':final['total_used'],'testLoginNamesReleased':True,'testMobilesReleased':True};save(fixture)
    print(json.dumps(fixture['cleanup'],ensure_ascii=False))

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('mode',choices=['setup','seed','configure','verify','cleanup']);args=parser.parse_args()
    admin=login('badmin',os.environ['ZS_OPS_ADMIN_PASSWORD'])['accessToken']
    if args.mode=="setup":setup(admin)
    elif args.mode=='seed':seed(admin,json.loads(FILE.read_text(encoding="utf-8")))
    elif args.mode=='configure':configure(admin,json.loads(FILE.read_text(encoding="utf-8")))
    elif args.mode=='verify':verify(admin,json.loads(FILE.read_text(encoding="utf-8")))
    else:cleanup(admin,json.loads(FILE.read_text(encoding="utf-8")))
