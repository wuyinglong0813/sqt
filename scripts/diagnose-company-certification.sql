-- Read-only diagnostics. Run in the database used by the identity service.
-- This script does not reset certification or grant membership.
SELECT c.id AS company_id, c.name, c.certification_status,
       ci.applicant_user_id, ci.local_status, ci.binding_status,
       ci.ident_status, ci.failure_reason, ci.last_sync_at
FROM company c
LEFT JOIN fadada_corp_identity ci ON ci.company_id = c.id
WHERE c.certification_status <> 'VERIFIED'
ORDER BY c.created_at DESC
LIMIT 50;

-- Replace these NULLs with the two company IDs from the result above.
SET @test_company_id_1 = NULL;
SET @test_company_id_2 = NULL;

SELECT c.id AS company_id, c.name,
       ci.applicant_user_id, ci.client_corp_id, ci.open_corp_id,
       ci.local_status AS company_identity_status, ci.failure_reason,
       ui.user_id, ui.client_user_id, ui.open_user_id,
       ui.local_status AS personal_identity_status,
       ci.operator_type AS last_verified_operator_type,
       ci.operator_id AS last_verified_operator_id,
       cm.status AS membership_status, cm.role_code, cm.role_codes
FROM company c
LEFT JOIN fadada_corp_identity ci ON ci.company_id = c.id
LEFT JOIN fadada_user_identity ui ON ui.user_id = ci.applicant_user_id
LEFT JOIN company_member cm
       ON cm.company_id = c.id AND cm.user_id = ci.applicant_user_id
WHERE c.id IN (@test_company_id_1, @test_company_id_2);

-- IMPORTANT: operator_id/type are currently saved only AFTER successful matching.
-- NULL or an old value here does not prove what the provider returned on a failed sync.
-- Compare a fresh provider getIdentity response to the applicant open_user_id before
-- changing any account mapping. Do not overwrite IDs or assign LEGAL based on names.

SELECT company_id, applicant_user_id, provider_request_id, status,
       review_reason, submitted_at, reviewed_at
FROM company_certification_application
WHERE company_id IN (@test_company_id_1, @test_company_id_2)
ORDER BY created_at DESC;
