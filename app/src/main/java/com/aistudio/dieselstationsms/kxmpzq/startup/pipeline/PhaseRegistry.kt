package com.aistudio.dieselstationsms.kxmpzq.startup.pipeline

/**
 * ═══════════════════════════════════════════════════════════════
 * سجل مراحل التهيئة - PhaseRegistry
 * ═══════════════════════════════════════════════════════════════
 *
 * المسؤولية:
 *
 * 1. تسجيل InitializationPhase.
 * 2. استرجاع مرحلة بالاسم.
 * 3. التحقق من وجود مرحلة.
 * 4. توفير جميع المراحل المسجلة.
 *
 * مبدأ معماري مهم:
 * ───────────────────────────────────────────────────────────────
 *
 * PhaseRegistry مسؤول فقط عن تسجيل واكتشاف المراحل.
 *
 * لا يقوم هنا بـ:
 *
 * - تحديد ترتيب التنفيذ.
 * - تنفيذ المراحل.
 * - تحليل dependencies.
 * - إدارة retry.
 * - إدارة cancellation.
 *
 * تحديد ترتيب التنفيذ والتحقق من dependencies مسؤولية DagEngine.
 */
class PhaseRegistry {

    private val phases = LinkedHashMap<String, InitializationPhase>()

    /**
     * تسجيل مرحلة جديدة.
     *
     * يمنع التسجيل المكرر لنفس الاسم لأن اسم المرحلة يمثل
     * معرفها الفريد داخل startup pipeline.
     *
     * السماح باستبدال مرحلة موجودة بصمت قد يؤدي إلى أخطاء
     * يصعب اكتشافها، خصوصًا إذا تم تسجيل مرحلتين بنفس الاسم.
     */
    fun register(phase: InitializationPhase) {

        require(phase.name.isNotBlank()) {
            "Phase name cannot be blank"
        }

        require(!phases.containsKey(phase.name)) {
            "Phase already registered: ${phase.name}"
        }

        phases[phase.name] = phase
    }

    /**
     * استرجاع مرحلة بالاسم.
     *
     * يتم الفشل مباشرة إذا لم تكن المرحلة مسجلة.
     *
     * هذا السلوك مقصود لأن StartupPolicyFactory يعتمد على
     * وجود المراحل المطلوبة، وأي نقص في التسجيل يعتبر خطأ
     * في تهيئة startup configuration وليس حالة تشغيل عادية.
     */
    fun get(name: String): InitializationPhase {
        require(name.isNotBlank()) {
            "Phase name cannot be blank"
        }

        return phases[name]
            ?: throw IllegalArgumentException(
                "Phase not found: $name"
            )
    }

    /**
     * إرجاع جميع المراحل المسجلة.
     *
     * يتم استخدام LinkedHashMap للحفاظ على ترتيب التسجيل
     * عند الحاجة إلى الاستعراض أو التشخيص.
     *
     * لا ينبغي الاعتماد على هذا الترتيب لتنفيذ pipeline؛
     * DagEngine هو المسؤول عن ترتيب التنفيذ بناءً على
     * dependencies.
     */
    fun getAll(): List<InitializationPhase> {
        return phases.values.toList()
    }

    /**
     * التحقق من وجود مرحلة مسجلة.
     */
    fun contains(name: String): Boolean {
        return name.isNotBlank() && phases.containsKey(name)
    }

    /**
     * عدد المراحل المسجلة.
     *
     * مفيد للتشخيص والاختبارات والتحقق من اكتمال التسجيل.
     */
    fun size(): Int {
        return phases.size
    }

    /**
     * إزالة مرحلة مسجلة.
     *
     * هذه العملية مخصصة للاختبارات أو إعادة بناء registry.
     *
     * لا تُستخدم أثناء تشغيل pipeline بشكل طبيعي.
     */
    fun unregister(name: String): Boolean {
        if (name.isBlank()) return false
        return phases.remove(name) != null
    }

    /**
     * إزالة جميع المراحل.
     *
     * مخصصة للاختبارات وإعادة التهيئة فقط.
     */
    fun clear() {
        phases.clear()
    }
}