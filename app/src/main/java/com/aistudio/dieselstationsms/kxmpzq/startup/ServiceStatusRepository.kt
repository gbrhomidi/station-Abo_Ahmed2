package com.aistudio.dieselstationsms.kxmpzq.startup

import android.content.Context
import android.content.SharedPreferences

/**
 * ═══════════════════════════════════════════════════════════════
 * مستودع حالة خدمة SMS - ServiceStatusRepository
 * ═══════════════════════════════════════════════════════════════
 *
 * المسؤوليات:
 * 1. حفظ حالة تشغيل SMSService.
 * 2. حفظ آخر تحديث لحالة التشغيل.
 * 3. حفظ آخر Heartbeat صادر من الخدمة.
 * 4. تحديد ما إذا كانت الخدمة ما تزال حية.
 * 5. منع الاعتماد على حالة تشغيل قديمة بعد توقف الخدمة.
 * 6. توفير معلومات الحالة لمنظومة Startup / Recovery.
 *
 * ملاحظة معمارية مهمة:
 *
 * last_update و last_heartbeat لهما وظيفتان مختلفتان:
 *
 * - last_update:
 *   يمثل آخر تغيير صريح في حالة الخدمة بواسطة setRunning().
 *
 * - last_heartbeat:
 *   يمثل آخر إشارة حيوية فعلية وصلت من الخدمة نفسها.
 *
 * لذلك لا ينبغي استخدام last_update وحده لإثبات أن الخدمة
 * ما تزال تعمل.
 */
class ServiceStatusRepository(context: Context) {

