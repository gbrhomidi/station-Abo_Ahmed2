package com.aistudio.dieselstationsms.kxmpzq.startup

import java.io.IOException
import android.content.Context
import android.os.Build
import android.os.StatFs
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**

* ═══════════════════════════════════════════════════════════════

* StartupDiagnostics

* ═══════════════════════════════════════════════════════════════

* 

* طبقة تشخيص مستقلة لدورة Startup.

* 

* الهدف الأساسي:

* 

* 1. معرفة هل التطبيق كان داخل Startup عند حدوث Crash.

* 2. معرفة آخر Phase تم تنفيذها.

* 3. معرفة آخر StartupEvent.

* 4. معرفة StartupReason.

* 5. معرفة correlationId.

* 6. الاحتفاظ بسجل مختصر وآمن للأحداث الأخيرة.

* 7. حفظ تقرير تشخيص دائم يمكن قراءته من الهاتف دون ADB.

* 

* التصميم:

* 

* - لا تعتمد على EventBus.

* - لا تعتمد على Coroutine.

* - لا تعتمد على InitializationPipeline.

* - لا تطلق Exceptions إلى المستدعي.

* - جميع عمليات التشخيص Best-Effort.

* 

* وبالتالي فإن فشل التشخيص نفسه لا يجب أن يؤدي إلى Crash.
  */
  class StartupDiagnostics private constructor(
  private val context: Context
  ) {
  
  companion object {
  
   private const val TAG = "StartupDiagnostics"

 private const val DIAGNOSTICS_DIR = "startup-diagnostics"

 private const val CURRENT_STATE_FILE = "startup_current.txt"

 private const val EVENTS_FILE = "startup_events.log"

 private const val LAST_CRASH_FILE = "startup_last_crash.txt"

 private const val MAX_EVENT_LINES = 200

 private const val MAX_EVENT_FILE_BYTES = 128 * 1024L

 private const val MIN_FREE_SPACE_MB = 2L

 private val lock = ReentrantLock()

 @Volatile
 private var instance: StartupDiagnostics? = null

 /**
  * الحصول على Singleton آمن.
  *
  * لا يعتمد على MyApplication حتى يمكن استخدامه
  * مبكرًا جدًا أثناء startup.
  */
 fun getInstance(context: Context): StartupDiagnostics {
     return instance ?: synchronized(this) {
         instance ?: StartupDiagnostics(
             context.applicationContext
         ).also {
             instance = it
         }
     }
 }

 /**
  * محاولة الحصول على Singleton دون إنشاء Instance جديد.
  */
 fun peek(): StartupDiagnostics? {
     return instance
 }
  
  }
  
  /**
  
  * رقم تسلسلي للأحداث.
  * 
  * يساعد في معرفة ترتيب الأحداث حتى لو كان
  * timestamp متقاربًا جدًا.
    */
    private val sequence = AtomicLong(0L)
  
  /**
  
  * حالة Startup الحالية في الذاكرة.
    */
    @Volatile
    private var active = false
  
  @Volatile
  private var currentReason: StartupReason? = null
  
  @Volatile
  private var currentCorrelationId: String? = null
  
  @Volatile
  private var currentPhase: String? = null
  
  @Volatile
  private var lastEvent: String? = null
  
  @Volatile
  private var startedAtMs: Long = 0L
  
  private val diagnosticsDir: File
  get() = File(
  context.filesDir,
  DIAGNOSTICS_DIR
  )
  
  private val currentStateFile: File
  get() = File(
  diagnosticsDir,
  CURRENT_STATE_FILE
  )
  
  private val eventsFile: File
  get() = File(
  diagnosticsDir,
  EVENTS_FILE
  )
  
  private val lastCrashFile: File
  get() = File(
  diagnosticsDir,
  LAST_CRASH_FILE
  )
  
  /**
  
  * تهيئة نظام التشخيص.
  
  * 
  
  * يجب استدعاؤها مبكرًا من MyApplication.onCreate().
    */
    fun initialize() {
    safe {
    ensureDirectory()
    
     /*
  * إذا بقي startup_current.txt من تشغيل سابق،
  * فهذا يعني أن التطبيق ربما انتهى قبل تسجيل
  * PipelineCompleted / PipelineFailed.
  */
 if (currentStateFile.exists()) {
     val previousState = currentStateFile.readText()

     if (previousState.isNotBlank()) {
         writeEvent(
             "PREVIOUS_STARTUP_INTERRUPTED | $previousState"
         )
     }
 }

 /*
  * لا نمسح currentStateFile هنا.
  *
  * إذا بدأ Startup فعليًا سيتم استبداله بواسطة
  * markPipelineStarted().
  */
    
    }
    }
  
  /**
  
  * تسجيل بداية Pipeline.
    */
    fun markPipelineStarted(
    reason: StartupReason,
    correlationId: String
    ) {
    safe {
    
     active = true
 currentReason = reason
 currentCorrelationId = correlationId
 currentPhase = null
 startedAtMs = System.currentTimeMillis()

 val message =
     "PIPELINE_STARTED" +
         " | reason=${reason.name}" +
         " | correlationId=$correlationId"

 lastEvent = message

 persistCurrentState(
     status = "RUNNING",
     event = message
 )

 writeEvent(message)
    
    }
    }
  
  /**
  
  * تسجيل بداية Phase.
    */
    fun markPhaseStarted(
    phase: String,
    correlationId: String
    ) {
    safe {
    
     active = true
 currentPhase = phase
 currentCorrelationId = correlationId

 val message =
     "PHASE_STARTED" +
         " | phase=$phase" +
         " | correlationId=$correlationId"

 lastEvent = message

 persistCurrentState(
     status = "RUNNING_PHASE",
     event = message
 )

 writeEvent(message)
    
    }
    }
  
  /**
  
  * تسجيل نجاح Phase.
    */
    fun markPhaseCompleted(
    phase: String,
    result: String,
    correlationId: String
    ) {
    safe {
    
     currentPhase = phase
 currentCorrelationId = correlationId

 val message =
     "PHASE_COMPLETED" +
         " | phase=$phase" +
         " | result=${sanitize(result)}" +
         " | correlationId=$correlationId"

 lastEvent = message

 persistCurrentState(
     status = "RUNNING",
     event = message
 )

 writeEvent(message)
    
    }
    }
  
  /**
  
  * تسجيل تخطي Phase.
    */
    fun markPhaseSkipped(
    phase: String,
    reason: String,
    correlationId: String
    ) {
    safe {
    
     currentPhase = phase
 currentCorrelationId = correlationId

 val message =
     "PHASE_SKIPPED" +
         " | phase=$phase" +
         " | reason=${sanitize(reason)}" +
         " | correlationId=$correlationId"

 lastEvent = message

 persistCurrentState(
     status = "RUNNING",
     event = message
 )

 writeEvent(message)
    
    }
    }
  
  /**
  
  * تسجيل فشل Phase.
    */
    fun markPhaseFailed(
    phase: String,
    error: String,
    correlationId: String
    ) {
    safe {
    
     currentPhase = phase
 currentCorrelationId = correlationId

 val message =
     "PHASE_FAILED" +
         " | phase=$phase" +
         " | error=${sanitize(error)}" +
         " | correlationId=$correlationId"

 lastEvent = message

 persistCurrentState(
     status = "PHASE_FAILED",
     event = message
 )

 writeEvent(message)
    
    }
    }
  
  /**
  
  * تسجيل تغير حالة StartupStateMachine.
  
  * 
  
  * نستخدم toString() بدل افتراض أسماء محددة لقيم State
  
  * حتى لا يصبح هذا الملف مقترنًا بتعريف State الحالي.
    */
    fun markStateChanged(
    oldState: StartupStateMachine.State,
    newState: StartupStateMachine.State,
    correlationId: String
    ) {
    safe {
    
     currentCorrelationId = correlationId

 val message =
     "STATE_CHANGED" +
         " | old=${sanitize(oldState.toString())}" +
         " | new=${sanitize(newState.toString())}" +
         " | correlationId=$correlationId"

 lastEvent = message

 persistCurrentState(
     status = "STATE_CHANGED",
     event = message
 )

 writeEvent(message)
    
    }
    }
  
  /**
  
  * تسجيل إلغاء Startup.
    */
    fun markCancelled(
    correlationId: String,
    reason: String
    ) {
    safe {
    
     currentCorrelationId = correlationId

 val message =
     "PIPELINE_CANCELLED" +
         " | reason=${sanitize(reason)}" +
         " | correlationId=$correlationId"

 lastEvent = message

 persistCurrentState(
     status = "CANCELLED",
     event = message
 )

 writeEvent(message)

 active = false
    
    }
    }
  
  /**
  
  * تسجيل نجاح Pipeline.
    */
    fun markPipelineCompleted(
    durationMs: Long,
    phases: List<String>,
    correlationId: String
    ) {
    safe {
    
     currentCorrelationId = correlationId

 val message =
     "PIPELINE_COMPLETED" +
         " | durationMs=$durationMs" +
         " | phases=${phases.joinToString(",")}" +
         " | correlationId=$correlationId"

 lastEvent = message

 writeEvent(message)

 persistCurrentState(
     status = "COMPLETED",
     event = message
 )

 /*
  * نحذف حالة التشغيل الحالية بعد النجاح.
  *
  * السبب:
  * إذا بقي الملف فلن نستطيع التمييز بين
  * Startup جارٍ فعليًا وStartup انتهى بنجاح.
  */
 currentStateFile.delete()

 active = false
 currentPhase = null
 startedAtMs = 0L
    
    }
    }
  
  /**
  
  * تسجيل فشل Pipeline.
  
  * 
  
  * لا نحذف current state مباشرة، لأن التقرير يجب أن يبقى
  
  * قابلًا للاكتشاف من الهاتف.
    */
    fun markPipelineFailed(
    failedPhase: String,
    error: String,
    correlationId: String
    ) {
    safe {
    
     currentPhase = failedPhase
 currentCorrelationId = correlationId

 val message =
     "PIPELINE_FAILED" +
         " | failedPhase=$failedPhase" +
         " | error=${sanitize(error)}" +
         " | correlationId=$correlationId"

 lastEvent = message

 persistCurrentState(
     status = "FAILED",
     event = message
 )

 writeEvent(message)

 active = false
    
    }
    }
  
  /**
  
  * تسجيل Crash وقع أثناء Startup.
  
  * 
  
  * هذه الدالة مستقلة عن MyApplication Crash Handler.
    */
    fun recordCrash(
    threadName: String?,
    throwable: Throwable
    ) {
    safe {
    
     ensureDirectory()

 val now = System.currentTimeMillis()

 val report = buildCrashReport(
     now = now,
     threadName = threadName,
     throwable = throwable
 )

 lastCrashFile.writeText(report)

 writeEvent(
     "STARTUP_CRASH" +
         " | thread=${sanitize(threadName ?: "unknown")}" +
         " | exception=${throwable.javaClass.name}" +
         " | message=${sanitize(throwable.message ?: "N/A")}"
 )
    
    }
    }
  
  /**
  
  * هل Startup كان نشطًا في الذاكرة؟
    */
    fun isStartupActive(): Boolean {
    return active
    }
  
  /**
  
  * معرفة المرحلة الحالية.
    */
    fun getCurrentPhase(): String? {
    return currentPhase
    }
  
  /**
  
  * معرفة سبب Startup الحالي.
    */
    fun getCurrentReason(): StartupReason? {
    return currentReason
    }
  
  /**
  
  * معرفة correlationId الحالي.
    */
    fun getCurrentCorrelationId(): String? {
    return currentCorrelationId
    }
  
  /**
  
  * معرفة آخر Event.
    */
    fun getLastEvent(): String? {
    return lastEvent
    }
  
  /**
  
  * معرفة مكان مجلد التشخيص.
    */
    fun getDiagnosticsDirectory(): File {
    safe {
    ensureDirectory()
    }
    
    return diagnosticsDir
    }
  
  /**
  
  * الحصول على آخر تقرير Crash.
    */
    fun getLastCrashReport(): String? {
    return try {
    if (lastCrashFile.exists()) {
    lastCrashFile.readText()
    } else {
    null
    }
    } catch (e: Exception) {
    Log.e(TAG, "Unable to read last crash report", e)
    null
    }
    }
  
  /**
  
  * الحصول على حالة Startup الحالية.
    */
    fun getCurrentState(): String? {
    return try {
    if (currentStateFile.exists()) {
    currentStateFile.readText()
    } else {
    null
    }
    } catch (e: Exception) {
    Log.e(TAG, "Unable to read current startup state", e)
    null
    }
    }
  
  /**
  
  * الحصول على سجل الأحداث.
    */
    fun getEvents(): String {
    return try {
    if (eventsFile.exists()) {
    eventsFile.readText()
    } else {
    ""
    }
    } catch (e: Exception) {
    Log.e(TAG, "Unable to read startup events", e)
    ""
    }
    }
  
  /**
  
  * حذف تقارير التشخيص.
  
  * 
  
  * مفيد بعد انتهاء عملية التحقيق.
    */
    fun clearDiagnostics(): Boolean {
    return try {
    lock.withLock {
    if (diagnosticsDir.exists()) {
    diagnosticsDir.deleteRecursively()
    }
    
         ensureDirectory()

     active = false
     currentReason = null
     currentCorrelationId = null
     currentPhase = null
     lastEvent = null
     startedAtMs = 0L

     true
 }
    
    } catch (e: Exception) {
    Log.e(TAG, "Failed to clear diagnostics", e)
    false
    }
    }
  
  /**
  
  * كتابة الحالة الحالية بشكل Atomic قدر الإمكان.
    */
    private fun persistCurrentState(
    status: String,
    event: String
    ) {
    try {
    ensureDirectory()
    
     val now = System.currentTimeMillis()

 val content = buildString {
     appendLine("status=$status")
     appendLine("timestamp=$now")
     appendLine(
         "timestampFormatted=${formatDate(now)}"
     )
     appendLine(
         "reason=${currentReason?.name ?: "null"}"
     )
     appendLine(
         "correlationId=${currentCorrelationId ?: "null"}"
     )
     appendLine(
         "currentPhase=${currentPhase ?: "null"}"
     )
     appendLine("startedAtMs=$startedAtMs")
     appendLine("lastEvent=${sanitize(event)}")
 }

 val tempFile = File(
     diagnosticsDir,
     "$CURRENT_STATE_FILE.tmp"
 )

 tempFile.writeText(content)

 if (!tempFile.renameTo(currentStateFile)) {
     currentStateFile.writeText(content)
     tempFile.delete()
 }
    
    } catch (e: Exception) {
    Log.e(TAG, "Failed to persist startup state", e)
    }
    }
  
  /**
  
  * كتابة Event في سجل دائم.
    */
    private fun writeEvent(message: String) {
    
    try {
    ensureDirectory()
    
     if (!hasEnoughSpace()) {
     Log.w(
         TAG,
         "Insufficient storage for diagnostics"
     )
     return
 }

 val number = sequence.incrementAndGet()

 val line =
     "$number | ${formatDate(System.currentTimeMillis())} | $message\n"

 lock.withLock {

     eventsFile.appendText(line)

     trimEventsFile()
 }
    
    } catch (e: Exception) {
    Log.e(TAG, "Failed to write startup event", e)
    }
    }
  
  /**
  
  * إبقاء حجم السجل محدودًا.
    */
    private fun trimEventsFile() {
    
    try {
    
     if (!eventsFile.exists()) {
     return
 }

 if (eventsFile.length() <= MAX_EVENT_FILE_BYTES) {

     val lines = eventsFile
         .readLines()

     if (lines.size <= MAX_EVENT_LINES) {
         return
     }

     val retained = lines
         .takeLast(MAX_EVENT_LINES)

     eventsFile.writeText(
         retained.joinToString("\n") + "\n"
     )

     return
 }

 /*
  * إذا تجاوز الملف الحجم المسموح،
  * نحتفظ بآخر جزء منه.
  */
 val lines = eventsFile
     .readLines()
     .takeLast(MAX_EVENT_LINES)

 eventsFile.writeText(
     lines.joinToString("\n") + "\n"
 )
    
    } catch (e: Exception) {
    Log.w(
    TAG,
    "Failed to trim diagnostics events",
    e
    )
    }
    }
  
  /**
  
  * إنشاء تقرير Crash خاص بـ Startup.
    */
    private fun buildCrashReport(
    now: Long,
    threadName: String?,
    throwable: Throwable
    ): String {
    
    val runtime = Runtime.getRuntime()
    
    val totalMemory =
    runtime.totalMemory() / 1024 / 1024
    
    val freeMemory =
    runtime.freeMemory() / 1024 / 1024
    
    val maxMemory =
    runtime.maxMemory() / 1024 / 1024
    
    val usedMemory =
    totalMemory - freeMemory
    
    val storageAvailableMb =
    getAvailableStorageMb()
    
    return buildString {
    
     appendLine("══════════════════════════════════════")
 appendLine("STARTUP CRASH DIAGNOSTIC REPORT")
 appendLine("══════════════════════════════════════")

 appendLine()
 appendLine("TIME")
 appendLine("timestamp=$now")
 appendLine(
     "formatted=${formatDate(now)}"
 )

 appendLine()
 appendLine("STARTUP")
 appendLine(
     "active=$active"
 )
 appendLine(
     "reason=${currentReason?.name ?: "null"}"
 )
 appendLine(
     "correlationId=${currentCorrelationId ?: "null"}"
 )
 appendLine(
     "currentPhase=${currentPhase ?: "null"}"
 )
 appendLine(
     "startedAtMs=$startedAtMs"
 )

 if (startedAtMs > 0L) {
     appendLine(
         "startupElapsedMs=${now - startedAtMs}"
     )
 }

 appendLine(
     "lastEvent=${lastEvent ?: "null"}"
 )

 appendLine()
 appendLine("THREAD")
 appendLine(
     "name=${threadName ?: "unknown"}"
 )

 appendLine()
 appendLine("EXCEPTION")
 appendLine(
     "type=${throwable.javaClass.name}"
 )
 appendLine(
     "message=${throwable.message ?: "N/A"}"
 )

 appendLine()
 appendLine("STACKTRACE")
 appendLine(
     throwable.stackTraceToString()
 )

 appendLine()
 appendLine("DEVICE")
 appendLine(
     "manufacturer=${Build.MANUFACTURER}"
 )
 appendLine(
     "brand=${Build.BRAND}"
 )
 appendLine(
     "model=${Build.MODEL}"
 )
 appendLine(
     "device=${Build.DEVICE}"
 )
 appendLine(
     "android=${Build.VERSION.RELEASE}"
 )
 appendLine(
     "sdk=${Build.VERSION.SDK_INT}"
 )

 appendLine()
 appendLine("MEMORY")
 appendLine(
     "totalMb=$totalMemory"
 )
 appendLine(
     "freeMb=$freeMemory"
 )
 appendLine(
     "usedMb=$usedMemory"
 )
 appendLine(
     "maxMb=$maxMemory"
 )

 appendLine()
 appendLine("STORAGE")
 appendLine(
     "availableMb=$storageAvailableMb"
 )

 appendLine()
 appendLine("RECENT STARTUP EVENTS")
 appendLine(
     getEvents()
 )

 appendLine()
 appendLine("CURRENT STATE")
 appendLine(
     getCurrentState() ?: "none"
 )

 appendLine()
 appendLine("══════════════════════════════════════")
 appendLine("END OF REPORT")
 appendLine("══════════════════════════════════════")
    
    }
    }
  
  /**
  
  * الحصول على المساحة الحرة.
    */
    private fun getAvailableStorageMb(): Long {
    return try {
    
     val stat = StatFs(
     context.filesDir.absolutePath
 )

 stat.availableBytes /
     1024 /
     1024
    
    } catch (e: Exception) {
    -1L
    }
    }
  
  /**
  
  * التحقق من المساحة الحرة.
    */
    private fun hasEnoughSpace(): Boolean {
    return try {
    
     getAvailableStorageMb() >=
     MIN_FREE_SPACE_MB
    
    } catch (e: Exception) {
    true
    }
    }
  
  /**
  
  * إنشاء مجلد التشخيص.
    */
    private fun ensureDirectory() {
    
    if (!diagnosticsDir.exists()) {
    
     if (!diagnosticsDir.mkdirs() &&
     !diagnosticsDir.exists()
 ) {
     throw IOException(
         "Unable to create diagnostics directory: " +
             diagnosticsDir.absolutePath
     )
 }
    
    }
    }
  
  /**
  
  * تنظيف النصوص قبل وضعها في سطر واحد.
    */
    private fun sanitize(value: String): String {
    return value
    .replace("\r", " ")
    .replace("\n", " ")
    .replace("|", "/")
    .trim()
    }
  
  /**
  
  * تنسيق الوقت.
    */
    private fun formatDate(timestamp: Long): String {
    return try {
    
     SimpleDateFormat(
     "yyyy-MM-dd HH:mm:ss.SSS",
     Locale.US
 ).format(Date(timestamp))
    
    } catch (e: Exception) {
    timestamp.toString()
    }
    }
  
  /**
  
  * تنفيذ عملية تشخيص بأمان كامل.
  * 
  * لا يسمح لأي Exception من هذه الطبقة
  * بالتسبب في Crash إضافي.
    */
    private inline fun safe(
    block: () -> Unit
    ) {
    try {
    block()
    } catch (e: Exception) {
    Log.e(
    TAG,
    "Diagnostics operation failed",
    e
    )
    }
    }
    }