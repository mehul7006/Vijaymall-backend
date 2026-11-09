-- Who cancelled an order and why (a code the app translates), and a snapshot of each line's picture.
ALTER TABLE orders ADD COLUMN cancelled_by text;
ALTER TABLE orders ADD COLUMN cancel_code text;
ALTER TABLE order_item ADD COLUMN image_url text;

-- Best effort for orders cancelled before this migration.
UPDATE orders SET cancelled_by = 'SHOP', cancel_code = 'REJECTED_BY_SHOP'
 WHERE status = 'CANCELLED' AND cancel_reason ILIKE 'rejected by shop:%';

-- The cancel entry of the history tells who did it: the customer, a staff member, or nobody (the system).
UPDATE orders o SET cancelled_by = 'CUSTOMER', cancel_code = 'CUSTOMER_CANCELLED'
  FROM order_status_history h
 WHERE o.status = 'CANCELLED' AND o.cancelled_by IS NULL
   AND h.order_id = o.id AND h.to_status = 'CANCELLED' AND h.actor_id = o.user_id;

UPDATE orders o SET cancelled_by = 'SHOP', cancel_code = 'CANCELLED_BY_SHOP'
  FROM order_status_history h
 WHERE o.status = 'CANCELLED' AND o.cancelled_by IS NULL
   AND h.order_id = o.id AND h.to_status = 'CANCELLED' AND h.actor_id IS NOT NULL AND h.actor_id <> o.user_id;

UPDATE orders o SET cancelled_by = 'SYSTEM',
       cancel_code = CASE WHEN h.note = 'payment timeout' THEN 'PAYMENT_TIMEOUT' ELSE 'PAYMENT_FAILED' END
  FROM order_status_history h
 WHERE o.status = 'CANCELLED' AND o.cancelled_by IS NULL
   AND h.order_id = o.id AND h.to_status = 'CANCELLED' AND h.actor_id IS NULL
   AND h.note IN ('payment timeout', 'payment failed');

UPDATE order_item i SET image_url = p.image_url
  FROM product p
 WHERE p.id = i.product_id AND p.image_url IS NOT NULL;
