# 72. Barcode labels, store barcodes and label printing

Audience: Flutter and web teams and the LLM assistants they use. Read `00-overview.md` first (auth, error envelope, pagination). This file is derived from the backend source (`label/**`, `pos/app/BarcodeGenerationService`, `pos/domain/Gtin`) and the integration test `LabelPrintingIT`.

Global conventions (not repeated per endpoint):

- JSON UTF-8, camelCase. Timestamps are ISO-8601 UTC instants ending in `Z`. IDs are UUID strings. Money is an integer number of **paise**. Sizes on labels are **millimetres** (decimal numbers).
- Error body for every non-2xx: `{ "code", "message", "traceId", "details" }`. Field validation errors are HTTP 400 `VALIDATION_FAILED` with `details.fields = { "<field>": "<message>" }`. A list item is named like `items[2].copies`.
- Paged lists use the project's page object `{ items, page, size, total, totalExact, hasMore }` (`size` is clamped to 1 to 50).
- Roles: **printing, history and barcode generation are open to ADMIN and CASHIER**; reading templates is open to both; creating, changing and deleting templates is **ADMIN only**. A CUSTOMER token gets 403 `FORBIDDEN`, no token 401. A "variant" is a product row (its `id` is the product id the inventory screen already has).

---

## 1. Purpose

Staff print price and barcode labels for a variant straight from the inventory screen, for example after a delivery. Every label carries a barcode that the counter scan (`GET /api/v1/pos/barcode/{code}`) understands. A variant that has no barcode gets a **store-internal EAN-13** the first time it is printed (or on demand), saved as one of its barcodes, so the printed label scans at once.

Output formats: a **PDF** (A4 sheets or one page per thermal label, for any printer through the system print dialog), **TSPL**, **ZPL** or **ESC/POS** printer commands (thermal label printers, raw bytes), or a **JSON** description so the Flutter app can draw the label itself for a Bluetooth printer.

## 2. Screen flow: "Print labels" from a variant

1. **Inventory screen, variant row or detail:** button "Print labels". Optionally "Generate barcode" when the variant shows no barcode (printing does it for you, so this is only for a screen that wants to show the code first).
2. **Sheet "Print labels":**
   - Template picker: `GET /api/v1/admin/label-templates` (remember the last one in local storage). Show name, size, and an icon for `kind`.
   - Copies: a stepper 1 to 500, plus a chip **"One per unit in stock"** which sends `copies: "fromStock"` (the server compares the word case-insensitively after trimming, but send exactly `fromStock`) (hide it when stock is 0). Show the stock number next to it. Warn "capped at 500" when stock is above 500.
   - For a `SHEET` template: a small grid (`columns` x `rows` of the template) where staff tap the first free cell: that is `startPosition` (1 = top left, counted row by row). Used for a partly used sheet.
   - Optional fields when the template has them: packed on, best before (date pickers), batch number. These are printed on every label of the request.
   - Output: PDF (default for sheets and for the system print dialog), or the printer type saved in the app settings (TSPL, ZPL, ESC/POS or "draw in the app" = JSON).
3. **Print:** `POST /api/v1/admin/labels`. For PDF show it in a viewer or hand it to the OS print dialog; for printer languages write the bytes to the printer; for JSON render locally (section 6).
4. **History:** a "Printed before" list on the variant, `GET /api/v1/admin/labels/history?variantId=...`, so staff see repeated reprints.
5. For several variants at once (a delivery), the screen builds the `items` list, one entry per variant.

## 3. Endpoints

### 3.1 Generate a store barcode

`POST /api/v1/admin/variants/{id}/barcode/generate` (ADMIN, CASHIER), no body.

```json
{ "variantId": "7d0f…", "barcode": "2900000000018", "created": true, "barcodes": ["2900000000018"] }
```

- `created: true` the code was just made; `false` the variant already had a store barcode and the same one is returned (calling again is safe).
- The code is `prefix + running number + check digit` (EAN-13, prefix from the server setting `barcode.storePrefix`, default `29`, always inside the in-store range 20 to 29). It never equals another barcode and never reads as a weighed label of an item code in use.
- 404 for an unknown variant. 400 `VALIDATION_FAILED` (`variantId`) for an item **sold by weight**: those are found by their item code (PLU), not by a barcode.

