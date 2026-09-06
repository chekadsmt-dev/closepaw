package ai.closepaw.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal enum class SmtRouteState {
    PENDING,
    SENT,
    WAITING_RESPONSE,
    RESULT_CAPTURED,
    RETURNED_TO_00,
    FAILED,
}

internal class SmtRouteLedger(context: Context) :
    SQLiteOpenHelper(context, "smt_router.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE routes (
                route_id TEXT PRIMARY KEY,
                target TEXT NOT NULL,
                state TEXT NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0,
                payload_hash TEXT,
                last_error TEXT,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun isTerminal(routeId: String): Boolean {
        readableDatabase.query(
            "routes",
            arrayOf("state"),
            "route_id = ?",
            arrayOf(routeId),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) return false
            return when (cursor.getString(0)) {
                SmtRouteState.RETURNED_TO_00.name, SmtRouteState.FAILED.name -> true
                else -> false
            }
        }
    }

    fun upsertPending(routeId: String, target: String, payloadHash: String) {
        val values = ContentValues().apply {
            put("route_id", routeId)
            put("target", target)
            put("state", SmtRouteState.PENDING.name)
            put("payload_hash", payloadHash)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "routes",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    fun update(
        routeId: String,
        state: SmtRouteState,
        error: String? = null,
        incrementAttempts: Boolean = false,
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("state", state.name)
                put("updated_at", System.currentTimeMillis())
                if (error == null) putNull("last_error") else put("last_error", error)
            }
            db.update("routes", values, "route_id = ?", arrayOf(routeId))
            if (incrementAttempts) {
                db.execSQL(
                    "UPDATE routes SET attempts = attempts + 1, updated_at = ? WHERE route_id = ?",
                    arrayOf(System.currentTimeMillis(), routeId)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
