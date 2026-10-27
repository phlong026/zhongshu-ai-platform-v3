-- 回滚 V20261022.801：须先撤销使用这些岗位的任职与组织
DELETE FROM pf_post_template WHERE domain = 'franchise';
DELETE FROM pf_org_type WHERE domain = 'franchise';
