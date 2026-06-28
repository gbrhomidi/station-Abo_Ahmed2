package com.aistudio.dieselstationsms.kxmpzq

import android.app.Application
import android.util.Log

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // حفظ معالج الاستثناءات الافتراضي للنظام
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        // تعيين معالج مخصص مع تمرير الخطأ للنظام
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("MyApplication", "Uncaught exception in thread ${thread.name}", throwable)
            // تمرير الاستثناء للمعالج الافتراضي لإغلاق التطبيق بشكل صحيح وعرض رسالة الخطأ
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
