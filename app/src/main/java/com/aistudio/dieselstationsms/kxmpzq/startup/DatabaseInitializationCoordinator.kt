package com.aistudio.dieselstationsms.kxmpzq.startup

import android.content.Context
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteException
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import kotlinx.coroutines.*
import java.util.Locale

object DatabaseInitializationCoordinator {
 private const val MAX_ATTEMPTS = 3
 private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("DatabaseInitialization"))
 private val initializer = SingleFlightInitializer<com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper>(scope, MAX_ATTEMPTS, { a -> if (a == 1) 250L else 700L }, ::isTransient)

 suspend fun awaitDatabase(context: Context): DatabaseHelper = initializer.get {
  val started = android.os.SystemClock.elapsedRealtime()
  val helper = DatabaseHelper.getInstance(context.applicationContext)
  val database = helper.writableDatabase
  check(database.isOpen) { "SQLite database is not open" }
  android.util.Log.i("DatabaseInitCoordinator", "SQLite ready; elapsed=" + (android.os.SystemClock.elapsedRealtime() - started) + "ms")
  helper
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
