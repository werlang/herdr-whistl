package io.github.werlang.whistl.domain

/**
 * What a core reports from `hello` (SPEC §5).
 *
 * @property coreId the core's stable id.
 * @property coreName the core's name, as set on the machine.
 * @property features feature names the core advertises (for example `events`).
 * @property generation protocol generation; this client only accepts `1`.
 * @property methods RPC method names the core supports.
 * @property version the core's version string, for example `0.1.18`.
 */
data class HelloResult(
    val coreId: String,
    val coreName: String,
    val features: List<String>,
    val generation: Int,
    val methods: List<String>,
    val version: String,
)
