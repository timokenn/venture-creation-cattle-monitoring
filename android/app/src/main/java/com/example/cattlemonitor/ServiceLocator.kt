package com.example.cattlemonitor

import com.example.cattlemonitor.data.CattleRepository

/** Tiny manual service locator — no DI framework needed at this scale. */
object ServiceLocator {
    val repository: CattleRepository by lazy { CattleRepository() }
}
