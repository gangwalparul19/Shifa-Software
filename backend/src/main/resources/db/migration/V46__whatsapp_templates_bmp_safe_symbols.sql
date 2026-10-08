-- Make the default WhatsApp templates render reliably EVERYWHERE, including the
-- WhatsApp Desktop (Windows) click-to-chat handoff, which mangles 4-byte
-- "astral" emoji (🌿🙏📦🚚💚, U+1Fxxx) into the replacement char "�" — that garbled
-- text was actually being SENT to customers. 3-byte "basic-plane" symbols (like ₹,
-- which rendered fine) survive the handoff, so we decorate with those instead
-- (☘ ✅ ✨ ❤ ☺ •). Managers can still add any emoji via the templates editor.
UPDATE whatsapp_templates
SET body = 'Hi {name}! ☘ Thank you for your order {orderCode} with {brand}. ✅

Your order total is {total}. We are packing it with care and will keep you posted at every step — from packing to dispatch. ✨

Have a question? Just reply here, we are happy to help! ❤'
WHERE template_key = 'confirm';

UPDATE whatsapp_templates
SET body = 'Hi {name}! ☘ This is {brand}. ☺

To ship your order quickly, please share your full delivery address:
• House/Flat, Area & Landmark
• City & State
• PIN code
• Preferred delivery time

Thank you so much!'
WHERE template_key = 'address';

UPDATE whatsapp_templates
SET body = 'Hi {name}! ☘ A gentle reminder from {brand} about your order {orderCode}.

Balance due: {remaining}

You can pay easily via the UPI/link we shared. Once done, we will dispatch your order right away! ✨

Thank you for choosing us. ❤'
WHERE template_key = 'payment';

UPDATE whatsapp_templates
SET body = 'Hi {name}! ☘ This is {brand}. ☺

Just checking in to see how you are doing! Would you like to reorder your favourites or try something new from our herbal range? ✨

We would love to help you stay healthy and happy. Reply anytime! ❤'
WHERE template_key = 'followup';
