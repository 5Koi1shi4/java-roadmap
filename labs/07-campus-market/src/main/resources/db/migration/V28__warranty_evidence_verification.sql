/* Warranty evidence is classified and verified at the physical upload boundary. */
ALTER TABLE dispute_evidence
    ADD COLUMN verification_status VARCHAR(16) NOT NULL DEFAULT 'UNVERIFIED' AFTER purpose,
    DROP CHECK ck_dispute_evidence_purpose,
    ADD CONSTRAINT ck_dispute_evidence_purpose CHECK (purpose IS NULL OR purpose IN ('REPAIR_QUOTE','INVOICE','RETURN_PROOF')),
    ADD CONSTRAINT ck_dispute_evidence_verification CHECK (verification_status IN ('UNVERIFIED','VERIFIED','REJECTED')),
    ADD KEY idx_warranty_evidence_verification (warranty_case_id, purpose, verification_status);