### 3.2 Barcode check digits when an admin adds a barcode

`PUT /api/v1/admin/products/{id}/pos` now rejects an **8, 12 or 13 digit** barcode (EAN-8, UPC-A, EAN-13) whose last digit is wrong:

```json
{ "code": "VALIDATION_FAILED", "message": "barcode 4006381333932 has a wrong last digit: for these digits it should be 1. Check that the code was typed or scanned correctly",
  "details": { "fields": { "barcodes": "barcode 4006381333932 has a wrong last digit: …" } } }
```

Other lengths and text codes (Code 128 such as `LBL-7F3A`) are not checked. Show the message under the barcode field. The Flutter app can check before sending with the same rule (section 7).

### 3.3 Label templates (read: ADMIN and CASHIER; write: ADMIN)

`GET /api/v1/admin/label-templates?page=0&size=50` -> page of templates, the three built-in ones first.
`GET /api/v1/admin/label-templates/{id}`, `POST /api/v1/admin/label-templates` (201), `PUT /api/v1/admin/label-templates/{id}`, `DELETE /api/v1/admin/label-templates/{id}` (204).

```json
{
  "id": "00000000-0000-0000-0000-00000000a001",
  "name": "50 x 25 mm thermal",
  "kind": "THERMAL",
  "widthMm": 50.0, "heightMm": 25.0,
  "columns": 1, "rows": 1, "gapXMm": 0.0, "gapYMm": 0.0, "marginLeftMm": 0.0, "marginTopMm": 0.0,
  "barcodeType": "EAN13",
  "fields": {
    "shopName": false, "productName": "EN", "packSize": true, "mrp": true, "price": true,
    "packedOn": false, "bestBefore": false, "batchNo": false, "pricePerUnit": false,
    "fontSize": 7, "alignment": "CENTER"
  },
  "builtIn": true
}
```

- `kind`: `THERMAL` (width x height is the label; the grid fields are fixed at 1, 1, 0, 0, 0, 0) or `SHEET` (width x height is **one cell** of an A4 page; `columns` x `rows` cells, `gapXMm`/`gapYMm` between cells, the first cell starts `marginLeftMm`/`marginTopMm` from the page corner).
- `barcodeType`: `EAN13` (default), `CODE128`, `QR`.
- Send **every key of `fields`** when creating or changing (a missing true/false or `fontSize` may be a 400 "is required" on `fields.<key>` or may default to false, depending on the JSON mapper, which is not verified: always send every key; `productName` and `alignment` may be left out: `NONE` and `CENTER`).
- `fields.productName`: `NONE` (default), `EN`, `GU` or `BOTH`. `fontSize` 4 to 24 points (0 = 7); the price line is 2 points bigger. `alignment` `LEFT`, `CENTER`, `RIGHT`.
- Built-in templates: **50 x 25 mm thermal**, **38 x 25 mm thermal**, **A4 sheet 3 x 8** (64 x 34 mm cells). They cannot be changed or deleted (409 `BUILT_IN_TEMPLATE`): show "Duplicate" and create a copy with `POST`.
- Validation (400, `details.fields`): `name` 1 to 60 characters (409 `NAME_TAKEN` when another template has it, ignoring case), `widthMm`/`heightMm` 10 to 200, `fields.fontSize`, `fields.pricePerUnit` needs `fields.price` on, `columns` 1 to 10, `rows` 1 to 30, gaps 0 to 50, margins 0 to 100, and the grid must fit an A4 page (`columns`/`rows` message "wider/taller than an A4 page"). A combination of fields that cannot fit the label height is refused with the height it needs: `fields` = "these fields need about 26.2 mm of height but a label 50 x 25 mm has 25.0 mm; use fewer fields, a smaller font or a taller label". A barcode needs about 7 mm (QR 10 mm) of height after the text lines.
- The shop name comes from the shop settings; the product name, pack size, MRP and price from the variant.

### 3.4 Print

`POST /api/v1/admin/labels` (ADMIN, CASHIER)

```json
{
  "templateId": "00000000-0000-0000-0000-00000000a003",
  "mode": "PDF",
  "startPosition": 5,
  "packedOn": "2026-11-09",
  "bestBefore": "2027-05-09",
  "batchNo": "B12",
  "items": [
    { "variantId": "7d0f…", "copies": 12 },
    { "variantId": "91ac…", "copies": "fromStock" }
  ]
}
```

