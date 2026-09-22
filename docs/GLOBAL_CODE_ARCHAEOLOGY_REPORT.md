# Phase 2: Global Code Archaeology & Competitive Architecture Extraction

## 1. Executive Summary
This report presents the findings of a deep architectural analysis of leading global open-source SMS and conversational systems (Jasmin, RapidPro, Rasa, Gammu, Kannel). The goal is to extract proven engineering patterns, identify gaps in the current `station-Abo_Ahmed2` architecture, and propose a target design that integrates AI safely and efficiently within an offline-first Android environment.

## 2. Projects Analyzed & Licensing
| Project | URL | License | Applicability |
|---------|-----|---------|---------------|
| Jasmin | [jookies/jasmin](https://github.com/jookies/jasmin) | Apache 2.0 | Architectural concepts only (Routing, Failover). Code reuse restricted. |
| RapidPro | [rapidpro/rapidpro](https://github.com/rapidpro/rapidpro) | AGPL 3.0 | Strict architectural concepts only (Flows, State). No code reuse. |
| Rasa | [RasaHQ/rasa](https://github.com/RasaHQ/rasa) | Apache 2.0 | Architectural concepts (NLU/Execution separation). Code reuse restricted. |
| Kannel | [markjeee/kannel](https://github.com/markjeee/kannel) | Kannel License | Architectural concepts (DLR, Retry limits). Code reuse restricted. |

## 3. Competitive Evidence Matrix (Key Patterns)

| Feature | Project | File | Class/Function | Evidence | Our Equivalent | Gap |
|---------|---------|------|----------------|----------|----------------|-----|
| **Failover Routing** | Jasmin | `jasmin/routing/Routes.py` | `class FailoverRoute`, `def getConnector()` | [CODE VERIFIED] Iterates through a predefined list of connectors when one fails. | `SmsAiResourceOrchestrator` | Current orchestrator lacks a robust, configurable fallback chain with cooldowns. |
| **Retry & Backoff** | Kannel | `gw/bb_smscconn.c` | `sms_resend_retry`, `bb_smscconn_send_failed` | [CODE VERIFIED] Checks `sms_resend_retry` limit and increments `resend_try`. | None / Ad-hoc | No centralized retry limit or backoff strategy for failed SMS/AI calls. |
| **Stateful Flows** | RapidPro | `temba/flows/models.py` | `class Flow`, `class FlowRun` | [CODE VERIFIED] Separates Flow definition from the runtime execution (Run). | `SmsCognitiveConversationEngine` | Current engine is tightly coupled and lacks a formal state machine for multi-turn conversations. |
| **NLU / Execution Separation** | Rasa | `rasa/core/` (General Arch) | NLU pipeline vs Action execution | [DOCUMENTATION VERIFIED] Strict separation; NLU only outputs intents/entities. | `SmsProcessor` | `SmsProcessor` mixes AI understanding, validation, and database execution in one flow. |

## 4. Gap Analysis & Critical Deficiencies
1. **Coupled Execution (Critical):** `SmsProcessor.kt` handles everything from receiving the message to executing the database command. It must be refactored to enforce a strict pipeline: `Normalization -> Intent -> Validation -> Execution`.
2. **Conversation State (High):** The current `SmsCognitiveConversationEngine` attempts to handle context, but lacks a formal State Machine. It needs structured states (`NEW`, `NEEDS_CONTEXT`, `CONFIRMATION_REQUIRED`, `EXECUTING`).
3. **Idempotency (High):** While database idempotency was introduced in Phase 1, the SMS layer itself lacks a correlation ID and deduplication queue (Inbox/Outbox pattern).
4. **Provider Failover (Medium):** AI provider selection needs a deterministic `Provider Registry` with health checks, rather than simple try-catch blocks.

## 5. Target Architecture Design
The new architecture will implement a unidirectional, pipeline-based flow:

```text
[SMS_RECEIVED]
      ↓
[INBOX QUEUE] (Deduplication & Correlation)
      ↓
[NORMALIZATION] (Arabic & Unicode)
      ↓
[INTENT DETECTION] (Deterministic First)
      ↓
[AI GATEWAY] (If needed, via Provider Registry with Failover)
      ↓
[AI QUALITY GATE] (Schema, Semantic, Business Validation)
      ↓
[CONVERSATION STATE MACHINE] (Context Resolution)
      ↓
[SEMANTIC COMMAND BUS] (Database Execution via Idempotency)
      ↓
[OUTBOX QUEUE] (Retry & DLR Tracking)
      ↓
[SMS_REPLY]
```

## 6. Implementation Roadmap
- **Phase 3:** Target Architecture & Migration Plan (Completed with this report).
- **Phase 4:** Message Contracts, Normalization, and Inbox/Outbox Queues.
- **Phase 5:** AI Quality Gate & Command Safety.
- **Phase 6:** Arabic Conversation State Machine.
- **Phase 7:** Provider Management & Reliability.
- **Phase 8:** Observability & Tracing.
- **Phase 9:** Final Verification.

## 7. Acceptance Criteria for Next Phases
- No direct database writes occur inside `SmsProcessor`.
- Every AI response passes through the `SmsAiUnderstandingValidator` before execution.
- Failed SMS or AI requests are queued and retried based on a configurable policy.
- Multi-turn Arabic conversations (e.g., "أرسل 20 دبة", "كم السعر؟", "أكد") are handled as a single stateful session.
