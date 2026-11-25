package com.aistudio.dieselstationsms.kxmpzq

import android.app.Application

/**
 * Prevents production startup recovery jobs from racing deterministic database fixtures.
 * Worker integration tests still initialize WorkManager and execute the production workers.
 */
class DatabaseLifecycleTestApplication : Application()
