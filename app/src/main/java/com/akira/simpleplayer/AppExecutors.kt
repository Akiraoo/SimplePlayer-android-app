package com.akira.simpleplayer

import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * Keep expensive app work away from the Compose/main thread.
 * Media3 owns the playback/audio threads; these pools are for app-side work.
 */
object AppExecutors {
    // Small pool for cache + HTTP orchestration. Network reads themselves are blocking IO.
    val io = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "SimplePlayer-IO").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    // Small CPU pool for JSON parsing, filtering and other short calculations.
    val cpu = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "SimplePlayer-CPU").apply { isDaemon = true }
    }.asCoroutineDispatcher()
}
