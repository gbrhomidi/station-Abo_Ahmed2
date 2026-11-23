# Canonical POS and Fuel Sale → Stock/Event Contract

**Status:** adopted for the existing Kotlin/SQLite architecture  
**Scope:** G3 / SALE-01 / SALE-02 / EVENT-02 from the V3.0 gap review

## Decision

completeSale (POS) and saveFuelSaleRecord (fuel sales) are the only canonical write paths for their respective screens. JavaScript submits the sale once and does not separately call stock-consumption or event-publication bridges. The Kotlin business layer owns inventory consumption and publishes the downstream event only after the sale has been accepted by SQLite.

This deliberately selects the review's preferred native path. Adding consumeProductStockJson, consumeFuelStockJson or publishEventJson calls from the screens would create a second mutation path and risk double deduction.

## POS path

1. pos.html sends one completeSale request and includes its operation idempotency key.
2. MainActivity.WebAppInterface.completeSale injects the authenticated station scope and delegates to DatabaseHelper.completeSale.
3. DatabaseHelper.completeSale inserts pos_sale_completed into events_outbox before the sale transaction commits, so a committed sale and its event are atomic.
4. The event uses consumer stock-levels and key pos-sale:<sale_id>.
5. The bridge retains the existing success/data/error envelope.

## Fuel path

1. fuel-sales.html submits save through executeFuelSalesAsync.
2. The allowlisted dispatcher invokes saveFuelSaleRecord on Dispatchers.IO.
3. The native method wraps addFuelSale and delivery-fee accounting in one SQLite transaction.
4. DatabaseHelper.addFuelSale inserts fuel_sale_completed into events_outbox before its transaction commits, using key fuel-sale:<sale_id>. The event includes pump_id as well as shift, tank, fuel type, litres and totals.
5. SMS and other post-commit side effects remain isolated from the committed sale result.

## Non-duplication and retry rules

- Do not add JavaScript-side stock deduction or event publication.
- Keep outbox publication, claiming, acknowledgement and failure transitions native-only. Do not expose these queue lifecycle operations through `@JavascriptInterface`; a future Kotlin consumer must verify the already-committed business effect before acknowledging and must never repeat the stock/tank mutation.
- Do not treat the asynchronous dispatch acknowledgement (queued) as sale confirmation; the screen waits for the native callback and a real sale_id.
- Keep sale idempotency in the SQLite sale path and event idempotency in events_outbox separate but stable.
- A duplicate event delivery must be deduplicated by the outbox identity/consumer contract; consumers must not infer a new sale from a retry.
- The outbox insert participates in the same SQLite transaction as the sale, eliminating the crash window between sale commit and event enqueue. Delivery is at-least-once; consumers must make their external effect idempotent.

## Verification

sales-stock-event-contract-regression-test.js checks the actual screen and bridge source for:
- one canonical POS write call and no JavaScript stock/event mutation;
- POS event publication gated by successful SQLite completion and stable idempotency;
- fuel asynchronous dispatch to the native save implementation;
- the fuel sale + delivery-fee transaction boundary;
- aligned stock consumer and stable fuel event identity;
- preservation of pump identity in the fuel event payload and tank identity in the durable fuel_sales row;
- additive tank_id migration and replay independence from mutable pump-to-tank configuration.

InventoryEventOutboxRobolectricTest covers station/consumer-scoped idempotency, claim/lease, acknowledgement, expired-lease crash recovery, retry/backoff, and terminal failure. The source/contract test verifies both sale paths enqueue inside their own SQLite transaction. The Actions run remains the authoritative build/test result for each pushed SHA.


## Native inventory-event consumer and recovery

The native InventoryEventOutboxWorker leases station-scoped stock-levels events and performs read-only verification before acknowledgement:

- POS: verifies the completed station/shift sale and persisted product movement for every payload line.
- Fuel: verifies the saved fuel-sale row, transaction, shift, pump, tank, fuel type, litres, unit price and total.
- Purchase receipt: verifies the receipt and PO line plus corresponding tank refill or product batch/movement.
- Unknown or incomplete event types are rejected through retry/backoff/terminal failure policy; they are never acknowledged.
- The verifier does not write inventory_levels, tank balances, fuel sales or stock movements. It only transitions the leased outbox row using its lease token.

A one-time worker is enqueued after successful POS, fuel-sale and purchase-receipt commits. MyApplication also schedules a 15-minute periodic recovery pass. The worker waits for DatabaseInitializationCoordinator.awaitDatabase before touching SQLite.
