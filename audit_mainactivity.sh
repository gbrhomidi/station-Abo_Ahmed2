#!/data/data/com.termux/files/usr/bin/bash

FILE="app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt"

echo "========================================="
echo " MainActivity.kt Security & Architecture Audit"
echo "========================================="

if [ ! -f "$FILE" ]; then
    echo "ERROR: File not found: $FILE"
    exit 1
fi

echo
echo "1) Login implementation"
echo "-----------------------------------------"
grep -n "fun login\|authenticateUser\|is_admin\|SUPER_ADMIN\|role\|token\|UUID" "$FILE"

echo
echo "2) JavascriptInterface exposed functions"
echo "-----------------------------------------"
echo "Count:"
grep -c "@JavascriptInterface" "$FILE"

echo "Functions:"
grep -n "@JavascriptInterface" "$FILE"

echo
echo "3) Database write operations exposed"
echo "-----------------------------------------"
grep -n "insert\|update\|delete\|addUser\|updateUser\|deleteUser\|execSQL\|rawQuery" "$FILE"

echo
echo "4) Blocking synchronous operations"
echo "-----------------------------------------"
grep -n "Sync\|sendMessageSync\|backupDatabase\|restoreDatabase\|exportToCSV\|importFromCSV\|vacuum\|exportAllData" "$FILE"

echo
echo "5) Coroutine usage"
echo "-----------------------------------------"
grep -n "lifecycleScope\|CoroutineScope\|launch\|Dispatchers" "$FILE"

echo
echo "6) Activity lifecycle handling"
echo "-----------------------------------------"
grep -n "onCreate\|onDestroy\|onPause\|onResume" "$FILE"

echo
echo "7) WebView configuration"
echo "-----------------------------------------"
grep -n "WebView\|WebSettings\|javaScriptEnabled\|allowFileAccess\|allowContentAccess\|setSupportMultipleWindows\|WebChromeClient\|WebViewClient" "$FILE"

echo
echo "8) Permission handling"
echo "-----------------------------------------"
grep -n "requestPermissions\|requestAllPermissions\|onRequestPermissionsResult\|WRITE_EXTERNAL_STORAGE\|MANAGE_EXTERNAL_STORAGE\|READ_EXTERNAL_STORAGE" "$FILE"

echo
echo "9) SMS Service startup"
echo "-----------------------------------------"
grep -n "startSMSService\|postDelayed" "$FILE"

echo
echo "10) Context / memory leak risks"
echo "-----------------------------------------"
grep -n "class WebAppInterface\|inner class\|Context\|MainActivity" "$FILE"

echo
echo "11) Hardcoded admin/security values"
echo "-----------------------------------------"
grep -n "true\|SUPER_ADMIN\|admin\|password" "$FILE"

echo
echo "12) External URL loading"
echo "-----------------------------------------"
grep -n "loadUrl\|shouldOverrideUrlLoading\|http://\|https://" "$FILE"

echo
echo "13) Error handling"
echo "-----------------------------------------"
grep -n "try\|catch\|Exception\|Throwable" "$FILE" | head -100

echo
echo "========================================="
echo " Audit finished"
echo "========================================="
