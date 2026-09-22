# GLOBAL-SMS-UI-ENGINEERING-REPORT-007

## Part 1: Global Repository Ranking & Screen Inventory
1. **RapidPro (AGPL 3.0):** Best for Conversation State UI (`flow_editor.html`, `models.py`). [CODE VERIFIED]
2. **Jasmin SMS Gateway (Apache 2.0):** Best for AI Health & Routing logic (`FailoverRoute`). [CODE VERIFIED]
3. **Jasmin Web Panel (MIT):** Best for Database-backed Dashboards (`home.py`). [CODE VERIFIED]
4. **Kannel (Kannel License):** Best for Queue/Retry status (`gw/bb_http.c`). [CODE VERIFIED]
5. **Rasa (Apache 2.0):** Best for NLU vs Action separation. [DOCUMENTATION VERIFIED]

## Part 2: Fake UI Audit
- **Dashboard (`main.html`):** The UI contains some hardcoded arrays for charts, though the backend `getDashboardStats` in `DatabaseHelper.kt` provides real SQLite data. **STATUS: PARTIAL (UI needs to bind to real data).**
- **Messages (`messages.html`):** Data is real (`loadMessages`), but state badges are missing. **STATUS: REAL but INCOMPLETE.**
- **AI Assistant (`ai-assistant.html`):** UI is mostly a chat interface. It lacks real-time health metrics of the AI providers. **STATUS: MOCK/PARTIAL.**

## Part 3: Database Compatibility Analysis
- `getDashboardStats` already exists in `DatabaseHelper.kt` and queries `sales_transactions` and `products`. No new tables needed.
- `SmsAiResourceOrchestrator.kt` holds circuit breaker state in-memory. A new API endpoint is needed in `MainActivity.kt` to expose this to the UI.
- `SmsCognitivePlan` holds the conversation state, but `messages.html` does not render it.

## Part 4: Implementation Priority (MANUS-GLOBAL-UI-IMPLEMENTATION-PLAN-007)

**🔴 DO NOT EXECUTE UNTIL APPROVED BY CHATGPT**

### TASK 001: Eradicate Fake Data in Dashboard Charts
- **Objective:** Bind `main.html` charts to `getDashboardStats`.
- **Source Pattern:** Jasmin Web Panel (`dashboard_view`).
- **Our Files:** `main.html`, `MainActivity.kt`.
- **Required Change:** Ensure JS uses the JSON from `Bridge.getDashboardStats()` to render Chart.js instead of static arrays.
- **Database Impact:** None (Query exists).
- **Priority:** P0

### TASK 002: Conversation State Visualization
- **Objective:** Show if an SMS order is `NEW`, `NEEDS_CONTEXT`, or `CONFIRMED`.
- **Source Pattern:** RapidPro Flow State.
- **Our Files:** `messages.html`, `SmsCognitivePlan.kt`.
- **Required Change:** Expose `suggestedState` to the WebView and render color-coded badges.
- **Priority:** P0

### TASK 003: AI Provider Health Center
- **Objective:** Show Circuit Breaker status for AI.
- **Source Pattern:** Kamex / Jasmin Connector Status.
- **Our Files:** `ai-assistant.html`, `MainActivity.kt`, `SmsAiResourceOrchestrator.kt`.
- **Required Change:** Create `getAiHealthStatus()` in Bridge to return `providerFailures` and `providerCooldowns`.
- **Priority:** P1
