# 75 - GST and HSN master: suggest the rate and HSN while the user types (frontend guide)

Scope: the shop's GST rate and HSN code for a product name come from the backend. The product form, the purchase form and the "new item from a bill" sheet ask `GET /api/v1/gst/suggest` while the user types a name and fill the GST % and HSN fields. The app bundles no GST data. Also: the read-only review list (`/gst/hsn`), the rate card (`/gst/rates`) and the owner's edit screens (`/admin/gst-master/rows`).

Conventions as in `00-overview.md` (Bearer token, camelCase, error body `{code, message, traceId, details}`, validation errors in `details.fields`). Rates are JSON numbers in percent: `5`, `2.5`, `0.25` (never `5.000`).

**Status.** Built and tested against the delivered data (564 HSN rows, 5,950 item names, 12 rate-card entries). Two parts are **not in this release**:

- *Server-side fill of a blank HSN / GST %* (section 6): available after the wire-in release. Until then the app must send what the user sees.
- *Rates 40, 1.5 and 0.25 in billing*: the master suggests them but marks them `acceptedForBilling: false` (section 4).

## Who can do what

| Call | ADMIN | CASHIER | Customer | No token |
|---|---|---|---|---|
| `GET /gst/suggest`, `/gst/hsn`, `/gst/hsn/{hsn}`, `/gst/rates` | yes | yes | 403 `FORBIDDEN` | 401 `UNAUTHENTICATED` |
| `POST /admin/gst-master/rows`, `PUT` / `DELETE /admin/gst-master/rows/{rowId}` | yes | 403 | 403 | 401 |

No `Idempotency-Key` on any of these.

## 1. Screen flow: product form, purchase form, new-item sheet

1. The user types the item name. **Debounce 300 ms**, and drop a response that is older than the latest request (the user kept typing). Do not call for fewer than 2 characters. A name longer than 120 characters is a 400: cut it.
2. Call `GET /api/v1/gst/suggest?name=<name>` and add what you know: `category=<category name>` (the text, not the id), `price=<selling price or MRP in rupees>` (needed for clothing and footwear price bands), `packed=true|false` only when the user has chosen "Packed" / "Loose". Leave `packed` out otherwise: the server reads words such as "loose" from the name.
3. `best == null`: do nothing. Leave GST % and HSN exactly as they are. (Junk and pack sizes such as `500 gm` give `best: null`.)
4. `best != null`: fill GST % (`best.gstPercent`) and HSN (`best.hsn`) **only into fields the user has not typed or edited**. **Typed values always win**: once the user touches a field, never overwrite it again for that form, even if the name changes. A `0` the user typed is a value, not "blank".
5. Show a one-line hint under the fields: `Matched: <best.matchedName>` plus a "Change" link. The match is a guess; `matchedName` tells the user which item it thought they meant. Show `best.description` in the HSN field's help text.
6. `best.verification == "VERIFY_PRODUCT"`: also show **"Please confirm this rate with your CA"** (the rate in the master is not checked against the official notification). `VERIFIED`: no note.
7. `choices` (not empty): the same HSN under other conditions, for example "Packed and labelled 5 %" and "Loose 0 %". Show a two-way selector with `best` and the `choices`, labelled with `conditionLabel` (fall back to `condition`). Picking one replaces GST % (and keeps the HSN, which is the same). Rows with `priceMin` / `priceMax` (clothes, footwear) mean the rate depends on the price per piece: if the price field is empty, ask the user to enter it, then call again with `price`.
8. `alternatives` (not empty): other likely HSN codes, best first. Show them behind "Other matches" (`matchedName`, `hsn`, `gstPercent`). Picking one fills both fields.
9. `best.acceptedForBilling == false`: the rate is real but billing cannot store it yet (today 40, 1.5 and 0.25). Show the suggestion greyed with "This rate cannot be billed yet, ask the owner" and **do not fill GST %** (you may fill HSN). The save would be refused with 400 anyway.
10. Network or 5xx error: stay silent and keep the fields as typed. The suggestion is a convenience, never a blocker. 403 means the user is not staff: hide the feature.

Where each screen calls it:

