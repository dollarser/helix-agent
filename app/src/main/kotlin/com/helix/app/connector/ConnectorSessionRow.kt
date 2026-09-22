package com.helix.app.connector

data class ConnectorSessionRow(
    val id: String,
    val name: String,
    val selected: Boolean,
    val defaultSelected: Boolean,
    val available: Boolean,
    val ready: Boolean,
)
