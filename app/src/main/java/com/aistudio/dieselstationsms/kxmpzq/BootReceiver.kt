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
 * الإصدار 1.0 – متوافق مع جميع إصدارات Android
 *
 * المتطلبات في AndroidManifest.xml:
 * <receiver
 *     android:name=".BootReceiver"
 *     android:exported="true"
 *     android:enabled="true">
 *     <intent-filter>
 *         <action android:name="android.intent.action.BOOT_COMPLETED" />
 *         <action android:name="android.intent.action.QUICKBOOT_POWERON" />
 *     </intent-filter>
 * </receiver>
 *
 * ملاحظة: يحتاج التطبيق إلى إذن RECEIVE_BOOT_COMPLETED
 * <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
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
            action == Intent.ACTION_QUICKBOOT_POWERON ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {

            Log.i(TAG, "Device boot completed, starting SMSService...")

            try {
                // إنشاء Intent لبدء الخدمة
                val serviceIntent = Intent(context, SMSService::class.java)

                // بدء الخدمة حسب إصدار Android
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // Android 8.0+ يتطلب startForegroundService
                    context.startForegroundService(serviceIntent)
                    Log.d(TAG, "Foreground service started (Android 8+)")
                } else {
                    // الإصدارات الأقدم تستخدم startService العادي
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
