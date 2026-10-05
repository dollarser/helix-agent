package com.helix.core.policy.network

data class HostsError(
    val line: Int,
    val reason: Reason,
) {
    enum class Reason { INVALID_IP, MISSING_HOST, INVALID_HOST, TOO_LARGE, TOO_MANY_ADDRESSES }
}

data class HostsDocument(
    val addresses: Map<String, List<String>>,
    val errors: List<HostsError>,
) {
    companion object {
        fun parse(text: String): HostsDocument {
            if (text.toByteArray().size > 128 * 1024) {
                return HostsDocument(emptyMap(), listOf(HostsError(1, HostsError.Reason.TOO_LARGE)))
            }
            val addresses = linkedMapOf<String, MutableList<String>>()
            val errors = mutableListOf<HostsError>()
            text.lineSequence().forEachIndexed { index, raw ->
                val line = raw.substringBefore('#').trim()
                if (line.isNotEmpty()) parseLine(index + 1, line, addresses, errors)
            }
            return HostsDocument(addresses, errors)
        }

        private fun parseLine(
            number: Int,
            line: String,
            addresses: MutableMap<String, MutableList<String>>,
            errors: MutableList<HostsError>,
        ) {
            val fields = line.split(Regex("\\s+"))
            if (fields.size < 2) {
                errors += HostsError(number, HostsError.Reason.MISSING_HOST)
                return
            }
            val ip =
                try {
                    require(!fields[0].startsWith('['))
                    NativeDnsSettings.literal(fields[0]).hostAddress!!
                } catch (_: IllegalArgumentException) {
                    errors += HostsError(number, HostsError.Reason.INVALID_IP)
                    return
                }
            fields.drop(1).forEach { alias ->
                val host =
                    try {
                        NativeDnsSettings.domain(alias)
                    } catch (_: IllegalArgumentException) {
                        errors += HostsError(number, HostsError.Reason.INVALID_HOST)
                        return@forEach
                    }
                val values = addresses.getOrPut(host) { mutableListOf() }
                if (ip !in values) values += ip
                if (values.size > 16) errors += HostsError(number, HostsError.Reason.TOO_MANY_ADDRESSES)
            }
        }
    }
}