| Screen | When | Fields filled |
|---|---|---|
| Product form (POS attributes, `PUT /admin/products/{id}/pos`) | name or category changes | GST %, HSN |
| Purchase form, each line | after the product is chosen or a new item name is typed | line GST %, line HSN |
| New-item-from-bill sheet | name field | GST %, HSN of the new item |

For an existing product, the purchase lookup (`GET /admin/purchases/lookup`) will return the filled values by itself after the wire-in release (section 6); do not call `suggest` for a product that already has a configured rate.

## 2. `GET /api/v1/gst/suggest`

Query: `name` (required, 1 to 120 characters), `category` (optional), `price` (optional, rupees, 0 to 10,000,000), `packed` (optional boolean), `limit` (optional, 1 to 10, default 5; the most hits in total, best plus alternatives). No `ETag` on this call.

```json
{
  "query": "basmati rice 5 kg",
  "best": {
    "rowId": "6b8c...",
    "hsn": "1006",
    "chapter": "10",
    "description": "Rice",
    "tariffDescription": "Rice",
    "productDescription": "Rice",
    "nameGu": "",
    "classificationConditions": "",
    "gstPercent": 5,
    "igstRate": 5, "cgstRate": 2.5, "sgstRate": 2.5, "cessRate": 0,
    "taxType": "TAXABLE",
    "condition": "PACKED_LABELLED",
    "conditionLabel": "Packed and labelled",
    "priceMin": null, "priceMax": null,
    "acceptedForBilling": true,
    "matchedName": "Basmati Rice",
    "score": 0.92,
    "verification": "VERIFIED",
    "schedule": "", "notificationReference": "", "sourceReference": "09/2025-Central Tax (Rate), Schedule I",
    "effectiveFrom": "2025-09-22",
    "note": "",
    "active": true,
    "updatedAt": "2026-11-10T10:00:00Z"
  },
  "choices": [ { "condition": "LOOSE", "conditionLabel": "Loose", "gstPercent": 0, "...": "same shape as best" } ],
  "alternatives": []
}
```

Rules the app can rely on:

- `best` is `null` (and `choices`, `alternatives` empty) when nothing scores 0.5 or more. `score` is 0 to 1 (two decimals); a match below 0.5 is never returned.
- `choices` never contains `best`; it lists the **other conditions of the same HSN** whose rate differs. `alternatives` are other HSN codes (one per code, each with `score >= 0.5`, none equal to `best.hsn`), at most `limit - 1`.
- `condition` is an **open string**. The keys that drive logic are `ANY`, `PACKED_LABELLED`, `LOOSE`, `PRICE_UPTO_2500`, `PRICE_ABOVE_2500`; about 80 one-off keys (`FRESH`, `DRIED`, `PANEER`, `BREAD`, ...) also exist. Always show `conditionLabel`; fall back to `condition`.
- `taxType`: `TAXABLE`, `NIL`, `EXEMPT` or `NON_GST` (never null). `description` is never empty.
- With a price, the band is chosen on the server: `t shirt` with `price=2500` is `PRICE_UPTO_2500` (5 %), with `price=2500.01` it is `PRICE_ABOVE_2500` (18 %).
- `packed=false` gives the `LOOSE` row as `best` (for example rice 0 %), `packed=true` the `PACKED_LABELLED` row; without `packed` the server decides from the name (words such as loose, packed) and otherwise returns packed as `best` with loose in `choices`.
- Matching ignores case and pack sizes, accepts Hindi / Gujarati spellings written in English letters (`zeera`, `doodh`, `biskut`, `daal`) and one or two typos. A Gujarati-script name matches the row's `nameGu`; the delivered data has no Gujarati script yet, so today only Latin spellings match.
- The match is a guess. `o'neil` can score 0.53 for an unrelated item. That is why the user can always overwrite, and `matchedName` is always shown.

Errors (400 `VALIDATION_FAILED`, field in `details.fields`): `name` missing, blank or over 120 characters; `limit` outside 1 to 10; `price` below 0 or above 10,000,000; `packed` not `true` / `false`. 422 `GST_MASTER_LOADING` for a few seconds after the server starts (retry after a moment).

## 3. Review list, one code, rate card

All three carry an `ETag`. Send `If-None-Match` with the last ETag; `304` with no body means "unchanged". The ETag changes after any admin edit.

