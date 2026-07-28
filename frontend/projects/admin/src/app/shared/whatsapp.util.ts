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

/** A one-tap WhatsApp message template (fallback shape; API adds id/active/sortOrder). */
export interface WhatsAppTemplate {
  key: string;
  title: string;
  icon: string;
  body: string;
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

/**
 * The built-in default templates offered on the order / customer screens. Used
 * as a fallback when the server-managed templates (V44) haven't loaded, and by
 * the dashboard nudges. The bodies mirror the seeded rows in migration V44.
 */
// NOTE: decorate with BASIC-PLANE (3-byte) symbols only (☘ ✅ ✨ ❤ ☺ •). WhatsApp
// Desktop (Windows) click-to-chat mangles 4-byte "astral" emoji (🌿📦🚚 …) into "�"
// in the SENT message — 3-byte symbols survive the handoff (like ₹ does). Keep this
// in sync with migration V46. Managers can add any emoji via the templates editor.
export const WHATSAPP_TEMPLATES: WhatsAppTemplate[] = [
  {
    key: 'confirm',
    title: 'Confirm order',
    icon: 'ti-checkbox',
    body:
      'Hi {name}! ☘ Thank you for your order {orderCode} with {brand}. ✅\n\n' +
      'Your order total is {total}. We are packing it with care and will keep you posted at every step — from packing to dispatch. ✨\n\n' +
      'Have a question? Just reply here, we are happy to help! ❤',
  },
  {
    key: 'address',
    title: 'Ask address',
    icon: 'ti-map-pin',
    body:
      'Hi {name}! ☘ This is {brand}. ☺\n\n' +
      'To ship your order quickly, please share your full delivery address:\n' +
      '• House/Flat, Area & Landmark\n• City & State\n• PIN code\n• Preferred delivery time\n\n' +
      'Thank you so much!',
  },
  {
    key: 'payment',
    title: 'Payment reminder',
    icon: 'ti-cash',
    body:
      'Hi {name}! ☘ A gentle reminder from {brand} about your order {orderCode}.\n\n' +
      'Balance due: {remaining}\n\n' +
      'You can pay easily via the UPI/link we shared. Once done, we will dispatch your order right away! ✨\n\n' +
      'Thank you for choosing us. ❤',
  },
  {
    key: 'followup',
    title: 'Follow-up',
    icon: 'ti-message-dots',
    body:
      'Hi {name}! ☘ This is {brand}. ☺\n\n' +
      'Just checking in to see how you are doing! Would you like to reorder your favourites or try something new from our herbal range? ✨\n\n' +
      'We would love to help you stay healthy and happy. Reply anytime! ❤',
  },
];

/**
 * Renders a free-text template body by substituting {placeholder} tokens against
 * the given context. Unknown / empty tokens collapse to nothing (and any doubled
 * spaces they leave are tidied). Supported tokens: {name} (first name),
 * {customerName} (full), {orderCode}, {total}, {remaining}, {brand}.
 */
export function renderTemplate(body: string, ctx: WhatsAppContext): string {
  const map: Record<string, string> = {
    name: firstName(ctx.customerName),
    customerName: (ctx.customerName ?? '').trim() || firstName(ctx.customerName),
    orderCode: (ctx.orderCode ?? '').toString().trim(),
    total: money(ctx.total),
    remaining: money(ctx.remaining ?? ctx.total),
    brand: ctx.brand || BRAND,
  };
  return (body ?? '')
    .replace(/\{(\w+)\}/g, (_, k: string) => (k in map ? map[k] : ''))
    .replace(/[ \t]{2,}/g, ' ')
    .replace(/ +([.,!?])/g, '$1')
    .trim();
}

/** Renders the message body for a built-in template key + context (fallback / dashboard). */
export function whatsAppMessage(key: string, ctx: WhatsAppContext): string {
  // Single source of truth: render the built-in (BMP-safe) template body for the
  // key, falling back to the follow-up template. No astral emoji here either, so
  // the dashboard win-back/reorder nudges also stay clean on WhatsApp Desktop.
  const tpl =
    WHATSAPP_TEMPLATES.find((t) => t.key === key) ??
    WHATSAPP_TEMPLATES.find((t) => t.key === 'followup');
  return renderTemplate(tpl ? tpl.body : '', ctx);
}
