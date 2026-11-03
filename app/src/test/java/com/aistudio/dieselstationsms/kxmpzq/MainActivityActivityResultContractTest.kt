package com.aistudio.dieselstationsms.kxmpzq

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityActivityResultContractTest {
    private val contract = ActivityResultContracts.StartActivityForResult()

    @Test
    fun documentSelectionPreservesUriAndPersistablePermissionFlags() {
        val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADocuments")
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        val intent = Intent().apply {
            data = uri
            addFlags(flags)
        }

        val result = contract.parseResult(Activity.RESULT_OK, intent)

        assertEquals(Activity.RESULT_OK, result.resultCode)
        assertNotNull(result.data)
        assertEquals(uri, result.data?.data)
        assertEquals(flags, result.data?.flags?.and(flags))
    }

    @Test
    fun cancelledPickerReturnsCancelledResultWithoutUri() {
        val result = contract.parseResult(Activity.RESULT_CANCELED, null)

        assertEquals(Activity.RESULT_CANCELED, result.resultCode)
        assertNull(result.data)
    }

    @Test
    fun fileChooserCanReturnMultipleUrisInClipData() {
        val first = Uri.parse("content://example.provider/document/one")
        val second = Uri.parse("content://example.provider/document/two")
        val intent = Intent().apply {
            clipData = android.content.ClipData(
                "documents",
                arrayOf("application/octet-stream"),
                android.content.ClipData.Item(first)
            ).apply {
                addItem(android.content.ClipData.Item(second))
            }
        }

        val result = contract.parseResult(Activity.RESULT_OK, intent)

        assertEquals(Activity.RESULT_OK, result.resultCode)
        assertNotNull(result.data?.clipData)
        assertEquals(2, result.data?.clipData?.itemCount)
        assertEquals(first, result.data?.clipData?.getItemAt(0)?.uri)
        assertEquals(second, result.data?.clipData?.getItemAt(1)?.uri)
    }
}
