-- Add the new headquarters administrator without revoking in-flight legacy design posts.
INSERT INTO pf_post_template(role_code,domain,org_types,line,lead,inherits_role_code)
VALUES ('hq-admin','franchise','headquarters','management',false,NULL)
ON CONFLICT (role_code) DO NOTHING;