`GET /api/v1/gst/hsn?q=&verification=&taxType=&page=0&size=20`

- `q`: digits are a code prefix (`1006`); text matches the product description, the heading and the item names of the code (`basmati`), with the same spelling tolerance.
- `verification`: `VERIFIED` or `VERIFY_PRODUCT`. `taxType`: `TAXABLE`, `NIL`, `EXEMPT`, `NON_GST`. `size` 1 to 50.
- Answer: `{ "items": [row], "page": 0, "size": 20, "total": 564, "totalExact": true, "hasMore": true }`. `total` is exact (`totalExact` is always `true`). A row has the same fields as `best` above; `matchedName` and `score` are `null` in a list. Without `q` the list includes switched-off rows (`active: false`) so the review page can show them; with `q` only active rows are searched.
- 400 for a bad `page`, `size`, `verification` or `taxType` (field named).

`GET /api/v1/gst/hsn/{hsn}` (4, 6 or 8 digits): `{ "hsn": "1006", "items": [row, ...] }`: all conditions and rates of the code, switched-off rows included. 404 `NOT_FOUND` for an unknown code, 400 when it is not 4, 6 or 8 digits. Use it when the user types an HSN and the app shows its rate.

`GET /api/v1/gst/rates`: `{ "items": [ { "code": "GST_5", "gstPercent": 5, "cgstRate": 2.5, "sgstRate": 2.5, "igstRate": 5, "cessRate": 0, "taxType": "TAXABLE", "acceptedForBilling": true, "note": "" } ] }`. Only fixed-percent entries (11 today). Use it to fill the GST % drop-down: show every entry, grey those with `acceptedForBilling: false`.

## 4. `acceptedForBilling`

`true` when the billing engine can store that rate on a product or a bill line. Today it is true for **0, 3, 5, 12, 18, 28** and false for **40, 1.5, 0.25** (the master knows them, billing does not yet). Every row and every rate-card entry carries it. When billing is widened (a separate release) the same field turns `true` for those rates with no app change: read the flag, do not hard-code the list.

## 5. Admin edit screens (ADMIN only)

Lets the owner correct a rate or add an item without a release. Suggested screens: a list (from `/gst/hsn`, filter "Needs CA check" = `VERIFY_PRODUCT`), a row editor, an "Add item" form.

`POST /api/v1/admin/gst-master/rows` -> 201 with the stored row. `PUT /api/v1/admin/gst-master/rows/{rowId}` -> 200 with the stored row (`rowId` is the `rowId` of a list row). `DELETE /api/v1/admin/gst-master/rows/{rowId}` -> 204, no body.

```json
{
  "hsn": "3305",
  "description": "Shampoo",
  "gstPercent": 18,
  "taxType": "TAXABLE",
  "verification": "VERIFIED",
  "condition": "ANY",
  "conditionLabel": "",
  "cessRate": 0,
  "nameGu": "",
  "notificationReference": "09/2025-Central Tax (Rate)",
  "effectiveFrom": "2025-09-22",
  "note": "",
  "keywords": ["herbal shampoo", "anti dandruff shampoo"]
}
```

| Field | Rule |
|---|---|
| `hsn` | required, 4, 6 or 8 digits |
| `description` | required, 1 to 300 characters |
| `gstPercent` | required, 0 to 100, at most 2 decimals (`0.25` is fine, `0.125` is not). CGST and SGST are set to half each |
| `taxType` | required: `TAXABLE`, `NIL`, `EXEMPT`, `NON_GST`; anything but `TAXABLE` needs `gstPercent` 0 |
| `condition` | optional, capital letters / digits / `_`, up to 40 (default `ANY`) |
| `conditionLabel`, `nameGu`, `notificationReference`, `note` | optional text (120 / 300 / 200 / 500 characters) |
| `cessRate` | optional, 0 to 100, at most 3 decimals |
| `verification` | optional `VERIFIED` / `VERIFY_PRODUCT` (default `VERIFY_PRODUCT` on create) |
| `effectiveFrom` | optional date (default today on create) |
| `keywords` | optional, up to 50 item names of up to 120 characters, **added** under this HSN. A name never gets removed by an edit, and a name that already exists under the code is skipped |

