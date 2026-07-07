import { Money } from './money.model';

/**
 * A single order line. Mirrors the backend `line_items` table.
 *
 * `lineTotal = rate * quantity` and `quantity` is constrained to 1..999 by the
 * domain layer (backend Req 7.3, cart Req 2).
 */
export interface LineItem {
  id?: number;
  productId: number;
  productName: string;
  quantity: number;
  rate: Money;
  lineTotal: Money;
}
