# GLOBAL UI GAP MATRIX 007

| Capability | Global Project | Reference File | Our File | Current State | Gap | Value | Risk | Recommendation |
|---|---|---|---|---|---|---|---|---|
| **Dashboard Metrics** | Jasmin Web Panel | `main/web/views/home.py` | `main.html` / `DatabaseHelper.kt` | `getDashboardStats` exists and fetches real data from `sales_transactions` | **PARTIAL** (UI may still have some mock elements or static charts) | P0 | Low | Bind UI charts directly to `getDashboardStats` response. |
| **Conversation State UI** | RapidPro | `temba/flows/models.py` | `messages.html` | Displays raw messages without state badges | **GAP** | P0 | Med | Add `SmsConversationState` badges (NEW, NEEDS_CONTEXT, CONFIRMED) to UI. |
| **AI Provider Health** | Kamex | Monitoring endpoints | `ai-assistant.html` | Hidden in `SmsAiResourceOrchestrator` logs | **GAP** | P1 | Low | Expose `providerFailures` and `providerCooldowns` to a new Admin UI. |
| **SMS Queue / Retry** | Kannel | `gw/bb_http.c` | `SmsReplyManager.kt` | No UI to manage queued/failed replies | **GAP** | P1 | High | Add a "Retry" button in `messages.html` linked to a new Bridge function. |
| **Idempotency Tracking** | Kannel | `gw/bb_smscconn.c` | `DatabaseHelper.kt` | Idempotency keys exist in `financial_idempotency_keys` | **PARTIAL** | P0 | Low | Expose duplicate rejection events in the UI Activity Log. |

# Top 5 Engineering Patterns
1. **Flow State Visualization (RapidPro):** Representing a conversation as a state machine (NEW -> PROCESSING -> CONFIRMED).
2. **Circuit Breaker UI (Jasmin/Kamex):** Visually showing operators when a provider (e.g., AI) is in a cooldown state to prevent panic.
3. **Database-Backed KPIs (Jasmin Web Panel):** Aggregating counts using `COUNT` and `SUM` directly in SQL, avoiding UI-side calculations.
4. **Retry & Failover Control (Kannel):** Allowing operators to manually trigger retries for failed outbound messages.
5. **Separation of NLU and Action (Rasa):** The UI should show what the AI *understood* (Intent/Entities) separately from what the system *did* (Business Command).
