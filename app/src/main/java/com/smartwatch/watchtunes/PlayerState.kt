package com.smartwatch.watchtunes

data class PlayerState(
    val running: Boolean = false,
    val titles: List<String> = emptyList(),
    val index: Int = -1,
    val playing: Boolean = false,
    val routing: Boolean = false,
    val message: String? = null,
)