    companion object {
        private const val PREFS_NAME = "service_status_prefs"

        private const val KEY_IS_RUNNING = "is_running"
        private const val KEY_LAST_UPDATE = "last_update"
        private const val KEY_LAST_HEARTBEAT = "last_heartbeat"

        /**
         * إذا لم تصل أي إشارة حيوية خلال هذه المدة، تعتبر الخدمة
         * غير حية بالنسبة لمنظومة Startup/Recovery.
         *
         * 5 دقائق متوافقة مع القيمة الأصلية في المشروع.
         */
        private const val STALE_TIMEOUT_MS = 300_000L
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    /**
     * تحديث الحالة الرسمية للخدمة.
     *
     * هذه الدالة تحافظ على API الأصلية حتى لا تتأثر الملفات
     * الأخرى التي تستدعي:
     *
     *     statusRepository.setRunning(true)
     *
     * أو:
     *
     *     statusRepository.setRunning(false)
     *
     * ملاحظة:
     * setRunning(true) لا يُعتبر Heartbeat.
     * Heartbeat له قناة مستقلة عبر recordHeartbeat().
     */
    fun setRunning(isRunning: Boolean) {
        val now = System.currentTimeMillis()

        prefs.edit()
            .putBoolean(KEY_IS_RUNNING, isRunning)
            .putLong(KEY_LAST_UPDATE, now)
            .apply()

        /*
         * عند الانتقال إلى حالة التوقف، يجب ألا يبقى Heartbeat
         * قديمًا يمكن أن يعطي انطباعًا بأن الخدمة ما زالت حية.
         *
         * لا نحذف Heartbeat عند setRunning(true)، لأن بدء الخدمة
         * لا يعني أن أول Heartbeat قد صدر بالفعل.
         */
        if (!isRunning) {
            prefs.edit()
                .remove(KEY_LAST_HEARTBEAT)
                .apply()
        }
    }

    /**
     * التحقق من حالة الخدمة.
     *
     * المنطق:
     *
     * 1. يجب أن تكون الحالة الرسمية is_running = true.
     * 2. يجب أن يكون لدينا دليل حديث على حيوية الخدمة.
     *
     * ولكن توجد مرحلة انتقالية مهمة:
     *
     * عند تشغيل الخدمة حديثًا، قد يقوم SmsServiceLauncher
     * باستدعاء setRunning(true) قبل أن تصدر SMSService أول
     * Heartbeat.
     *
     * لذلك نسمح بفترة الإقلاع الأولية بالاعتماد على last_update.
     * بعد وجود Heartbeat، يصبح Heartbeat هو الدليل الأقوى
     * على استمرار الحياة.
     */
    fun isRunning(): Boolean {
        if (!prefs.getBoolean(KEY_IS_RUNNING, false)) {
            return false
        }

        val now = System.currentTimeMillis()
        val lastHeartbeat = prefs.getLong(KEY_LAST_HEARTBEAT, 0L)

        /*
         * إذا كانت الخدمة قد بدأت حديثًا ولم يصل أول Heartbeat
         * بعد، نعتمد مؤقتًا على last_update حتى لا نمنع منظومة
         * Startup من إعطاء الخدمة فرصة لإكمال التهيئة.
         */
        if (lastHeartbeat <= 0L) {
            val lastUpdate = prefs.getLong(KEY_LAST_UPDATE, 0L)

            if (lastUpdate <= 0L) {
                return false
            }

            return now - lastUpdate <= STALE_TIMEOUT_MS
        }

        /*
         * بعد وجود Heartbeat، يجب أن تكون الإشارة الحيوية حديثة.
         */
        return now - lastHeartbeat <= STALE_TIMEOUT_MS
    }

    /**
     * تسجيل Heartbeat صادر من SMSService.
     *
     * Heartbeat لا يغير is_running مباشرة؛ فهو يثبت فقط أن
     * الخدمة ما زالت حية.
     *
     * كما نحدث last_update أيضًا للحفاظ على توافق الحالة
     * مع المكونات القديمة التي تعتمد على هذا الحقل.
     */
    fun recordHeartbeat() {
        val now = System.currentTimeMillis()

        prefs.edit()
            .putLong(KEY_LAST_HEARTBEAT, now)
            .putLong(KEY_LAST_UPDATE, now)
            .putBoolean(KEY_IS_RUNNING, true)
            .apply()
    }

    /**
     * الحصول على وقت آخر Heartbeat.
     *
     * القيمة 0 تعني أنه لم يتم تسجيل Heartbeat بعد أو تم مسحه.
     */
    fun lastHeartbeat(): Long =
        prefs.getLong(KEY_LAST_HEARTBEAT, 0L)

    /**
     * الحصول على وقت آخر تحديث لحالة الخدمة.
     *
     * هذه الدالة إضافية غير مدمرة، وتفيد منظومة التشخيص
     * وRecovery دون الحاجة للوصول المباشر إلى SharedPreferences.
     */
    fun lastUpdate(): Long =
        prefs.getLong(KEY_LAST_UPDATE, 0L)

    /**
     * معرفة ما إذا كان قد تم تسجيل Heartbeat من قبل.
     */
    fun hasHeartbeat(): Boolean =
        lastHeartbeat() > 0L

    /**
     * معرفة ما إذا كان Heartbeat ما يزال حديثًا.
     *
     * لا تعتمد هذه الدالة على is_running؛ فهي تقيس فقط
     * حيوية آخر Heartbeat.
     */
    fun isHeartbeatFresh(): Boolean {
        val heartbeat = lastHeartbeat()

        if (heartbeat <= 0L) {
            return false
        }

        return System.currentTimeMillis() - heartbeat <= STALE_TIMEOUT_MS
    }

    /**
     * الحصول على عمر آخر Heartbeat بالمللي ثانية.
     *
     * تعاد القيمة 0 إذا لم يوجد Heartbeat.
     */
    fun heartbeatAgeMs(): Long {
        val heartbeat = lastHeartbeat()

        if (heartbeat <= 0L) {
            return 0L
        }

        return (System.currentTimeMillis() - heartbeat).coerceAtLeast(0L)
    }

    /**
     * إيقاف الخدمة منطقيًا ومسح بيانات الحيوية.
     *
     * نستخدم setRunning(false) بدل تكرار منطق التخزين هنا
     * لضمان اتساق الحالة.
     */
    fun markStopped() {
        setRunning(false)
    }

    /**
     * مسح حالة الخدمة بالكامل.
     *
     * يستخدم عند الحاجة إلى إعادة تهيئة حالة Startup/Recovery
     * من الصفر.
     */
    fun clear() {
        prefs.edit()
            .clear()
            .apply()
    }
}