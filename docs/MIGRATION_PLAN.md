# Migration Plan: SMS Processor Decoupling

## 1. Objective
Refactor `SmsProcessor.kt` to decouple the AI understanding, validation, and execution phases, aligning with the target architecture defined in the Global Code Archaeology Report.

## 2. Target Files for Modification
1. `app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/sms/SmsProcessor.kt`
2. `app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/sms/SmsAiUnderstandingValidator.kt`
3. `app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/sms/SmsCognitiveConversationEngine.kt`

## 3. Step-by-Step Execution Plan

### Step 1: Establish AI Quality Gate
- **Action:** Enhance `SmsAiUnderstandingValidator.kt` to enforce strict schema, semantic, and business rules.
- **Why:** To ensure that no malformed or unsafe AI output reaches the execution layer (inspired by Rasa's action validators).

### Step 2: Implement Inbox/Outbox Queues
- **Action:** Create `SmsInboxRepository` and enhance `SmsOutboxRepository` to handle deduplication and retries (inspired by Gammu and Kannel).
- **Why:** To guarantee `At Least Once + Idempotency` delivery and processing.

### Step 3: Formalize Conversation State Machine
- **Action:** Update `SmsCognitiveConversationEngine.kt` to use explicit states (`NEW`, `NEEDS_CONTEXT`, `CONFIRMATION_REQUIRED`, `EXECUTING`).
- **Why:** To handle multi-turn Arabic conversations robustly (inspired by RapidPro Flows).

### Step 4: Refactor SmsProcessor
- **Action:** Strip direct database execution and AI validation logic from `SmsProcessor.kt`. Delegate these to the newly established pipelines.
- **Why:** To achieve single-responsibility and separation of concerns.

## 4. Rollback Strategy
Since all changes will be committed incrementally, any failure during testing can be reverted using `git revert <commit_hash>` or by checking out the baseline `ac14bf7`.