- **On `PUT`, an optional field you leave out keeps the stored value.** Send `verification: "VERIFIED"` when the CA has confirmed the rate.
- **Saving a switched-off row switches it back on** (the audit action is `ACTIVATE`).
- `DELETE` switches the row off ("deactivate"); it stays in `/gst/hsn` with `active: false` and is no longer suggested. A second `DELETE` also answers 204.
- New item names get `verification: VERIFY_PRODUCT`, so the app shows the CA note for them until the owner confirms.
- After a save, `/gst/suggest` on that server answers with the new rate immediately; any other server follows within 60 seconds.
- Every change is audit logged (who, old value, new value).

Errors:

| Status | Code | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | every wrong field is listed together in `details.fields`, for example `{"hsn": "must be 4, 6 or 8 digits", "gstPercent": "must be 0 to 100 with at most 2 decimals"}` |
| 404 | `NOT_FOUND` | unknown `rowId` on `PUT` / `DELETE` |
| 409 | `GST_HSN_IN_USE` | switching off, or changing the HSN of, the last active row of a code that item names still point to (`details.hsn`). Add another row for the code first |
| 403 / 401 | `FORBIDDEN` / `UNAUTHENTICATED` | not an admin / no token |

## 6. Server-side fill of a blank HSN / GST % (available after the wire-in release)

**Not live yet.** When it ships, the server fills a blank `hsn` or `gstPercent` in `PUT /admin/products/{id}/pos`, in purchase lines and in `GET /admin/purchases/lookup` from the master, using the product name, category and price, and returns the filled values with their source (`MASTER`, `USER`, `STORED`, `DEFAULT`) and `gstVerification`. Rules:

- Only a **blank** is filled. A value the caller sent always wins, and a typed `0` is not blank.
- The server uses a match only when `score >= 0.6` (stricter than the 0.5 shown by `suggest`, because nobody confirms it on screen). Among matches within a hair of each other, the `VERIFIED` row wins.
- If the master's rate is one billing cannot store yet (40, 1.5, 0.25), only the HSN is filled.
- The app should therefore **omit** `gstPercent` / `hsn` from the body when the user has not touched them, and show `gstVerification == "VERIFY_PRODUCT"` as "Please confirm this rate with your CA".

Until then, keep sending the values the user sees.

## 7. Dart sketches

```dart
class GstRow {
  GstRow(this.rowId, this.hsn, this.description, this.gstPercent, this.condition, this.conditionLabel,
      this.acceptedForBilling, this.matchedName, this.score, this.verification, this.priceMin, this.priceMax);
  final String rowId, hsn, description, condition, conditionLabel, verification;
  final num gstPercent; // 5, 2.5, 0.25: num, not int
  final bool acceptedForBilling;
  final String? matchedName;
  final double? score;
  final num? priceMin, priceMax;

  factory GstRow.fromJson(Map<String, dynamic> j) => GstRow(
        j['rowId'], j['hsn'], j['description'], j['gstPercent'], j['condition'],
        (j['conditionLabel'] as String).isEmpty ? j['condition'] : j['conditionLabel'],
        j['acceptedForBilling'], j['matchedName'], (j['score'] as num?)?.toDouble(),
        j['verification'], j['priceMin'], j['priceMax']);

  bool get needsCaCheck => verification == 'VERIFY_PRODUCT';
}

class GstSuggestion {
  GstSuggestion(this.best, this.choices, this.alternatives);
  final GstRow? best;
  final List<GstRow> choices, alternatives;
  factory GstSuggestion.fromJson(Map<String, dynamic> j) => GstSuggestion(
        j['best'] == null ? null : GstRow.fromJson(j['best']),
        [for (final c in j['choices']) GstRow.fromJson(c)],
        [for (final a in j['alternatives']) GstRow.fromJson(a)]);
}

/// Debounced suggest for the product, purchase and new-item forms.
class GstSuggestController {
  GstSuggestController(this.api);
  final Api api;
  Timer? _timer;
  int _seq = 0;

  void onNameChanged(String name, {String? category, num? price, bool? packed,
      required bool hsnTouched, required bool gstTouched, required void Function(GstSuggestion) onResult}) {
    _timer?.cancel();
    if (name.trim().length < 2) return;
    final mine = ++_seq;
    _timer = Timer(const Duration(milliseconds: 300), () async {
      try {
        final res = await api.get('/gst/suggest', query: {
          'name': name.trim().substring(0, name.trim().length.clamp(0, 120)),
          if (category != null) 'category': category,
          if (price != null) 'price': '$price',
          if (packed != null) 'packed': '$packed',
        });
        if (mine != _seq) return; // an older answer: the user kept typing
        onResult(GstSuggestion.fromJson(res));
      } catch (_) {
        // stay silent: the suggestion is a convenience, the fields keep what the user typed
      }
    });
  }
}

// Applying a result: typed values always win, 40 / 1.5 / 0.25 are not filled while acceptedForBilling is false.
void applySuggestion(GstSuggestion s, FormState f) {
  final b = s.best;
  if (b == null) return;
  if (!f.hsnTouched) f.hsn = b.hsn;
  if (!f.gstTouched && b.acceptedForBilling) f.gstPercent = b.gstPercent;
  f.hint = 'Matched: ${b.matchedName}';
  f.confirmWithCa = b.needsCaCheck;
}
```