| Field | Rules |
|---|---|
| `templateId` | required; 404 when unknown |
| `mode` | `PDF`, `TSPL`, `ZPL`, `ESCPOS` or `JSON`. A `SHEET` template only prints as `PDF` or `JSON` |
| `items` | 1 to 200 entries |
| `items[].copies` | a whole number 1 to 500, or the text `"fromStock"` = one label per unit in stock, capped at 500 (stock 0 is a 400 on `items[i].copies`) |
| total | at most **2000 labels** per request |
| `startPosition` | `SHEET` only, 1 to `columns x rows`, default 1 (the first cell of the first page to use) |
| `packedOn`, `bestBefore`, `batchNo` | optional; printed when the template has those fields. `bestBefore` must not be before `packedOn`. (Later the stock lots will fill them in when the request leaves them out) |

Response headers: `X-Label-Count` (labels) and `X-Label-Pages` (pages; for thermal templates it equals the number of labels), `Content-Disposition: inline; filename="labels.pdf"` (`labels.tspl`, `labels.zpl`, `labels.escpos`, `labels.json`).

| mode | Content-Type | Body |
|---|---|---|
| `PDF` | `application/pdf` | vector PDF. THERMAL: one page of the label size per label. SHEET: A4 pages, cells filled row by row from `startPosition` |
| `TSPL` | `text/plain;charset=ISO-8859-1` | TSPL2 commands, one block per variant ending with `PRINT copies,1`. **Treat as bytes**: it holds raw bitmap bytes when a label has Gujarati |
| `ZPL` | `text/plain;charset=US-ASCII` | ZPL II, one `^XA…^XZ` block per variant with `^PQ copies` |
| `ESCPOS` | `application/octet-stream` | binary ESC/POS: text, native barcode, Gujarati as raster, feed and partial cut after each label |
| `JSON` | `application/json` | the description below |

A variant without a barcode gets a store-internal EAN-13 first (also when the template is `CODE128` or `QR` and the variant has no barcode). With an `EAN13` template the variant's own valid 13-digit barcode is used; if it has only other codes a store EAN-13 is added and printed.

Errors (400 `VALIDATION_FAILED` unless noted): `items[i].copies` bad value, `items` empty / over 200 / over 2000 labels in total, `items[i].variantId` unknown or an item sold by weight (no pack barcode), `mode` not allowed for the template, `startPosition`, `bestBefore`, `templateId` ("the label for X does not fit the template: it needs N mm of height and has M mm"), and the Gujarati message in section 5. 404 for an unknown template.

#### JSON mode

```json
{
  "template": { "id": "…", "name": "50 x 25 mm thermal", "kind": "THERMAL", "widthMm": 50.0, "heightMm": 25.0, "barcodeType": "EAN13" },
  "dpi": 203,
  "totalLabels": 2,
  "items": [
    {
      "variantId": "7d0f…", "name": "Basmati Rice Premium 500 g", "copies": 2,
      "barcode": { "type": "EAN13", "payload": "2900000000018" },
      "elements": [
        { "type": "text", "xMm": 1.5, "yMm": 1.5, "widthMm": 47.0, "heightMm": 2.96, "text": "Basmati Rice Premium", "fontPt": 7.0, "bold": true, "align": "CENTER", "gujarati": false },
        { "type": "text", "xMm": 1.5, "yMm": 4.46, "widthMm": 47.0, "heightMm": 2.96, "text": "500 g", "fontPt": 7.0, "bold": false, "align": "CENTER", "gujarati": false },
        { "type": "text", "xMm": 1.5, "yMm": 7.4, "widthMm": 47.0, "heightMm": 2.96, "text": "MRP Rs 65.00", "fontPt": 7.0, "bold": false, "align": "CENTER", "gujarati": false },
        { "type": "text", "xMm": 1.5, "yMm": 10.4, "widthMm": 47.0, "heightMm": 3.7, "text": "Rs 59.00", "fontPt": 9.0, "bold": true, "align": "CENTER", "gujarati": false },
        { "type": "barcode", "xMm": 7.0, "yMm": 14.6, "widthMm": 36.0, "heightMm": 8.9, "barcodeType": "EAN13", "payload": "2900000000018", "humanReadable": true }
      ]
    }
  ]
}
```

