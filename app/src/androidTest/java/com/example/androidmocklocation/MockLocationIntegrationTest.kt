package com.example.androidmocklocation

import android.app.Instrumentation
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MockLocationIntegrationTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager: LocationManager get() = context.getSystemService(LocationManager::class.java)

    @After fun cleanUp() {
        if (Session.state.value.serviceRunning) Session.command(context, MockLocationService.STOP)
    }

    @Test fun createSubmitAndRemoveGpsProvider() {
        assumeTrue("Select this app as mock-location app first", Session.mockAllowed(context))
        val properties = ProviderProperties.Builder().setAccuracy(ProviderProperties.ACCURACY_FINE)
            .setPowerUsage(ProviderProperties.POWER_USAGE_LOW).build()
        manager.addTestProvider(LocationManager.GPS_PROVIDER, properties)
        try {
            manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, Location(LocationManager.GPS_PROVIDER).apply {
                latitude = 39.9042; longitude = 116.4074; accuracy = 3f
                time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            })
        } finally {
            manager.removeTestProvider(LocationManager.GPS_PROVIDER)
            manager.removeTestProvider(LocationManager.GPS_PROVIDER) // idempotent cleanup
        }
    }

    @Test fun foregroundServiceFixedLocationAndStop() {
        assumeTrue("Select this app as mock-location app first", Session.mockAllowed(context))
        ActivityScenario.launch(MainActivity::class.java).use {
            val target = Point(39.9042, 116.4074)
            Session.command(context, MockLocationService.FIX, point = target)
            waitUntil { Session.state.value.mode == "FIXED" }
            assertEquals(target, Session.state.value.submitted)
            assertTrue(Session.state.value.serviceRunning)
            Session.command(context, MockLocationService.STOP)
            waitUntil { Session.state.value.mode == "IDLE" }
            assertFalse(Session.state.value.serviceRunning)
        }
    }

    @Test fun routePauseJoystickAndResumeUseOneEngine() {
        assumeTrue("Select this app as mock-location app first", Session.mockAllowed(context))
        ActivityScenario.launch(MainActivity::class.java).use {
            val first = Point(39.9042, 116.4074)
            val last = Point(39.9042, 116.4174)
            Session.command(context, MockLocationService.ROUTE, points = listOf(first, last), speed = 2.0)
            waitUntil { Session.state.value.mode == "ROUTE_RUNNING" }
            Session.command(context, MockLocationService.PAUSE)
            waitUntil { Session.state.value.mode == "ROUTE_PAUSED" }
            val pausedAt = Session.state.value.routeMetres
            Thread.sleep(1200)
            assertEquals(pausedAt, Session.state.value.routeMetres, 0.01)
            Session.command(context, MockLocationService.JOYSTICK, speed = 5.0)
            waitUntil { Session.state.value.mode == "JOYSTICK" }
            val beforeSteer = Session.state.value.submitted!!
            Session.command(context, MockLocationService.STEER, speed = 5.0, bearing = 90.0)
            Thread.sleep(1300)
            Session.command(context, MockLocationService.RELEASE)
            waitUntil { Session.state.value.submitted != beforeSteer }
            Session.command(context, MockLocationService.END_JOYSTICK)
            waitUntil { Session.state.value.mode == "ROUTE_PAUSED" }
            Session.command(context, MockLocationService.RESUME)
            waitUntil { Session.state.value.mode == "ROUTE_RUNNING" }
            assertTrue(Session.state.value.routeMetres >= pausedAt)
            // A direction gesture can finish after the resume button is pressed.
            Session.command(context, MockLocationService.RELEASE)
            Session.command(context, MockLocationService.END_JOYSTICK)
            val resumedAt = Session.state.value.routeMetres
            waitUntil { Session.state.value.routeMetres > resumedAt }
            assertEquals("ROUTE_RUNNING", Session.state.value.mode)
            Session.command(context, MockLocationService.STOP)
            waitUntil { Session.state.value.mode == "IDLE" }
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        repeat(50) {
            if (condition()) return
            Thread.sleep(100)
        }
        fail("Timed out. Snapshot: ${Session.state.value}")
    }
}
