package com.aistudio.dieselstationsms.kxmpzq.startup.pipeline

import java.util.ArrayDeque

/**
 * ═══════════════════════════════════════════════════════════════
 * محرك الرسم البياني غير الدوري - DagEngine
 * ═══════════════════════════════════════════════════════════════
 *
 * مسؤول عن:
 * 1. التحقق من صحة مراحل Startup Pipeline.
 * 2. التحقق من وجود جميع التبعيات.
 * 3. منع أسماء المراحل المكررة.
 * 4. منع التبعيات الذاتية.
 * 5. اكتشاف التبعيات الدائرية.
 * 6. إنشاء ترتيب تنفيذ صالح للمراحل.
 * 7. تحديد المراحل الجاهزة للتنفيذ.
 *
 * ملاحظة:
 * لا يقوم هذا المحرك بتنفيذ المراحل بنفسه؛
 * بل يحدد فقط العلاقات والترتيب المطلوب للتنفيذ.
 */
class DagEngine(
    private val phases: List<InitializationPhase>
) {

    data class DagResult(
        val isValid: Boolean,
        val executionOrder: List<String>,
        val error: String? = null
    )

    /**
     * ═══════════════════════════════════════════════════════════
     * التحقق من صحة الـ DAG وإنشاء ترتيب التنفيذ.
     * ═══════════════════════════════════════════════════════════
     */
    fun validateAndSort(): DagResult {
        if (phases.isEmpty()) {
            return DagResult(
                isValid = true,
                executionOrder = emptyList()
            )
        }

        /*
         * التحقق من أسماء المراحل قبل بناء الرسم البياني.
         *
         * الاسم هو المعرف الأساسي للمرحلة داخل الـ DAG،
         * ولذلك لا يجوز أن توجد مرحلتان تحملان الاسم نفسه.
         */
        val duplicateNames = phases
            .groupingBy { it.name }
            .eachCount()
            .filterValues { it > 1 }
            .keys

        if (duplicateNames.isNotEmpty()) {
            return DagResult(
                isValid = false,
                executionOrder = emptyList(),
                error = "Duplicate phase name(s): ${duplicateNames.joinToString(", ")}"
            )
        }

        /*
         * التحقق من أسماء فارغة أو بيضاء.
         */
        val invalidNames = phases
            .filter { it.name.isBlank() }
            .mapIndexed { index, _ -> "#${index + 1}" }

        if (invalidNames.isNotEmpty()) {
            return DagResult(
                isValid = false,
                executionOrder = emptyList(),
                error = "Invalid blank phase name(s): ${invalidNames.joinToString(", ")}"
            )
        }

        val graph = LinkedHashMap<String, MutableSet<String>>()
        val inDegree = LinkedHashMap<String, Int>()

        /*
         * تسجيل جميع العقد أولاً.
         *
         * LinkedHashMap/LinkedHashSet مستخدمة للحفاظ على ترتيب
         * تعريف المراحل، وبالتالي الحصول على سلوك deterministic.
         */
        phases.forEach { phase ->
            graph[phase.name] = LinkedHashSet()
            inDegree[phase.name] = 0
        }

        /*
         * بناء الحواف والتحقق من التبعيات.
         */
        phases.forEach { phase ->
            val uniqueDependencies = LinkedHashSet<String>()

            phase.dependencies.forEach { dependency ->
                val dep = dependency.trim()

                if (dep.isEmpty()) {
                    return DagResult(
                        isValid = false,
                        executionOrder = emptyList(),
                        error = "Blank dependency declared for phase ${phase.name}"
                    )
                }

                /*
                 * منع المرحلة من الاعتماد على نفسها.
                 */
                if (dep == phase.name) {
                    return DagResult(
                        isValid = false,
                        executionOrder = emptyList(),
                        error = "Self dependency detected: phase ${phase.name}"
                    )
                }

                /*
                 * التحقق من وجود المرحلة التي تعتمد عليها.
                 */
                if (dep !in graph) {
                    return DagResult(
                        isValid = false,
                        executionOrder = emptyList(),
                        error = "Missing dependency: $dep for phase ${phase.name}"
                    )
                }

                /*
                 * منع احتساب التبعية نفسها أكثر من مرة.
                 *
                 * تكرار dependency في القائمة الأصلية يجب ألا يؤدي
                 * إلى زيادة inDegree أكثر من مرة.
                 */
                if (uniqueDependencies.add(dep)) {
                    graph[dep]?.add(phase.name)
                    inDegree[phase.name] =
                        (inDegree[phase.name] ?: 0) + 1
                }
            }
        }

        /*
         * خوارزمية Kahn لاكتشاف الدورة وبناء ترتيب طوبولوجي.
         *
         * يتم إدخال العقد التي لا تعتمد على أي عقدة أولاً.
         */
        val queue = ArrayDeque<String>()
        val result = mutableListOf<String>()

        /*
         * استخدام ترتيب phases الأصلي لضمان deterministic execution order.
         */
        phases.forEach { phase ->
            if (inDegree[phase.name] == 0) {
                queue.addLast(phase.name)
            }
        }

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            result.add(current)

            graph[current]
                ?.forEach { neighbor ->
                    val newDegree =
                        (inDegree[neighbor] ?: 0) - 1

                    inDegree[neighbor] = newDegree

                    if (newDegree == 0) {
                        queue.addLast(neighbor)
                    }
                }
        }

        /*
         * إذا لم تتم معالجة جميع المراحل فهذا يعني وجود
         * دورة dependency cycle داخل الرسم البياني.
         */
        if (result.size != phases.size) {
            val unresolved = phases
                .map { it.name }
                .filter { it !in result }

            return DagResult(
                isValid = false,
                executionOrder = emptyList(),
                error = buildString {
                    append("Circular dependency detected")

                    if (unresolved.isNotEmpty()) {
                        append(": ")
                        append(unresolved.joinToString(", "))
                    }
                }
            )
        }

        return DagResult(
            isValid = true,
            executionOrder = result
        )
    }

    /**
     * ═══════════════════════════════════════════════════════════
     * إرجاع المراحل الجاهزة للتنفيذ.
     * ═══════════════════════════════════════════════════════════
     *
     * completed:
     * مجموعة أسماء المراحل التي اكتمل تنفيذها بنجاح أو تم تجاوزها
     * حسب منطق الـ InitializationPipeline.
     *
     * المرحلة تكون جاهزة عندما:
     * - لم يتم تنفيذها مسبقاً.
     * - جميع dependencies الخاصة بها موجودة في completed.
     */
    fun getReadyPhases(
        completed: Set<String>
    ): List<InitializationPhase> {
        if (phases.isEmpty()) {
            return emptyList()
        }

        return phases.filter { phase ->
            phase.name !in completed &&
                phase.dependencies
                    .asSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .all { it in completed }
        }
    }
}