DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM design_provider_prompt_event_receipt LIMIT 1) THEN
        RAISE EXCEPTION 'refusing to drop non-empty design_provider_prompt_event_receipt';
    END IF;
END $$;

DROP TABLE design_provider_prompt_event_receipt;
