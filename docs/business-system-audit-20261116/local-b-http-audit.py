"""Real local B HTTP calls. Credentials stay in the private loopback test environment."""
import json
import sys
import time
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError

ROOT=Path(__file__).resolve().parents[2]
PRIVATE=Path('C:/Users/Administrator/.codex/artifacts/business-system-audit-20261116/live-b')
QA=ROOT/'qa/business-system-audit-20261116/run-20261116-054514'
BASE='http://127.0.0.1:18086/admin-api'
trace=[]


def api(method,path,body=None,token=None,post=None,key=None):
    assert path.startswith('/') and not path.startswith('//')
    headers={'Content-Type':'application/json','tenant-id':'1'}
    if token:headers['Authorization']='Bearer '+token
    if post:headers['X-Post-Id']=str(post)
    if key:headers['Idempotency-Key']=key
    start=time.monotonic()
    request=Request(BASE+path,data=json.dumps(body,ensure_ascii=False).encode() if body is not None else None,headers=headers,method=method)
    try:
        response=urlopen(request,timeout=15);status=response.status;result=json.load(response)
    except HTTPError as error:
        status=error.code;result=json.load(error)
    trace.append({'method':method,'path':path.split('?')[0],'httpStatus':status,'businessCode':result.get('code'),
                  'elapsedMs':round((time.monotonic()-start)*1000,2),'environment':'isolated real B API'})
    if status!=200 or result.get('code')!=0:
        raise RuntimeError(method+' '+path.split('?')[0]+': '+str(result.get('code'))+' '+str(result.get('msg')))
    return result.get('data')


def account_login(account,password,new_password=None):
    policy=api('GET','/platform/auth/login-policy')
    return api('POST','/platform/auth/account/login',{'accountId':account,'password':password,'newPassword':new_password,
        'loginVersion':2,'consent':{'accepted':True,'agreementVersion':policy['agreementVersion'],'privacyVersion':policy['privacyVersion']}})


def setup():
    import secrets
    creds=json.loads((PRIVATE/'secrets.json').read_text(encoding='utf-8'))
    admin=api('POST','/system/auth/login',{'username':creds['adminUser'],'password':creds['adminPassword']})
    boss=api('POST','/platform/org/franchise-onboard',{'brand':'合家美宅','province':'湖北省','city':'武汉市','area':'江夏区',
        'adminName':'测试老板','adminMobile':'19900000001','remark':'本机独立数据库审计样例'},admin['accessToken'])
    boss_password='Aa1!'+secrets.token_hex(6)
    (PRIVATE/'boss-setup-checkpoint.json').write_text(json.dumps({'boss':boss,'bossPassword':boss_password},ensure_ascii=False),encoding='utf-8')
    login=account_login(boss['accountId'],boss['initialPassword'],boss_password)
    posts=api('GET','/platform/posts/mine',token=login['accessToken'])
    boss_post=next(p for p in posts if str(p['orgId'])==str(boss['orgId']) and p['roleCode']=='boss')
    staff=api('POST','/platform/org/staff-accounts',{'orgId':boss['orgId'],'name':'测试业务','mobile':'19900000002','roleCode':'sales'},
        login['accessToken'],boss_post['postId'])
    (PRIVATE/'accounts.json').write_text(json.dumps({'boss':boss,'bossPassword':boss_password,'bossLogin':login,
        'bossPost':boss_post,'staff':staff,'staffNewPassword':'Aa1!'+secrets.token_hex(6)},ensure_ascii=False,indent=2),encoding='utf-8')
    return {'setup':'passed','realAdminLogin':True,'realBossOnboardAndPasswordChange':True,'realEmployeeOnboard':True,
            'employeeRole':'sales','organization':'本机独立审计组织','securityMock':False,'businessData':'real private PostgreSQL'}


