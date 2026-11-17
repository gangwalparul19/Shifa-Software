-- Cash is the deliberate no-account exception to the salesperson/portal
-- receiving-account rule. V115 normalised all methods to requires_account=TRUE;
-- restore the stable CASH method semantics without editing V115.

UPDATE payment_methods
SET requires_account = FALSE,
    linked_account_type = NULL
WHERE code = 'CASH';
