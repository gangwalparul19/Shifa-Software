/**
 * Click-to-WhatsApp helpers. Opens the WhatsApp chat for a customer with a
 * pre-filled message via a `wa.me` deep link — works from the browser and the
 * phone without any WhatsApp Business API (the courier/WhatsApp integrations
 * stay mocked). The salesperson just taps Send.
 */

/** Default country calling code prepended to bare 10-digit Indian mobiles. */
const DEFAULT_CC = '91';

/**
 * Normalises a mobile to the digits `wa.me` expects (country code + number, no
 * `+`, spaces or dashes). A bare 10-digit number is assumed to be Indian and
 * gets the `91` prefix; a number already carrying a country code is kept as-is.
 */
export function normalizeWhatsAppNumber(mobile: string | null | undefined): string | null {
  if (!mobile) {
    return null;
  }
  const digits = mobile.replace(/\D/g, '');
  if (digits.length === 10) {
    return DEFAULT_CC + digits;
  }
  // 11+ digits: assume it already includes a country code (e.g. 9198…).
  return digits.length >= 11 ? digits : null;
}

/**
 * Builds a `https://wa.me/<number>?text=<encoded>` deep link, or null when the
 * number can't be normalised. Open it in a new tab/window.
 */
export function whatsAppHref(mobile: string | null | undefined, message: string): string | null {
  const number = normalizeWhatsAppNumber(mobile);
  if (!number) {
    return null;
  }
  return `https://wa.me/${number}?text=${encodeURIComponent(message)}`;
}

/** Opens the WhatsApp chat in a new tab; returns false if the number was invalid. */
export function openWhatsApp(mobile: string | null | undefined, message: string): boolean {
  const href = whatsAppHref(mobile, message);
  if (!href) {
    return false;
  }
  window.open(href, '_blank', 'noopener');
  return true;
}

/** A one-tap WhatsApp message template. */
export interface WhatsAppTemplate {
  key: string;
  label: string;
  icon: string;
}

/** Context used to render a template's message body. */
export interface WhatsAppContext {
  customerName?: string | null;
  orderCode?: string | null;
  total?: string | number | null;
  remaining?: string | number | null;
  brand?: string;
}

const BRAND = 'Shifa Herbal Remedies';

function firstName(name: string | null | undefined): string {
  const n = (name ?? '').trim().split(/\s+/)[0];
  return n || 'there';
}

function money(v: string | number | null | undefined): string {
  const n = Number(v ?? 0);
  return '₹' + (Number.isFinite(n) ? Math.round(n).toLocaleString('en-IN') : '0');
}

/** The quick templates offered on the order / customer screens. */
export const WHATSAPP_TEMPLATES: WhatsAppTemplate[] = [
  { key: 'confirm', label: 'Confirm order', icon: 'ti-checkbox' },
  { key: 'address', label: 'Ask address', icon: 'ti-map-pin' },
  { key: 'payment', label: 'Payment reminder', icon: 'ti-cash' },
  { key: 'followup', label: 'Follow-up', icon: 'ti-message-dots' },
];

/** Renders the message body for a template key + context. */
export function whatsAppMessage(key: string, ctx: WhatsAppContext): string {
  const name = firstName(ctx.customerName);
  const brand = ctx.brand || BRAND;
  switch (key) {
    case 'confirm':
      return (
        `Hi ${name}, thank you for your order${ctx.orderCode ? ` ${ctx.orderCode}` : ''} with ${brand}. ` +
        `Order total: ${money(ctx.total)}. We'll keep you updated on dispatch. 🌿`
      );
    case 'address':
      return (
        `Hi ${name}, this is ${brand}. To ship your order, please share your full delivery address ` +
        `with PIN code and a preferred delivery time. Thank you!`
      );
    case 'payment':
      return (
        `Hi ${name}, a gentle reminder from ${brand} regarding your order` +
        `${ctx.orderCode ? ` ${ctx.orderCode}` : ''}. Balance due: ${money(ctx.remaining ?? ctx.total)}. ` +
        `You can pay via the link/UPI we shared. Thank you!`
      );
    case 'followup':
    default:
      return (
        `Hi ${name}, this is ${brand} 🌿. Just checking in — would you like to reorder your ` +
        `favourites or try something new? Happy to help with any questions.`
      );
  }
}