def seed():
    session=json.loads((PRIVATE/'ui-session.json').read_text(encoding='utf-8'))
    token,post=session['token'],session['postId']
    rows=[]
    for i in range(60):
        item=api('POST','/franchise/leads',{'name':'跨页目标' if i==0 else '本地客户'+str(i),
            'phone':'1990001'+str(i).zfill(4),'need':'本地隔离环境联验，非真实客户','source':'本机测试'},token,post,'local-audit-lead-'+str(i))
        rows.append(item)
        if i==0:
            api('PUT','/franchise/customers/'+str(item['customerId']),{'name':'跨页目标','wechat':'','province':'湖北省','city':'宜昌市',
                'summary':'地区搜索与跨页联验样例','revision':1},token,post,'local-audit-region')
    page=api('GET','/franchise/customers?pageNo=1&pageSize=50',token=token,post=post)
    target=api('GET','/franchise/customers?pageNo=1&pageSize=50&keyword='+__import__('urllib.parse').parse.quote('跨页目标'),token=token,post=post)
    region=api('GET','/franchise/customers?pageNo=1&pageSize=50&keyword='+__import__('urllib.parse').parse.quote('湖北'),token=token,post=post)
    assert page['total']==60 and len(page['list'])==50
    assert str(rows[0]['customerId']) not in {str(row['id']) for row in page['list']}
    assert target['total']==1 and region['total']==1
    (PRIVATE/'customer-seed.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2),encoding='utf-8')
    return {'seed':'passed','createdViaRealApi':60,'firstPageRows':50,'total':60,
            'targetOutsideFirstPage':True,'keywordTotal':target['total'],'provinceTotal':region['total']}


def verify():
    session=json.loads((PRIVATE/'ui-session.json').read_text(encoding='utf-8'))
    rows=json.loads((PRIVATE/'customer-seed.json').read_text(encoding='utf-8'))
    detail=api('GET','/franchise/customers/'+str(rows[0]['customerId']),token=session['token'],post=session['postId'])
    customer=detail['customer']
    assert customer['name']=='跨页目标已核验'
    assert customer['summary']=='本机界面保存已核验，非真实客户。'
    assert len(detail['follows'])==1 and detail['follows'][0]['note']=='本地后端联验：已了解两层布局需求。'
    assert customer['status']=='FOLLOWING' and customer['revision']==4
    registered=api('GET','/franchise/customers?pageNo=1&pageSize=50&keyword='+__import__('urllib.parse').parse.quote('界面登记样例'),token=session['token'],post=session['postId'])
    assert registered['total']==1 and registered['list'][0]['source']=='视频号咨询'
    assert registered['list'][0]['summary']=='本机原生界面登记联验，非真实客户。'
    handles=json.loads((PRIVATE/'ui-handles.json').read_text(encoding='utf-8'))
    requirements=api('GET','/franchise/sales/requirements?customerId='+str(handles['customerId']),token=session['token'],post=session['postId'])
    assert len(requirements)==1 and requirements[0]['width']==12 and requirements[0]['depth']==10 and requirements[0]['floors']==2
    exported=api('GET','/franchise/sales/quotes/'+str(handles['quoteId'])+'/export-sheet',token=session['token'],post=session['postId'])
    assert exported['total']==32040000 and exported['mainTotal']==25440000 and exported['decorTotal']==6600000
    assert exported['date']=='2026-11-16'
    assert exported['form']=={'foundation':'独立基础','structure':'框架结构'}
    contracts=api('GET','/franchise/sales/contracts?customerId='+str(handles['customerId']),token=session['token'],post=session['postId'])
    assert len(contracts)==1 and contracts[0]['contract']['status']=='WAIT_SIGN'
    assert contracts[0]['contract']['totalCents']==32040000
    assert sorted(stage['amountCents'] for stage in contracts[0]['stages'])==[0,0,32040000]
    return {'verify':'passed','freshBackendRead':True,'editedNamePersisted':True,'editedSummaryPersisted':True,
            'followCount':len(detail['follows']),'status':customer['status'],'revision':customer['revision'],
            'nextVisitDate':customer['nextVisitDate'],'actualWritesFromNativeUi':6,'uiCustomerRegistrationPersisted':True,
            'requirementVersions':len(requirements),'quoteTotalCents':exported['total'],'quoteDate':exported['date'],
            'quoteConfiguration':exported['form'],'contractCount':len(contracts),'contractStatus':contracts[0]['contract']['status'],
            'contractStageCount':len(contracts[0]['stages'])}


if __name__=='__main__':
    action=sys.argv[1]
    try:
        result={'setup':setup,'seed':seed,'verify':verify}[action]()
        (QA/('local-b-'+action+'-result.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
        print(json.dumps(result,ensure_ascii=False))
    finally:
        (QA/('local-b-'+action+'-http-trace.json')).write_text(json.dumps(trace,ensure_ascii=False,indent=2),encoding='utf-8')
