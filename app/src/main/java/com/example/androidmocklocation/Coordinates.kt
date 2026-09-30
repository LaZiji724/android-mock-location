package com.example.androidmocklocation

import kotlin.math.*

enum class CoordinateSystem { WGS84, GCJ02 }

/** Storage and Android Location always use WGS-84; conversion is confined to map boundaries. */
object Coordinates {
    private const val a = 6378245.0
    private const val ee = 0.00669342162296594323

    fun toMap(wgs: Point, system: CoordinateSystem): Point = when (system) {
        CoordinateSystem.WGS84 -> wgs
        CoordinateSystem.GCJ02 -> wgsToGcj(wgs)
    }
    fun fromMap(map: Point, system: CoordinateSystem): Point = when (system) {
        CoordinateSystem.WGS84 -> map
        CoordinateSystem.GCJ02 -> gcjToWgs(map)
    }
    fun wgsToGcj(wgs: Point): Point {
        require(wgs.valid())
        if (outsideChina(wgs)) return wgs
        val x = wgs.lon - 105.0
        val y = wgs.lat - 35.0
        var dLat = -100.0 + 2 * x + 3 * y + .2 * y * y + .1 * x * y + .2 * sqrt(abs(x))
        dLat += (20 * sin(6 * x * PI) + 20 * sin(2 * x * PI)) * 2 / 3
        dLat += (20 * sin(y * PI) + 40 * sin(y / 3 * PI)) * 2 / 3
        dLat += (160 * sin(y / 12 * PI) + 320 * sin(y * PI / 30)) * 2 / 3
        var dLon = 300.0 + x + 2 * y + .1 * x * x + .1 * x * y + .1 * sqrt(abs(x))
        dLon += (20 * sin(6 * x * PI) + 20 * sin(2 * x * PI)) * 2 / 3
        dLon += (20 * sin(x * PI) + 40 * sin(x / 3 * PI)) * 2 / 3
        dLon += (150 * sin(x / 12 * PI) + 300 * sin(x / 30 * PI)) * 2 / 3
        val rad = Math.toRadians(wgs.lat)
        val magic = 1 - ee * sin(rad).pow(2)
        val root = sqrt(magic)
        dLat = dLat * 180 / ((a * (1 - ee)) / (magic * root) * PI)
        dLon = dLon * 180 / (a / root * cos(rad) * PI)
        return Point(wgs.lat + dLat, wgs.lon + dLon)
    }
    fun gcjToWgs(gcj: Point): Point {
        require(gcj.valid())
        if (outsideChina(gcj)) return gcj
        var estimate = gcj
        repeat(30) {
            val forward = wgsToGcj(estimate)
            val latitudeError = forward.lat - gcj.lat
            val longitudeError = forward.lon - gcj.lon
            if (abs(latitudeError) < 1e-9 && abs(longitudeError) < 1e-9) return estimate
            estimate = Point(estimate.lat - latitudeError, estimate.lon - longitudeError)
        }
        return estimate
    }
    private fun outsideChina(p: Point) = p.lon !in 72.004..137.8347 || p.lat !in 0.8293..55.8271
}
