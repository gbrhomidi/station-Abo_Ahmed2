package com.aistudio.dieselstationsms.kxmpzq

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * BootReceiver - مستقبل إقلاع الجهاز
 *
 * هذا المستقبل يستمع إلى حدث BOOT_COMPLETED ويقوم بتشغيل خدمة SMSService
 * تلقائياً عند إقلاع الجهاز، لضمان استمرارية عمل الخادم المحلي
 * وإمكانية استقبال الرسائل النصية ومعالجتها.
 *
 * الإصدار 1.1 – متوافق مع جميع إصدارات Android
 * تم إصلاح مشكلة ACTION_QUICKBOOT_POWERON باستخدام النص الحرفي
 *
 * المتطلبات في AndroidManifest.xml:
 * <receiver
 *     android:name=".BootReceiver"
 *     android:exported="true"
 *     android:enabled="true">
 *     <intent-filter>
 *         <action android:name="android.intent.action.BOOT_COMPLETED" />
 *         <action android:name="android.intent.action.QUICKBOOT_POWERON" />
 *         <action android:name="android.intent.action.LOCKED_BOOT_COMPLETED" />
 *     </intent-filter>
 * </receiver>
 *
 * ملاحظة: يحتاج التطبيق إلى إذن RECEIVE_BOOT_COMPLETED
 * <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }

    /**
     * يتم استدعاؤها عند استقاذ البث
     * @param context سياق التطبيق
     * @param intent النية المستلمة
     */
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "onReceive called with action: $action")

        // التحقق من أن البث هو حدث إقلاع الجهاز
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == ACTION_QUICKBOOT_POWERON ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {

            Log.i(TAG, "Device boot completed, starting SMSService...")

            try {
                val serviceIntent = Intent(context, SMSService::class.java)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                    Log.d(TAG, "Foreground service started (Android 8+)")
                } else {
                    context.startService(serviceIntent)
                    Log.d(TAG, "Service started (Android < 8)")
                }

                Log.i(TAG, "SMSService started successfully on boot")

            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException starting service on boot: ${e.message}", e)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "IllegalStateException starting service on boot: ${e.message}", e)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting service on boot: ${e.message}", e)
            }
        } else {
            Log.d(TAG, "Ignoring action: $action (not a boot completed event)")
        }
    }
}