- Positions are millimetres from the label's top-left corner. A `text` box is `x, y, width, height`: draw the text with the given `fontPt`, `bold` and `align` inside it. A `barcode` box includes the digits printed under the bars when `humanReadable` is true (the bottom 2.7 mm).
- `copies` is how many identical labels to print for that item. Long names are already shortened with `..`.
- For a `SHEET` template the response also has `sheet`: `{ pageWidthMm: 210, pageHeightMm: 297, columns, rows, startPosition, pages, placements: [{ item, page, column, row, xMm, yMm }] }`; `placements` has one entry per label in order, `item` is the index in `items`, `xMm/yMm` the label's corner on its page.
- Prices are formatted as `Rs 59.00` (the rupee sign is missing from printer fonts). The same text rule is used in every mode.

### 3.5 History

`GET /api/v1/admin/labels/history?variantId={optional}&page=0&size=20` (ADMIN, CASHIER), newest first.

```json
{ "items": [ { "id": "…", "printedAt": "2026-11-09T10:15:30Z", "actorId": "…", "actorName": "Asha", "templateId": "…",
               "templateName": "50 x 25 mm thermal", "variantId": "…", "variantName": "Basmati Rice Premium 500 g", "copies": 12, "mode": "PDF" } ],
  "page": 0, "size": 20, "total": 1, "totalExact": true, "hasMore": false }
```

One row per variant per print request. `actorName` is `null` when the account has no name. Template and variant names are copies from the moment of printing. The audit log also gets an entry `PRINT` / `label`.

## 4. Printer notes

- **A4 sheet printer or any printer through the OS:** use PDF. Print at 100 percent, no "fit to page". A partly used sheet: send `startPosition`.
- **Label printers (TSC, many Chinese brands): TSPL.** **Zebra and compatibles: ZPL.** Send the response bytes to the printer unchanged (USB, network port 9100, or Bluetooth serial). The backend uses `label.dpi` (default 203; 300 for the finer printers, an admin/server setting) to turn millimetres into dots. TSPL gets `SIZE w mm,h mm`, `GAP 2 mm,0 mm`: if the roll has another gap or black marks, adjust `GAP`/`BLINE` in the app before sending (replace the line, the rest stays valid).
- **Receipt-width printers (58 or 80 mm): ESC/POS.** Use a THERMAL template at most as wide as the paper (58 mm paper prints about 48 mm; use the 38 x 25 template or a custom 48 mm one). Each label is cut after printing.
- **Bluetooth printers driven by the app:** ask for `JSON` and draw the elements with the printer SDK's text, barcode and image commands. Convert millimetres to dots with `dots = mm * dpi / 25.4`.
- Barcodes are drawn without a quiet zone of their own: the label margin is the quiet zone. Do not make the barcode wider than the template does.
- EAN-13 labels carry the digits under the bars (printer-native on TSPL, ZPL and ESC/POS, drawn in the PDF).
- Scan check: printed codes start with the store prefix (default `29`). If the counter app reports a weighed-label error for a code you just printed, report it: store barcodes are always recognised as pack barcodes first.

## 5. Gujarati names

Printer fonts have no Gujarati letters and no joined forms, so the backend **draws Gujarati lines as a small picture** with the bundled Noto Sans Gujarati font and sends that picture in the printer language (`BITMAP` for TSPL, `^GFA` for ZPL, raster `GS v 0` for ESC/POS, an embedded image in the PDF). English text stays real printer text. Template `fields.productName` `GU` or `BOTH` therefore works in every mode, and a variant without a Gujarati name just prints the English one.

Only if the server cannot draw text (a stripped JRE without font support; not expected) it answers:

- thermal modes: 400 `VALIDATION_FAILED` on `mode`: **"Gujarati names need PDF or image mode"**
- `PDF`: 400 on `mode`: "Gujarati text cannot be drawn on this server; use JSON mode and let the app draw it"

`JSON` never fails for this reason (`gujarati: true` on the text elements). Offer the user "Use PDF or draw in the app" when you see these messages. Picture lines are shrunk (down to half size) to fit the label width; use a wider label or a smaller `fontSize` when a long Gujarati name looks small.

