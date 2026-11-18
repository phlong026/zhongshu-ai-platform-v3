CREATE INDEX idx_fr_eng_project_company_manager ON fr_eng_project(company_id, manager_user_id);
CREATE INDEX idx_fr_eng_project_company_assigned ON fr_eng_project(company_id, assigned_engineer_user_id)
    WHERE assigned_engineer_user_id IS NOT NULL;
