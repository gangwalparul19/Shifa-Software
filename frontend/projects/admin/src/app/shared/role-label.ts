/**
 * Human-readable label for a staff role. The backend serialises roles as their
 * UPPERCASE enum name (ADMIN, PAYMENT_VERIFIER, TEAM_LEAD, …); showing those raw
 * to users looks unprofessional. This is the single mapping — used by the shell
 * user chip/menus, the Users page, and My Profile.
 */
export function roleLabel(role: string | null | undefined): string {
  switch (role) {
    case 'ADMIN':
      return 'Admin';
    case 'ACCOUNTANT':
      return 'Accountant';
    case 'SALESPERSON':
      return 'Salesperson';
    case 'TEAM_LEAD':
      return 'Team Lead';
    case 'PACKING_USER':
      return 'Packing';
    case 'PAYMENT_VERIFIER':
      return 'Payment Verifier';
    case 'CA':
      return 'CA (Accountant)';
    case 'CUSTOMER':
      return 'Customer';
    default:
      return role ? String(role) : '';
  }
}
