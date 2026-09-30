package com.example.androidmocklocation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StoreIntegrationTest {
    @Test fun favoriteRenameDeleteAndRouteRoundTrip() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = Point(39.9042, 116.4074)
        val second = Point(39.9142, 116.4174)
        Store(context).use { store ->
            store.addPoint("favorites", "integration-test", first)
            val favorite = store.points("favorites").first { it.name == "integration-test" }
            try {
                assertEquals(first, favorite.point)
                store.renameFavorite(favorite.id, "integration-renamed")
                assertEquals("integration-renamed", store.points("favorites").first { it.id == favorite.id }.name)
            } finally { store.delete("favorites", favorite.id) }

            store.addRoute("integration-route", listOf(first, second))
            val route = store.routes().first { it.name == "integration-route" }
            try { assertEquals(listOf(first, second), route.points) }
            finally { store.delete("routes", route.id) }
        }
    }
}
