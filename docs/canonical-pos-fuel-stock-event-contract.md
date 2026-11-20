# Canonical POS and Fuel Sale → Stock/Event Contract

**Status:** adopted for the existing Kotlin/SQLite architecture  
**Scope:** G3 / SALE-01 / SALE-02 / EVENT-02 from the V3.0 gap review

## Decision

completeSale (POS) and saveFuelSaleRecord (fuel sales) are the only canonical write paths for their respective screens. JavaScript submits the sale once and does not separately call stock-consumption or event-publication bridges. The Kotlin business layer owns inventory consumption and publishes the downstream event only after the sale has been accepted by SQLite.

This deliberately selects the review's preferred native path. Adding consumeProductStockJson, consumeFuelStockJson or publishEventJson calls from the screens would create a second mutation path and risk double deduction.

## POS path

1. pos.html sends one completeSale request and includes its operation idempotency key.
2. MainActivity.WebAppInterface.completeSale injects the authenticated station scope and delegates to DatabaseHelper.completeSale.
3. Only a successful SQLite result triggers pos_sale_completed publication.
4. The event uses consumer stock-levels and key pos-sale:<sale_id>.
5. The bridge retains the existing success/data/error envelope.

## Fuel path

1. fuel-sales.html submits save through executeFuelSalesAsync.
2. The allowlisted dispatcher invokes saveFuelSaleRecord on Dispatchers.IO.
3. The native method wraps addFuelSale and delivery-fee accounting in one SQLite transaction.
4. After that transaction commits, it publishes fuel_sale_completed to consumer stock-levels with key fuel-sale:<sale_id>. The event includes pump_id as well as shift, tank, fuel type, litres and totals.
5. SMS and other post-commit side effects remain isolated from the committed sale result.

## Non-duplication and retry rules

- Do not add JavaScript-side stock deduction or event publication.
- Do not treat the asynchronous dispatch acknowledgement (queued) as sale confirmation; the screen waits for the native callback and a real sale_id.
- Keep sale idempotency in the SQLite sale path and event idempotency in events_outbox separate but stable.
- A duplicate event delivery must be deduplicated by the outbox identity/consumer contract; consumers must not infer a new sale from a retry.
- A post-commit event publication failure is written to diagnostic logs and must never cause the UI to resubmit a committed sale automatically. Durable retry/reconciliation remains a separate outbox-operational acceptance item.

## Verification

sales-stock-event-contract-regression-test.js checks the actual screen and bridge source for:
- one canonical POS write call and no JavaScript stock/event mutation;
- POS event publication gated by successful SQLite completion and stable idempotency;
- fuel asynchronous dispatch to the native save implementation;
- the fuel sale + delivery-fee transaction boundary;
- aligned stock consumer and stable fuel event identity;
- preservation of pump identity in the fuel event payload.

This is a source/contract regression test, not a substitute for the existing Kotlin/Robolectric SQLite transaction and outbox tests. The Actions run remains the authoritative build/test result for each pushed SHA.
