-- 总部 B 定价/成本入口，沿现有 ADMIN @ss 授权，不给任职角色自动授权。
INSERT INTO system_menu(id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater)
VALUES
(960021,'设计能力定价','platform:design:price:query',2,6,960000,'design-prices','ep:price-tag','platform/wallet/index','PlatformOpsDesignPrices',0,TRUE,FALSE,TRUE,'1','1'),
(960022,'设计调用成本','platform:design:cost:query',2,7,960000,'design-costs','ep:data-analysis','platform/wallet/index','PlatformOpsDesignCosts',0,TRUE,FALSE,TRUE,'1','1'),
(960023,'发布设计价格版本','platform:design:price:update',3,1,960021,'','',NULL,NULL,0,TRUE,FALSE,TRUE,'1','1');
CREATE INDEX fr_ops_design_price_versions ON fr_design_price(tenant_id,operation,resolution,version DESC);
CREATE INDEX fr_ops_design_command_page ON fr_design_remote_command(tenant_id,created_at DESC,command_id DESC);
