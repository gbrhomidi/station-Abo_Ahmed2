# GLOBAL REPOSITORY INSPECTION

هذه الوثيقة تلخص فحص مستودعات GitHub العالمية التي سيتم استخدامها كمرجع هندسي (Benchmarks) لإعادة تصميم شاشات مشروع "محطة أبو أحمد". تم فحص المستودعات للتأكد من ملاءمتها، تراخيصها، وهيكليتها.

## 1. ERPNext (by Frappe)
- **Repository:** `frappe/erpnext`
- **Description:** Free and Open Source Enterprise Resource Planning (ERP).
- **License:** GNU General Public License v3.0 (GPL-3.0)
- **Applicable Domains:** Inventory, Accounting, CRM, Administration.
- **Architectural Notes:** 
  - Uses Frappe framework for UI (Desk).
  - Strong patterns for List Views (Pagination, Filtering, Bulk Actions).
  - Standardized Form Views (Tabs, Collapsible sections, Audit trails).
  - *Adaptation Constraint:* We will adopt the UX/UI patterns (Grid layouts, Status badges, Form grouping) but implement them natively in HTML/CSS without copying GPL code.

## 2. Odoo
- **Repository:** `odoo/odoo`
- **Description:** Open Source Apps To Grow Your Business (ERP, CRM, POS).
- **License:** GNU Lesser General Public License v3.0 (LGPL-3.0) / Odoo Enterprise Edition License.
- **Applicable Domains:** POS, Fleet, HR, Fuel/Station Operations (via Asset modules).
- **Architectural Notes:**
  - Excellent Kanban and Grid views.
  - Very strong Point of Sale (POS) touch-optimized interface.
  - Modular dashboard widgets.
  - *Adaptation Constraint:* We will extract the "Mobile-first" and "Touch-optimized" concepts for our POS and Station operations screens.

## 3. Jasmin SMS Web Panel
- **Repository:** `101t/jasmin-web-panel` (and `jookies/jasmin`)
- **Description:** Web Panel for Jasmin SMS Gateway.
- **License:** MIT License / Apache-2.0
- **Applicable Domains:** SMS / Messaging.
- **Architectural Notes:**
  - Clear separation of Users, Groups, Routes, and MT (Mobile Terminated) messaging logs.
  - Real-time queue and DLR status visualization.
  - *Adaptation Constraint:* We will adapt their log tables and routing configuration layouts for our `messages.html` and `SmsCoreDiagnostics.html`.

## 4. Rasa / Langfuse (AI & Conversational)
- **Repository:** `langfuse/langfuse`
- **Description:** Open source LLM engineering platform (Observability, Analytics).
- **License:** MIT License
- **Applicable Domains:** AI / Conversational.
- **Architectural Notes:**
  - Excellent trace visualization (Tree view of AI reasoning).
  - Model cost, latency, and health dashboards.
  - *Adaptation Constraint:* We will adapt the "Trace" and "Provider Health" UI patterns for our `ai-assistant.html` screen.

## 5. Metabase / Apache Superset
- **Repository:** `metabase/metabase`
- **Description:** Open source business intelligence and analytics.
- **License:** GNU Affero General Public License v3.0 (AGPL-3.0)
- **Applicable Domains:** Dashboard, KPI, Reports.
- **Architectural Notes:**
  - Clean, data-driven KPI cards.
  - Interactive charts and date-range filters.
  - *Adaptation Constraint:* We will adapt the layout of KPI cards and filter bars for our `main.html` and report screens, ensuring they bind directly to our SQLite outputs.

---
**Next Step:** سنقوم باستخراج أنماط الواجهات والوظائف (UI/UX Patterns) من هذه المستودعات ومطابقتها مع شاشات مشروعنا.
