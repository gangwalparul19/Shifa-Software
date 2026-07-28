-- Add an itemized order summary to the "confirm" WhatsApp template so the message
-- to the customer includes WHAT was ordered, WHAT was paid, and the BALANCE due on
-- delivery (COD). {orderSummary} is rendered client-side from the order in context
-- (item list + total + paid + COD balance); it collapses to nothing when there is
-- no order in context (e.g. a customer-level message), so the template stays clean.
UPDATE whatsapp_templates
SET body = 'Hi {name}! ☘ Thank you for your order {orderCode} with {brand}. ✅

{orderSummary}

We are packing it with care and will keep you posted at every step — from packing to dispatch. ✨

Have a question? Just reply here, we are happy to help! ❤'
WHERE template_key = 'confirm';
