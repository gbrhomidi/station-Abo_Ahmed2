# V3.0 Inventory Reorder and FEFO Acceptance

## Automatic reorder draft

The active `inventory_levels` insert/update triggers evaluate the station-wide available quantity after every inventory balance mutation. If an active product has a positive configured `reorder_point` and available quantity is at or below it, SQLite inserts a `draft` purchase order and one product line in the same transaction. The order quantity replenishes to reorder point plus configured safety stock, with a minimum one-unit draft quantity.

The trigger checks for an existing draft, submitted, approved, or partial order for the same station/product before insertion. It therefore does not duplicate an active order under repeated balance updates. The PO remains a draft and does not authorize procurement, supplier payment, or receipt.

## FEFO enforcement

For batch-tracked products, the native sale resolver now selects the earliest non-expired batch with sufficient quantity. A caller-supplied batch is accepted only if it is that same FEFO batch. The subsequent decrement is conditional on batch ID, product ID, remaining quantity, and non-expired status; failure to update exactly one row aborts the enclosing sale transaction.

## Wet-stock tolerance

The wet-stock reconciliation test suite explicitly exercises a 0.009 L variance without a reason and a 0.011 L variance that must fail without a reason and notes, then pass into DRAFT with an approved reason code and notes. The existing opening/closing reading selection and full shift-window calculation remain unchanged.

## Verification boundary

These are Robolectric SQLite regression tests executed by the repository Android workflow. A source edit or a green local static check is not a substitute for the workflow result on the exact pushed commit SHA.
