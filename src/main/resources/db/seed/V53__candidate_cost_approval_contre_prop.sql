-- V53: store the counter-proposal salary on the approval record itself so
-- simulation history can show what the DAF/CD proposed when rejecting.
ALTER TABLE candidate_cost_approvals
    ADD contre_prop_salaire DECIMAL(18,4) NULL;
