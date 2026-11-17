"""Isolated real HTTP finance acceptance. Session credentials never enter the repository."""
import json,sys,secrets,time
from pathlib import Path
from urllib.request import Request,urlopen
from urllib.error import HTTPError
from urllib.parse import quote
PRIVATE=Path('C:/Users/Administrator/.codex/artifacts/finance-joint-20261117/run1')
OUT=Path(__file__).resolve().parent
BASE='http://127.0.0.1:18091/admin-api'
trace=[]
def api(method,path,body=None,actor=None,key=None):
    h={'Content-Type':'application/json','tenant-id':'1'}
    if actor:h.update({'Authorization':'Bearer '+actor['token'],'X-Post-Id':str(actor['postId'])})
    if key:h['Idempotency-Key']=key
    req=Request(BASE+path,data=None if body is None else json.dumps(body,ensure_ascii=False).encode(),headers=h,method=method)
    try:
        with urlopen(req,timeout=20) as response:status=response.status;result=json.load(response)
    except HTTPError as e:status=e.code;result=json.load(e)
    trace.append({'method':method,'path':path.split('?')[0],'status':status,'code':result.get('code')})
    if status!=200 or result.get('code')!=0:raise RuntimeError(f"{method} {path}: {result.get('code')} {result.get('msg')}")
    return result.get('data')
def login(account,password,new=None):
    policy=api('GET','/platform/auth/login-policy')
    return api('POST','/platform/auth/account/login',{'accountId':account,'password':password,'newPassword':new,'loginVersion':2,'consent':{'accepted':True,'agreementVersion':policy['agreementVersion'],'privacyVersion':policy['privacyVersion']}})
def session(result,role):
    token={'token':result['accessToken']}
    posts=api('GET','/platform/posts/mine',actor={**token,'postId':''})
    post=next(p for p in posts if p['roleCode']==role)
    return {**token,'postId':post['postId'],'userId':result['userId'],'posts':posts,'orgId':post['orgId']}
def init():
    c=json.loads((PRIVATE/'secrets.json').read_text())
    admin=api('POST','/system/auth/login',{'username':c['adminUser'],'password':c['adminPassword']})
    root={'token':admin['accessToken'],'postId':''}
    onboard=api('POST','/platform/org/franchise-onboard',{'brand':'合家美宅','province':'湖北省','city':'武汉市','area':'江夏区','adminName':'联调老板','adminMobile':'19900111701','remark':'财务四岗位联调测试组织，无真实付款'},root)
    password='Aa1!'+secrets.token_hex(8)
    actors={'boss':session(login(onboard['accountId'],onboard['initialPassword'],password),'boss')}
    for i,(role,name) in enumerate([('sales','联调业务'),('engineer','联调工程'),('finance','联调财务'),('finance-lead','联调财务负责人')],2):
        staff=api('POST','/platform/org/staff-accounts',{'orgId':onboard['orgId'],'name':name,'mobile':'199001117'+str(i).zfill(2),'roleCode':role},actors['boss'])
        actors[role]=session(login(staff['accountId'],staff['initialPassword'],'Aa1!'+secrets.token_hex(8)),role)
    (PRIVATE/'actors.json').write_text(json.dumps(actors,ensure_ascii=False,indent=2))
    (OUT/'environment.json').write_text(json.dumps({'api':BASE,'data':'isolated PostgreSQL, actual application HTTP','actors':{k:{'userId':v['userId'],'postId':v['postId'],'orgId':v['orgId']} for k,v in actors.items()},'paidAiCalls':0,'realTransfers':0},ensure_ascii=False,indent=2))
    print(json.dumps({'initialized':True,'roles':list(actors),'organization':onboard['orgId']},ensure_ascii=False))
if __name__=='__main__':
    try:{'init':init}[sys.argv[1]]()
    finally:(OUT/'setup-http-trace.json').write_text(json.dumps(trace,ensure_ascii=False,indent=2))
