# QuikShipX API v1 — Create Order

Extracted from the client-supplied `api_custom_docs_v1.pdf` ("Quickship X Delhivery", Mohit Yadav,
2026-04-18). This is the **complete** documented surface: one operation, create-order.

## Endpoint

| | |
| --- | --- |
| Method | `POST` |
| Production URL | `https://head.quikshipx.com/api/create-order-v1` |
| Test URL | *not stated in the document* — test mode is selected by using the TEST secret, not a different URL |

### Headers

| Key | Value |
| --- | --- |
| `Content-Type` | `application/json` |

**There is no `Authorization` header.** Credentials travel in the body under `shipper_details`.

### Test vs Live

- An order booked with the **TEST** secret appears under the QuikShipX **Test** section.
- An order booked with the **LIVE** secret appears under the QuikShipX **Pending** section.
- Secrets are generated from the QuikShipX dashboard (the document illustrates this with screenshots).

## Request body

Four sections. **Every value is a JSON string**, including amounts, dimensions and quantities.

### A. `customer_details` — the recipient

| Field | Type | Description |
| --- | --- | --- |
| `customer_full_name` | String | Full name of the customer |
| `customer_phone_number` | String | Primary contact number |
| `customer_full_address` | String | Detailed delivery address (single free-text field) |
| `customer_pincode` | String | Area postal code |
| `customer_order_id` | String | Unique ID for the order from your system |
| `customer_order_date` | String | Date of the order, e.g. `"14 March 2026"` |
| `customer_address_type` | String | `1` Home, `2` Office |
| `customer_email_id` | String | Optional |
| `customer_alternate_phone_number` | String | Optional |
| `customer_landmark` | String | Nearby reference point. Optional |

### B. `shipment_details` — physical and logistical attributes

| Field | Type | Description |
| --- | --- | --- |
| `shipment_package_type` | String | `1` Flyer, `2` Cardboard |
| `shipment_dead_weight_in_grams` | String | Weight of the package in grams |
| `shipment_length`, `shipment_width`, `shipment_height` | String | Dimensions in cm |
| `shipment_shipping_mode` | String | `1` SURFACE, `2` EXPRESS |
| `shipment_pay_mode` | String | `1` COD, `2` PREPAID |
| `order_amount` | String | Original value of the product |
| `cod_amount` | String | Amount to be collected (`0` for prepaid) |
| `commodity_amount` | String | Original order amount without discounts. Refundable value if the parcel is lost |
| `shipping_amount` | String | Delivery charge levied by the seller |
| `discount_amount` | String | Discount on the commodity amount. Default `0` |
| `discount_coupon_name` | String | Optional |
| `shipment_pickup_warehouse_id` | String | Warehouse ID |

### C. `product_details` — array, one entry per item

| Field | Type | Description |
| --- | --- | --- |
| `product_name` | String | Name of the product |
| `product_category` | String | Product category name |
| `product_sku_code` | String | Stock keeping unit code |
| `product_tax_rate` | String | Applied tax percentage |
| `product_hsn_code` | String | HSN code |
| `product_amount` | String | Amount of unit product |
| `product_discount` | String | Discount on product. Default `0` |
| `product_quantity` | String | Number of units |

### D. `shipper_details` — authentication

| Field | Type | Description |
| --- | --- | --- |
| `client_code` | String | Your unique client identifier |
| `user_id` | String | Your registered user ID |
| `user_secret` | String | Your private secret key |

## Sample request

```json
{
  "customer_details": {
    "customer_full_name": "Madhav Thakur",
    "customer_phone_number": "7087694198",
    "customer_full_address": "C31 9799 GD Nagar Samrala Chowk Ludhiana",
    "customer_pincode": "141008",
    "customer_order_id": "id_12312378",
    "customer_order_date": "14 March 2026",
    "customer_address_type": "1",
    "customer_email_id": "",
    "customer_alternate_phone_number": "",
    "customer_landmark": ""
  },
  "shipment_details": {
    "shipment_package_type": "1",
    "shipment_dead_weight_in_grams": "200",
    "shipment_length": "10",
    "shipment_width": "20",
    "shipment_height": "30",
    "shipment_pickup_warehouse_id": "65",
    "shipment_shipping_mode": "1",
    "shipment_pay_mode": "2",
    "order_amount": "999",
    "cod_amount": "0",
    "commodity_amount": "999",
    "shipping_amount": "60",
    "discount_amount": "0",
    "discount_coupon_name": ""
  },
  "product_details": [
    {
      "product_name": "product1",
      "product_category": "category1",
      "product_sku_code": "sku1",
      "product_tax_rate": "0",
      "product_hsn_code": "hsn1",
      "product_amount": "999",
      "product_discount": "0",
      "product_quantity": "1"
    }
  ],
  "shipper_details": {
    "client_code": " your client code ",
    "user_id": " your user id ",
    "user_secret": "your user secret "
  }
}
```

## Not defined by this document

These gaps bound the `shopify-quikshipx-order-sync` feature. Each needs confirming with QuikShipX.

| Missing | Impact on Shifa OMS |
| --- | --- |
| Response body | Shipment identifier / AWB field names unknown. Parsing must be tolerant; a success response is treated as accepted even with no identifier returned. |
| Status webhook | Cannot push status into Shifa. Status mirroring ships disabled behind `app.quikshipx.status-feed-available`. |
| Status query endpoint | No polling fallback either, for the same reason. |
| Label endpoint | Packers download the label from the QuikShipX portal. Shifa surfaces `label_url` if the response happens to carry one. |
| Cancel endpoint | Cancelling stays a portal action. |
| Channel / source / tag field | **No field carries `SHOPIFY_API` vs `SHIFA_ADMIN`.** Shifa encodes the channel in `customer_order_id` (the QuikShipX_Order_Reference, e.g. `SHIFA-SHR-1234`). |
| Test base URL | Test mode is selected by secret, so one base URL is assumed. |

## Fields Shifa OMS did not previously hold

Supplied from admin-managed Shipment Defaults (Requirement 16), optionally overridden per product:
`shipment_pickup_warehouse_id`, `shipment_package_type`, `shipment_shipping_mode`,
`shipment_dead_weight_in_grams`, `shipment_length`, `shipment_width`, `shipment_height`,
`shipping_amount`, `product_category`.

Already held: `product_hsn_code` (`products.hsn_code`), `product_tax_rate` (`products.gst_rate`),
`discount_amount` (`orders.discount_amount`), COD amount, order total, customer and address fields.
