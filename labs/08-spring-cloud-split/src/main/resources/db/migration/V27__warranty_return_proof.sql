ALTER TABLE dispute_evidence
    ADD COLUMN purpose VARCHAR(32) NULL AFTER case_type,
    ADD CONSTRAINT ck_dispute_evidence_purpose CHECK (purpose IS NULL OR purpose IN ('REPAIR_QUOTE','RETURN_PROOF'));

ALTER TABLE return_case
    MODIFY COLUMN dispute_case_id CHAR(36) NULL,
    ADD COLUMN warranty_case_id CHAR(36) NULL AFTER dispute_case_id,
    ADD UNIQUE KEY uk_return_case_warranty (warranty_case_id),
    ADD CONSTRAINT ck_return_case_source CHECK ((dispute_case_id IS NOT NULL) XOR (warranty_case_id IS NOT NULL)),
    ADD CONSTRAINT fk_return_case_warranty FOREIGN KEY (warranty_case_id) REFERENCES warranty_case (id);
