package com.example.androidmocklocation

import kotlin.math.*

data class Point(val lat: Double, val lon: Double) {
    fun valid() = lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0
    override fun toString() = "%.6f, %.6f".format(java.util.Locale.US, lat, lon)
}

object Geo {
    private const val radius = 6371008.8
    fun distance(a: Point, b: Point): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * radius * asin(min(1.0, sqrt(h)))
    }
    fun bearing(a: Point, b: Point): Double {
        val p1 = Math.toRadians(a.lat)
        val p2 = Math.toRadians(b.lat)
        val dl = Math.toRadians(b.lon - a.lon)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }
    fun offset(p: Point, bearing: Double, metres: Double): Point {
        val phi1 = Math.toRadians(p.lat)
        val lambda1 = Math.toRadians(p.lon)
        val delta = metres / radius
        val theta = Math.toRadians(bearing)
        val phi2 = asin(sin(phi1) * cos(delta) + cos(phi1) * sin(delta) * cos(theta))
        val lambda2 = lambda1 + atan2(sin(theta) * sin(delta) * cos(phi1), cos(delta) - sin(phi1) * sin(phi2))
        return Point(Math.toDegrees(phi2), ((Math.toDegrees(lambda2) + 540) % 360) - 180)
    }
}

class RouteTrack(input: List<Point>) {
    val points: List<Point> = input.filterIndexed { index, point -> index == 0 || Geo.distance(input[index - 1], point) > 0.001 }
    private val cumulative = mutableListOf(0.0)
    init {
        require(points.size >= 2 && points.all { it.valid() }) { "路线需要至少两个不同的有效坐标" }
        points.zipWithNext().forEach { (a, b) -> cumulative += cumulative.last() + Geo.distance(a, b) }
    }
    val length: Double get() = cumulative.last()
    fun at(metres: Double): Point {
        if (metres <= 0) return points.first()
        if (metres >= length) return points.last()
        val index = cumulative.binarySearch(metres).let { if (it >= 0) it else -it - 2 }.coerceIn(0, points.size - 2)
        val fraction = (metres - cumulative[index]) / (cumulative[index + 1] - cumulative[index])
        val a = points[index]
        val b = points[index + 1]
        val longitudeStep = ((b.lon - a.lon + 540.0) % 360.0) - 180.0
        val longitude = ((a.lon + longitudeStep * fraction + 540.0) % 360.0) - 180.0
        return Point(a.lat + (b.lat - a.lat) * fraction, longitude)
    }
    fun bearingAt(metres: Double): Double {
        val index = cumulative.binarySearch(metres.coerceIn(0.0, length - 0.0000001)).let { if (it >= 0) it else -it - 2 }.coerceIn(0, points.size - 2)
        return Geo.bearing(points[index], points[index + 1])
    }
}

class RouteProgress(val track: RouteTrack, speed: Double) {
    var speedMetresPerSecond = speed
        set(value) { require(value in 0.1..100.0); field = value }
    var travelled = 0.0
        private set
    var paused = false
    init { speedMetresPerSecond = speed }
    val finished get() = travelled >= track.length
    val remaining get() = (track.length - travelled).coerceAtLeast(0.0)
    fun advance(seconds: Double): Point {
        if (!paused && seconds > 0) travelled = (travelled + speedMetresPerSecond * seconds).coerceAtMost(track.length)
        return track.at(travelled)
    }
}
