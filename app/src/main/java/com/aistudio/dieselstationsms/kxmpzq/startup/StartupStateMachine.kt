package com.aistudio.dieselstationsms.kxmpzq.startup

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * ═══════════════════════════════════════════════════════════════
 * آلة حالات بدء التشغيل - StartupStateMachine
 * ═══════════════════════════════════════════════════════════════
 *
 * المسؤوليات:
 * 1. حفظ الحالة الحالية لعملية Startup.
 * 2. فرض انتقالات الحالة المسموح بها فقط.
 * 3. منع الانتقالات المتعارضة عند التنفيذ المتوازي.
 * 4. إخطار المستمعين بتغير الحالة.
 * 5. حماية عملية الانتقال من أخطاء المستمعين.
 *
 * دورة التشغيل الأساسية:
 *
 * IDLE
 *   ↓
 * PREPARING
 *   ↓
 * RUNNING
 *   ├──→ PAUSED
 *   │      ↓
 *   │    RUNNING
 *   │
 *   ├──→ COMPLETED
 *   ├──→ FAILED
 *   └──→ CANCELLED
 *
 * وبعد انتهاء الدورة:
 *
 * COMPLETED / FAILED / CANCELLED
 *              ↓
 *             IDLE
 */
class StartupStateMachine {

    companion object {
        private const val TAG = "StartupStateMachine"
    }

    /**
     * الحالات الممكنة لمنظومة Startup.
     */
    enum class State {
        IDLE,
        PREPARING,
        RUNNING,
        PAUSED,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    /**
     * خريطة الانتقالات المسموح بها.
     *
     * يمنع هذا التعريف الانتقال العشوائي بين الحالات.
     */
    private val allowedTransitions: Map<State, Set<State>> = mapOf(
        State.IDLE to setOf(
            State.PREPARING
        ),

        State.PREPARING to setOf(
            State.RUNNING,
            State.FAILED,
            State.CANCELLED
        ),

        State.RUNNING to setOf(
            State.PAUSED,
            State.COMPLETED,
            State.FAILED,
            State.CANCELLED
        ),

        State.PAUSED to setOf(
            State.RUNNING,
            State.CANCELLED
        ),

        State.COMPLETED to setOf(
            State.IDLE
        ),

        State.FAILED to setOf(
            State.IDLE
        ),

        State.CANCELLED to setOf(
            State.IDLE
        )
    )

    /**
     * الحالة الحالية.
     *
     * AtomicReference تسمح بقراءة الحالة بأمان من أكثر من Thread.
     */
    private val currentState = AtomicReference(State.IDLE)

    /**
     * قائمة المستمعين.
     *
     * CopyOnWriteArrayList مناسبة هنا لأن:
     * - القراءة والإخطار أكثر شيوعًا من التعديل.
     * - يمكن إضافة/إزالة listeners أثناء وجود عمليات أخرى.
     * - لا يحدث ConcurrentModificationException أثناء الإخطار.
     */
    private val listeners =
        CopyOnWriteArrayList<(State, State) -> Unit>()

    /**
     * يمنع تنفيذ انتقالين متزامنين في الوقت نفسه.
     *
     * مهم خصوصًا لأن Startup قد يبدأ من أكثر من مصدر:
     * Boot / Alarm / Manual / Recovery وغيرها.
     */
    private val mutex = Mutex()

    /**
     * تنفيذ انتقال حالة بشكل ذري وآمن.
     *
     * @return true إذا تم الانتقال بنجاح.
     * @return false إذا كان الانتقال غير مسموح به.
     */
    suspend fun transition(to: State): Boolean {
        return mutex.withLock {
            val from = currentState.get()

            /*
             * لا نسمح بانتقال إلى الحالة نفسها.
             *
             * الحالات معرفة كآلة انتقال وليست كعملية إعادة تعيين
             * للحالة الحالية.
             */
            if (from == to) {
                Log.d(
                    TAG,
                    "Ignoring redundant transition: $from -> $to"
                )
                return@withLock false
            }

            val allowed =
                allowedTransitions[from] ?: emptySet()

            if (to !in allowed) {
                Log.w(
                    TAG,
                    "Invalid state transition: $from -> $to"
                )
                return@withLock false
            }

            /*
             * تحديث الحالة قبل إخطار المستمعين.
             *
             * بهذا يصبح getCurrentState() متوافقًا مع الحدث
             * الذي سيستقبله المستمعون.
             */
            currentState.set(to)

            notifyListenersSafely(from, to)

            Log.d(
                TAG,
                "State transition: $from -> $to"
            )

            true
        }
    }

    /**
     * إرجاع الحالة الحالية.
     */
    fun getCurrentState(): State {
        return currentState.get()
    }

    /**
     * التحقق من إمكانية الانتقال إلى حالة معينة.
     *
     * هذه الدالة لا تنفذ الانتقال.
     */
    fun canTransitionTo(to: State): Boolean {
        val from = currentState.get()

        if (from == to) {
            return false
        }

        return allowedTransitions[from]
            ?.contains(to)
            ?: false
    }

    /**
     * إضافة مستمع لتغيرات الحالة.
     */
    fun addListener(
        listener: (State, State) -> Unit
    ) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    /**
     * إزالة مستمع.
     */
    fun removeListener(
        listener: (State, State) -> Unit
    ) {
        listeners.remove(listener)
    }

    /**
     * إزالة جميع المستمعين.
     *
     * مفيدة أثناء تنظيف دورة حياة المكونات التي تعتمد
     * على StartupStateMachine.
     */
    fun clearListeners() {
        listeners.clear()
    }

    /**
     * إرجاع عدد المستمعين الحاليين.
     *
     * مفيدة للتشخيص والاختبارات.
     */
    fun listenerCount(): Int {
        return listeners.size
    }

    /**
     * إخطار جميع المستمعين مع عزل أخطاء كل Listener.
     *
     * خطأ Listener واحد يجب ألا يؤدي إلى فشل عملية Startup
     * أو يمنع بقية المستمعين من استقبال الحدث.
     */
    private fun notifyListenersSafely(
        from: State,
        to: State
    ) {
        listeners.forEach { listener ->
            try {
                listener(from, to)
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "State listener failed: $from -> $to",
                    e
                )
            }
        }
    }
}