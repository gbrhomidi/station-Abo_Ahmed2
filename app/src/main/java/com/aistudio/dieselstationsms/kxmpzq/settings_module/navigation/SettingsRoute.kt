package com.aistudio.dieselstationsms.kxmpzq.settings.navigation

/**
 * مسارات شاشة الإعدادات
 */
sealed class SettingsRoute(val route: String) {
    object Main : SettingsRoute("settings_main")
    object Dashboard : SettingsRoute("settings_dashboard")
    object Backup : SettingsRoute("settings_backup")
    object Security : SettingsRoute("settings_security")
    object Logs : SettingsRoute("settings_logs")
    object About : SettingsRoute("settings_about")
    object Monitoring : SettingsRoute("settings_monitoring")
    object Maintenance : SettingsRoute("settings_maintenance")
}
