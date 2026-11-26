package io.github.hamzabhai.raftaaro.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerCacheTest {
    @Test fun `keeps the newest prepared players and drops older ones`() =
        assertEquals(
            listOf("player,old.json", "player,older.json"),
            stalePlayerFiles(listOf("player,older.json" to 1L, "player,new.json" to 30L, "player,old.json" to 10L, "player,newer.json" to 20L), keep = 2)
        )

    @Test fun `leaves the solver scripts alone`() =
        assertEquals(emptyList<String>(), stalePlayerFiles(listOf("lib.json" to 1L, "core.json" to 2L, "player,a.json" to 3L), keep = 1))

    @Test fun `nothing to drop when few are kept`() =
        assertEquals(emptyList<String>(), stalePlayerFiles(listOf("player,a.json" to 3L), keep = 2))
}