ETag for the review list:

```dart
Future<List<GstRow>> loadRates(Api api, Cache cache) async {
  final res = await api.getRaw('/gst/rates', headers: {if (cache.etag != null) 'If-None-Match': cache.etag!});
  if (res.statusCode == 304) return cache.rates;
  cache.etag = res.headers['etag'];
  return cache.rates = [for (final r in res.json['items']) GstRow.fromRate(r)];
}
```

## 8. Checklist

- [ ] Debounce 300 ms, ignore stale answers, no call under 2 characters, name cut at 120.
- [ ] `category` sent as text; `price` sent when known; `packed` only after the user chose.
- [ ] `best == null` changes nothing.
- [ ] Typed (even `0`) values are never overwritten; touched flags are per form.
- [ ] `matchedName` shown; "confirm with your CA" shown for `VERIFY_PRODUCT`.
- [ ] `choices` as a two-way selector using `conditionLabel`; price-band rows ask for the price.
- [ ] `alternatives` behind "Other matches".
- [ ] `acceptedForBilling == false`: greyed, GST % not filled.
- [ ] Rates handled as `num` (`2.5`, `0.25`), never `int`.
- [ ] Errors of `suggest` ignored silently; 403 hides the feature.
- [ ] ETag / 304 on `/gst/hsn`, `/gst/hsn/{hsn}`, `/gst/rates`.
- [ ] Admin editor: all field errors shown together, 409 `GST_HSN_IN_USE` explained, "switched off" rows shown greyed.
- [ ] Server-side fill (section 6): omit untouched fields only after the wire-in release.

## Before go-live (owner and CA decisions, data is delivered as is)

The seed was not changed; the app only surfaces `verification`. These need the owner's or CA's answer, and the fix is an admin edit (section 5) or a new migration:

1. **162 of 564 rows are `VERIFY_PRODUCT`**: the rate is not checked against the notification. Spot-check at least 20 `VERIFIED` rows too.
2. **HSN 8306 has two `ANY` rows**, 18 % (bells, statuettes of base metal, photo frames) and 5 % (idols, brass murti). About 20 item names ("Brass Ganesh", "Brass Murti", "Metal Showpiece" ...) point to 8306 and the first row (18 %) wins. Both rows stay until the CA either splits the condition (for example `IDOL` vs `ANY`) or confirms one rate.
3. **Five item names point to two HSN codes**: charcoal (4402 / 9609), chocolate spread (1806 / 2007), gumboots (6401 / 6403), rasgulla tin (1901 / 210690), vacuum bag (3920 / 8508).
4. Six `(hsn, condition)` pairs repeat in the data (0401, 8301, 8302, 8306, 9613): 564 rows, 558 distinct pairs.
5. Single Hindi words that are only a keyword of an item can tie with an item whose name equals the word (`atta`: "Chakki Atta" and "Atta Kneader"). The owner should add plain one-word items ("Atta", "Jeera") through the "Add item" form.
6. The data holds only the rates 0, 3, 5, 18 and 40; 0.25, 1.5, 12 and 28 appear only in the rate card.
7. `nameGu` is empty everywhere: Gujarati-script search works only after the owner adds Gujarati names.
