-- Enrich the four built-in WhatsApp templates with warmer, longer, emoji-rich copy.
-- (V44 is already applied, so we UPDATE rather than editing the seed — Flyway rule.)
-- Emoji require utf8mb4 end-to-end: the whatsapp_templates.body column is utf8mb4
-- (V44) and the JDBC connection uses characterEncoding=UTF-8 (utf8mb4 on the wire
-- with Connector/J 8). Line breaks below are literal newlines inside the string.
UPDATE whatsapp_templates
SET body = 'Hi {name}! 🌿 Thank you for your order {orderCode} with {brand}. 🙏

Your order total is {total}. We are packing it with care and will keep you posted at every step — from packing to dispatch. 📦🚚

Have a question? Just reply here, we are happy to help! 💚'
WHERE template_key = 'confirm';

UPDATE whatsapp_templates
SET body = 'Hi {name}! 🌿 This is {brand}. 😊

To ship your order quickly, please share your full delivery address:
📍 House/Flat, Area & Landmark
🏙️ City & State
📮 PIN code
🕒 Preferred delivery time

Thank you so much! 🙏'
WHERE template_key = 'address';

UPDATE whatsapp_templates
SET body = 'Hi {name}! 🌿 A gentle reminder from {brand} about your order {orderCode}. 🧾

💰 Balance due: {remaining}

You can pay easily via the UPI/link we shared. Once done, we will dispatch your order right away! 🚚✨

Thank you for choosing us. 💚'
WHERE template_key = 'payment';

UPDATE whatsapp_templates
SET body = 'Hi {name}! 🌿 This is {brand}. 😊

Just checking in to see how you are doing! Would you like to reorder your favourites or try something new from our herbal range? 🍃

We would love to help you stay healthy and happy. Reply anytime! 💚'
WHERE template_key = 'followup';
