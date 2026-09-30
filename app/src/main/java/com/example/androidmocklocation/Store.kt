package com.example.androidmocklocation

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray

data class SavedPoint(val id: Long, val name: String, val point: Point, val created: Long)
data class SavedRoute(val id: Long, val name: String, val points: List<Point>, val created: Long)

class Store(context: Context) : SQLiteOpenHelper(context, "locations.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE favorites(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,lat REAL NOT NULL,lon REAL NOT NULL,created INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE history(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,lat REAL NOT NULL,lon REAL NOT NULL,created INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE routes(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,points TEXT NOT NULL,created INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    fun addPoint(table: String, name: String, point: Point) {
        require(table == "favorites" || table == "history")
        require(point.valid())
        writableDatabase.insertOrThrow(table, null, ContentValues().apply {
            put("name", name); put("lat", point.lat); put("lon", point.lon); put("created", System.currentTimeMillis())
        })
    }
    fun points(table: String): List<SavedPoint> {
        require(table == "favorites" || table == "history")
        val result = mutableListOf<SavedPoint>()
        readableDatabase.query(table, arrayOf("id", "name", "lat", "lon", "created"), null, null, null, null, "created DESC", "100").use { cursor ->
            while (cursor.moveToNext()) result += SavedPoint(cursor.getLong(0), cursor.getString(1), Point(cursor.getDouble(2), cursor.getDouble(3)), cursor.getLong(4))
        }
        return result
    }
    fun renameFavorite(id: Long, name: String) {
        writableDatabase.update("favorites", ContentValues().apply { put("name", name) }, "id=?", arrayOf(id.toString()))
    }
    fun delete(table: String, id: Long) {
        require(table == "favorites" || table == "routes")
        writableDatabase.delete(table, "id=?", arrayOf(id.toString()))
    }
    fun addRoute(name: String, points: List<Point>) {
        RouteTrack(points)
        val encoded = JSONArray().apply { points.forEach { put(JSONArray().put(it.lat).put(it.lon)) } }.toString()
        writableDatabase.insertOrThrow("routes", null, ContentValues().apply {
            put("name", name); put("points", encoded); put("created", System.currentTimeMillis())
        })
    }
    fun routes(): List<SavedRoute> {
        val result = mutableListOf<SavedRoute>()
        readableDatabase.query("routes", arrayOf("id", "name", "points", "created"), null, null, null, null, "created DESC", "100").use { cursor ->
            while (cursor.moveToNext()) {
                val array = JSONArray(cursor.getString(2))
                val points = (0 until array.length()).map { index ->
                    val pair = array.getJSONArray(index)
                    Point(pair.getDouble(0), pair.getDouble(1))
                }
                result += SavedRoute(cursor.getLong(0), cursor.getString(1), points, cursor.getLong(3))
            }
        }
        return result
    }
}