## 6. Dart sketches

```dart
enum LabelMode { pdf, tspl, zpl, escpos, json }

Future<Response<List<int>>> printLabels(Dio dio, String templateId, List<LabelItem> items,
    {LabelMode mode = LabelMode.pdf, int? startPosition, DateTime? packedOn, DateTime? bestBefore, String? batchNo}) {
  return dio.post<List<int>>('/api/v1/admin/labels',
      data: {
        'templateId': templateId,
        'mode': mode.name.toUpperCase(),
        if (startPosition != null) 'startPosition': startPosition,
        if (packedOn != null) 'packedOn': packedOn.toIso8601String().substring(0, 10),
        if (bestBefore != null) 'bestBefore': bestBefore.toIso8601String().substring(0, 10),
        if (batchNo != null && batchNo.isNotEmpty) 'batchNo': batchNo,
        'items': items.map((i) => i.toJson()).toList(),
      },
      options: Options(responseType: ResponseType.bytes)); // always bytes: TSPL can hold raw bitmap data
}

class LabelItem {
  LabelItem(this.variantId, {this.copies, this.fromStock = false}); // copies 1..500
  final String variantId;
  final int? copies;
  final bool fromStock;
  Map<String, dynamic> toJson() => {'variantId': variantId, 'copies': fromStock ? 'fromStock' : copies};
}

// Error handling: a JSON error body comes back as bytes too.
Map<String, dynamic> errorBody(Response r) => jsonDecode(utf8.decode(r.data as List<int>));

// Draw JSON elements on a Flutter canvas (preview) or convert to printer dots.
double dots(double mm, int dpi) => mm * dpi / 25.4;

// EAN-13 / EAN-8 / UPC-A check before sending a typed barcode (same rule as the server).
bool validGtin(String c) {
  if (!RegExp(r'^(\d{8}|\d{12}|\d{13})$').hasMatch(c)) return true; // other lengths are not checked
  var sum = 0;
  for (var i = 0; i < c.length - 1; i++) {
    sum += int.parse(c[c.length - 2 - i]) * (i.isEven ? 3 : 1);
  }
  return (10 - sum % 10) % 10 == int.parse(c[c.length - 1]);
}
```

## 7. Variable-measure (weighed) barcodes: future

The backend has a reader and writer for the standard weighing-scale EAN-13 (`PP IIIII VVVVV C`: two prefix digits 20 to 29, 5-digit item code, 5-digit weight in grams or price in paise, check digit), **switched off** (`barcode.variableMeasure.enabled=false`) because the shop has no scale printer yet. Nothing changes for the apps. The counter's existing weighed label support (shop setting `weighedPrefix`, item code PLU) keeps working as before. When a scale printer arrives the setting is switched on and `GET /pos/barcode/{code}` will read those labels as well; no new endpoint is needed.

## 8. Checklist

- [ ] "Print labels" action on a variant (inventory list and detail), template picker, copies stepper, "one per unit in stock" chip.
- [ ] Sheet start-cell picker for `SHEET` templates (`startPosition`).
- [ ] Optional packed on / best before / batch inputs shown only for templates whose `fields` have them.
- [ ] PDF: show or share the bytes (`application/pdf`); printer modes: send the response bytes to the printer unchanged; use `responseType: bytes` for every mode.
- [ ] JSON mode renderer for the Bluetooth printer: text boxes, barcode box with digits, Gujarati lines as text drawn by the app.
- [ ] Remember last template and printer type per device; show `X-Label-Count` / `X-Label-Pages` after printing.
- [ ] History list per variant (`/admin/labels/history?variantId=`).
- [ ] Barcode field on the product counter screen: show the server message for a wrong check digit; offer "Generate store barcode" (`POST /admin/variants/{id}/barcode/generate`) when the variant has none.
- [ ] Template admin (ADMIN only): list, create, duplicate a built-in one, edit, delete; show `details.fields` messages (including the "needs N mm of height" message). Hide write actions for CASHIER.
- [ ] Handle 400 for Gujarati on thermal modes (offer PDF or in-app drawing) and 409 `BUILT_IN_TEMPLATE` / `NAME_TAKEN`.
