/**
 * Monetary amounts mirror the backend's `DECIMAL(12,2)` columns.
 *
 * The domain design mandates exact decimal arithmetic with a fixed scale of 2
 * and no floating-point drift, so money is carried across the API boundary as a
 * fixed-scale decimal string (e.g. `"1499.00"`) rather than a JavaScript
 * `number`. Feature logic (payment/COD math implemented in later tasks) is
 * responsible for parsing/formatting these values with a decimal-safe helper.
 */
export type Money = string;
