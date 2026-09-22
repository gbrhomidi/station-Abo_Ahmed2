# GLOBAL-SMS-UI-ENGINEERING-REPORT

## Part 1: Global Repository Ranking & Assessment
1. **RapidPro** (AGPL 3.0): Excellent for conversational flows and state management. [CODE VERIFIED]
2. **Jasmin SMS Gateway** (Apache 2.0): Excellent for routing, failover, and message queues. [CODE VERIFIED]
3. **Jasmin Web Panel** (MIT): Good reference for SMS operational dashboards and real-time KPIs. [CODE VERIFIED]
4. **Kannel** (Kannel License): Strong reference for retry limits, DLR tracking, and low-level queue management. [CODE VERIFIED]
5. **Rasa** (Apache 2.0): Best-in-class for separating NLU (understanding) from Action Execution. [DOCUMENTATION VERIFIED]

## Part 2: Screen Inventory & Fake UI Detection (Station Abo Ahmed)
- **`messages.html`**: [PARTIAL] Contains real data fetching (`loadMessages`, `loadConversations`), but lacks advanced filtering and visual state representation (e.g., CONFIRMATION_REQUIRED).
- **`main.html` (Dashboard)**: [PARTIAL/MOCK] Contains some hardcoded charts or `Math.random()` placeholders that must be replaced with real `DatabaseHelper` aggregation queries.
- **`bad-debts.html`**: [REAL] Uses typed bridge contracts (`getBadDebtRecords`, `saveBadDebtRecord`).
- **`ai-assistant.html`**: [UNKNOWN/MOCK] Needs to be fully integrated with `SmsAiResourceOrchestrator` to show real AI health and latency.

## Part 3: Global UI Gap Matrix
| Feature | Global Benchmark | Our Project | Gap Status | Priority |
|---------|------------------|-------------|------------|----------|
| **Dashboard KPIs** | Jasmin Web Panel (Real SQL Aggregation) | `main.html` (Partial/Mock data) | **GAP** | P0 |
| **Conversation State UI** | RapidPro (Flow Builder/State UI) | `messages.html` (Flat list) | **GAP** | P0 |
| **AI Provider Health UI** | Kamex (Prometheus/Real-time) | Missing / Hidden in logs | **GAP** | P1 |
| **SMS Retry/Failover UI** | Kannel (Queue Management) | `SmsReplyManager` (No UI control) | **GAP** | P1 |

## Part 4: Architecture Recommendations
1. **Strict Data Binding:** All charts and KPIs in `main.html` MUST fetch data via a new Bridge function (e.g., `getDashboardMetrics()`) that executes real SQL `COUNT()` and `SUM()` queries in `DatabaseHelper`.
2. **Conversation Threading:** Update `messages.html` to group messages by `conversation_id` and display the current `SmsConversationState` (e.g., NEW, CONFIRMED, FAILED).
3. **AI Health Monitor:** Create a dedicated section in the Dashboard or Settings to display the Circuit Breaker status of `SmsAiResourceOrchestrator`.

---

# MANUS-GLOBAL-UI-IMPLEMENTATION-PLAN

**🔴 STRICT RULE: Do not execute these tasks until approved by ChatGPT.**

### TASK 001: Eradicate Fake Data in Dashboard
- **OBJECTIVE:** Replace all `Math.random()` or static arrays in `main.html` with real data.
- **TARGET FILES:** `app/src/main/assets/main.html`, `MainActivity.kt`, `DatabaseHelper.kt`
- **REQUIRED CHANGE:** Create `getDashboardMetrics()` in Kotlin. Return JSON with real `sms_sales`, `failed_messages`, etc. Update JS to render this JSON.
- **ACCEPTANCE CRITERIA:** No mock data remains. Dashboard accurately reflects SQLite state.

### TASK 002: Conversation State Visualization
- **OBJECTIVE:** Show the cognitive state of a conversation in the UI.
- **SOURCE PATTERN:** RapidPro Flow State.
- **TARGET FILES:** `app/src/main/assets/screens/messages.html`, `MainActivity.kt`
- **REQUIRED CHANGE:** Expose `suggestedState` from `SmsCognitivePlan` to the WebView Bridge. Add CSS badges (e.g., Yellow for `NEEDS_CONTEXT`, Green for `CONFIRMED`).
- **ACCEPTANCE CRITERIA:** Operator can visually distinguish between a completed order and one waiting for user confirmation.

### TASK 003: AI Provider Health UI
- **OBJECTIVE:** Allow operators to see if Gemini/Remote AI is failing or in cooldown.
- **SOURCE PATTERN:** Jasmin/Kamex Connector Status.
- **TARGET FILES:** `app/src/main/assets/screens/settings.html` (or new `ai-health.html`), `SmsAiResourceOrchestrator.kt`
- **REQUIRED CHANGE:** Expose `providerFailures` and `providerCooldowns` via Bridge. Render as a status table (Active, Degraded, Circuit Open).
- **ACCEPTANCE CRITERIA:** UI accurately reflects the in-memory circuit breaker state.
