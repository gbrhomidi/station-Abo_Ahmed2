package com.aistudio.dieselstationsms.kxmpzq.data

import android.database.sqlite.SQLiteDatabase
import androidx.annotation.WorkerThread
import org.json.JSONObject

class ProductBatchDao(private val db: SQLiteDatabase) {
    @WorkerThread fun getActiveBatchByProduct(productId: Long): JSONObject? {
        require(productId > 0)
        return db.query("product_batches", null, "product_id=? AND status='active' AND COALESCE(remaining_quantity,quantity)>0",
            arrayOf(productId.toString()), null, null, "CASE WHEN expiry_date IS NULL OR expiry_date='' THEN 1 ELSE 0 END, expiry_date, received_at, batch_id", "1").use {
            if (it.moveToFirst()) MovementDaoSupport.row(it) else null
        }
    }
    @WorkerThread fun create(data: JSONObject): Long {
        require(data.optLong("product_id") > 0 && data.optDouble("quantity") >= 0)
        val row = JSONObject(data.toString())
        if (!row.has("received_quantity")) row.put("received_quantity", row.optDouble("quantity"))
        if (!row.has("remaining_quantity")) row.put("remaining_quantity", row.optDouble("quantity"))
        return MovementDaoSupport.insert(db, "product_batches", row)
    }
    @WorkerThread fun update(batchId: Long, data: JSONObject): Int =
        MovementDaoSupport.update(db, "product_batches", "batch_id", batchId, data)
    @WorkerThread fun get(batchId: Long): JSONObject? = MovementDaoSupport.get(db, "product_batches", "batch_id", batchId)
    @WorkerThread fun delete(batchId: Long): Int {
        require(batchId > 0)
        val referenced = db.rawQuery("SELECT 1 FROM inventory_movements WHERE batch_id=? LIMIT 1", arrayOf(batchId.toString())).use { it.moveToFirst() }
        require(!referenced) { "لا يمكن حذف دفعة مرتبطة بحركة مخزنية" }
        return db.delete("product_batches", "batch_id=?", arrayOf(batchId.toString()))
    }
}
