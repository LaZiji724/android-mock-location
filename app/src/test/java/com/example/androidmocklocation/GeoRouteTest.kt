package com.example.androidmocklocation

import org.junit.Assert.*
import org.junit.Test

class GeoRouteTest {
    private val beijing = Point(39.9042, 116.4074)

    @Test fun coordinateValidation() {
        assertTrue(beijing.valid())
        assertFalse(Point(91.0, 0.0).valid())
        assertFalse(Point(0.0, -181.0).valid())
        assertFalse(Point(Double.NaN, 0.0).valid())
    }

    @Test fun mapCoordinateRoundTrip() {
        assertEquals(beijing, Coordinates.fromMap(Coordinates.toMap(beijing, CoordinateSystem.WGS84), CoordinateSystem.WGS84))
        val gcj = Coordinates.toMap(beijing, CoordinateSystem.GCJ02)
        assertTrue(Geo.distance(beijing, gcj) > 100)
        assertTrue(Geo.distance(beijing, Coordinates.fromMap(gcj, CoordinateSystem.GCJ02)) < 0.01)
        val london = Point(51.5072, -0.1276)
        assertEquals(london, Coordinates.toMap(london, CoordinateSystem.GCJ02))
    }

    @Test fun distanceAndBearing() {
        val east = Point(39.9042, 116.4174)
        assertEquals(0.0, Geo.distance(beijing, beijing), 0.001)
        assertTrue(Geo.distance(beijing, east) in 800.0..900.0)
        assertEquals(90.0, Geo.bearing(beijing, east), 0.01)
    }

    @Test fun routeInterpolationAndDuplicatePoints() {
        val end = Point(39.9042, 116.4174)
        val track = RouteTrack(listOf(beijing, beijing, end))
        assertEquals(2, track.points.size)
        assertEquals(beijing.lat, track.at(track.length / 2).lat, 1e-8)
        assertEquals((beijing.lon + end.lon) / 2, track.at(track.length / 2).lon, 1e-8)
        assertEquals(end, track.at(track.length + 100))
    }

    @Test fun speedPauseResumeAndEndpoint() {
        val track = RouteTrack(listOf(beijing, Point(39.9042, 116.4174)))
        val progress = RouteProgress(track, 2.0)
        progress.advance(10.0)
        assertEquals(20.0, progress.travelled, 1e-8)
        progress.paused = true
        progress.advance(10.0)
        assertEquals(20.0, progress.travelled, 1e-8)
        progress.speedMetresPerSecond = 5.0
        progress.paused = false
        progress.advance(10.0)
        assertEquals(70.0, progress.travelled, 1e-8)
        assertEquals(track.points.last(), progress.advance(1000.0))
        assertTrue(progress.finished)
        assertEquals(0.0, progress.remaining, 0.0)
    }

    @Test fun joystickOffset() {
        val moved = Geo.offset(beijing, 45.0, 100.0)
        assertTrue(moved.lat > beijing.lat && moved.lon > beijing.lon)
        assertEquals(100.0, Geo.distance(beijing, moved), 0.01)
    }

    @Test fun routeCrossesDatelineByShortestPath() {
        val track = RouteTrack(listOf(Point(0.0, 179.9), Point(0.0, -179.9)))
        val halfway = track.at(track.length / 2)
        assertEquals(-180.0, halfway.lon, 1e-6)
        assertTrue(track.length in 21000.0..23000.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidIntermediatePointIsRejected() {
        RouteTrack(listOf(beijing, Point(Double.NaN, 0.0), Point(39.9042, 116.4174), Point(39.9042, 116.4274)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidTrailingPointIsRejected() {
        RouteTrack(listOf(beijing, Point(39.9042, 116.4174), Point(0.0, Double.NaN)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun onePointRouteRejected() { RouteTrack(listOf(beijing)) }
}
