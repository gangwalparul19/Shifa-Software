-- Add the public self-service tracking link to the confirmation WhatsApp template.
-- Customers can enter their mobile number there to see order history and live updates.
UPDATE whatsapp_templates
SET body = 'Hi {name}! ☘ Thank you for your order {orderCode} with {brand}. ✅

{orderSummary}

We are packing it with care and will keep you posted at every step — from packing to dispatch. ✨

Have a question? Just reply here, we are happy to help! ❤

✨ Track your order anytime:
https://shifa.weblithic.online/track-order
Just enter your mobile number to view all your order history and live delivery updates. ✅'
WHERE template_key = 'confirm';
