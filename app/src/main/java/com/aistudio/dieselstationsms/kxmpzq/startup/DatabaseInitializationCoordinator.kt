package com.aistudio.dieselstationsms.kxmpzq.startup

import android.content.Context
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteException
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import kotlinx.coroutines.*
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object DatabaseInitializationCoordinator {
 private const val MAX_ATTEMPTS = 3
 private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("DatabaseInitialization"))
 private val lifecycleLock = ReentrantLock()
 private val initializerMapLock = Any()
 private val initializers = mutableMapOf<String, SingleFlightInitializer<DatabaseHelper>>()

 private fun initializerFor(databasePath: String): SingleFlightInitializer<DatabaseHelper> =
  synchronized(initializerMapLock) {
   initializers.getOrPut(databasePath) {
    SingleFlightInitializer<DatabaseHelper>(
     scope, MAX_ATTEMPTS, { attempt -> if (attempt == 1) 250L else 700L }, ::isTransient
    )
   }
  }

 /** Serializes database opens with scoped helper retirement. Each SQLite file has its own initializer. */
 internal fun <T> withLifecycleLock(block: () -> T): T = lifecycleLock.withLock(block)

 /** Invalidate one database path, or every path only for an explicit process-wide reset. */
 internal fun invalidate(databasePath: String? = null) {
  withLifecycleLock {
   val retired = synchronized(initializerMapLock) {
    if (databasePath == null) {
     initializers.values.toList().also { initializers.clear() }
    } else {
     listOfNotNull(initializers.remove(databasePath))
    }
   }
   retired.forEach { it.resetForTests() }
  }
 }

 internal fun resetForTests() = invalidate()

 internal fun resetForTests(context: Context) {
  val appContext = context.applicationContext
  invalidate(appContext.getDatabasePath(DatabaseHelper.DATABASE_NAME).absolutePath)
 }

 suspend fun awaitDatabase(context: Context): DatabaseHelper {
  StartupPerformanceMonitor.increment("database.await_requests")
  val awaitSpan = StartupPerformanceMonitor.beginSpan("database.await")
  var awaitSucceeded = false
  try {
   val appContext = context.applicationContext
   val databasePath = appContext.getDatabasePath(DatabaseHelper.DATABASE_NAME).absolutePath
   val pathInitializer = initializerFor(databasePath)
   val helper = pathInitializer.get {
    withLifecycleLock {
     StartupPerformanceMonitor.increment("database.open_attempts")
     val attemptSpan = StartupPerformanceMonitor.beginSpan("database.open_attempt")
     try {
      val helper = DatabaseHelper.getInstance(appContext)
      val database = helper.writableDatabase
      check(database.isOpen) { "SQLite database is not open" }
      android.util.Log.i("DatabaseInitCoordinator", "SQLite ready: $databasePath")
      StartupPerformanceMonitor.mark("database.sqlite_open_ready")
      StartupPerformanceMonitor.endSpan(attemptSpan)
      helper
     } catch (error: Exception) {
      StartupPerformanceMonitor.increment("database.open_failures")
      StartupPerformanceMonitor.mark(
       "database.sqlite_open_failed",
       mapOf("exception" to error.javaClass.simpleName)
      )
      StartupPerformanceMonitor.endSpan(attemptSpan, success = false)
      throw error
     }
    }
   }
   awaitSucceeded = true
   return helper
  } finally {
   StartupPerformanceMonitor.endSpan(awaitSpan, awaitSucceeded)
  }
 }

 private fun isTransient(error: Throwable): Boolean {
  var current: Throwable? = error
  var depth = 0
  while (current != null && depth < 8) {
   if (current is SQLiteDatabaseLockedException || current is SQLiteCantOpenDatabaseException) return true
   if (current is SQLiteException) {
    val message = current.message.orEmpty().lowercase(Locale.ROOT)
    if (message.contains("locked") || message.contains("busy") || message.contains("unable to open")) return true
   }
   current = current.cause
   depth++
  }
  return false
 }
}
