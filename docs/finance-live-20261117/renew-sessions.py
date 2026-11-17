import sys,json,secrets,subprocess
sys.path.insert(0,'docs/finance-live-20261117');import joint_api as j
c=json.loads((j.PRIVATE/'secrets.json').read_text());old=json.loads((j.PRIVATE/'actors.json').read_text());admin=j.api('POST','/system/auth/login',{'username':c['adminUser'],'password':c['adminPassword']});root={'token':admin['accessToken'],'postId':''};passwords={};actors={}
accounts=json.loads(subprocess.check_output(['docker','exec','codex-finance-joint-pg-1117','psql','-At','-U','platform_owner','-d','zhongshu_platform_b','-c',"SELECT json_object_agg(user_id,account_id) FROM pf_franchise_account WHERE org_id=118"],encoding='utf8'))
for i,role in enumerate(['boss','sales','engineer','finance','finance-lead'],1):
 assert old[role]['userId']==145+i
 password='Aa1!'+secrets.token_hex(6);account=accounts[str(old[role]['userId'])]
 j.api('PUT','/system/user/update-password',{'id':old[role]['userId'],'password':password},root)
 result=j.login(account,password);actors[role]=j.session(result,role);actors[role].update({'refreshToken':result.get('refreshToken'),'expiresTime':result.get('expiresTime')});passwords[role]={'account':account,'password':password}
(j.PRIVATE/'actors.json').write_text(json.dumps(actors,ensure_ascii=False,indent=2));(j.PRIVATE/'test-logins.json').write_text(json.dumps(passwords,ensure_ascii=False));print('Renewed 5 isolated test sessions; no credentials printed')

