@file:Suppress("TooManyFunctions", "UseRequire")

package com.helix.extensions.skills.connector.index

import java.net.URI

/**
 * Strict recursive-descent parser for connector index JSON documents.
 * Enforces RFC 8259 compliance, strict 64-bit integer numbers, nesting depth limits,
 * duplicate key rejection, and known schema version validation.
 */
internal object SignedConnectorIndexParser {
    sealed interface ParseResult {
        data class Success(
            val index: ConnectorIndex,
        ) : ParseResult

        data class Failure(
            val reason: FailureReason,
            val detail: String,
        ) : ParseResult
    }

    @Suppress("ReturnCount")
    fun parse(jsonBytes: ByteArray): ParseResult {
        if (jsonBytes.size > ConnectorIndexConstants.MAX_INDEX_BYTES) {
            return ParseResult.Failure(
                FailureReason.PAYLOAD_TOO_LARGE,
                "Payload size ${jsonBytes.size} exceeds maximum ${ConnectorIndexConstants.MAX_INDEX_BYTES} bytes",
            )
        }
        val text =
            try {
                Charsets.UTF_8
                    .newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(jsonBytes))
                    .toString()
            } catch (e: java.nio.charset.CharacterCodingException) {
                return ParseResult.Failure(FailureReason.INVALID_JSON, "Payload is not valid UTF-8: ${e.message}")
            } catch (e: IllegalArgumentException) {
                return ParseResult.Failure(FailureReason.INVALID_JSON, "Payload is not valid UTF-8: ${e.message}")
            }

        val node =
            try {
                StrictJsonReader(text).parseDocument()
            } catch (e: DuplicateKeyException) {
                return ParseResult.Failure(FailureReason.DUPLICATE_KEY, e.message ?: "Duplicate key")
            } catch (e: ExcessiveDepthException) {
                return ParseResult.Failure(FailureReason.EXCESSIVE_NESTING_DEPTH, e.message ?: "Excessive depth")
            } catch (e: IllegalArgumentException) {
                return ParseResult.Failure(FailureReason.INVALID_JSON, e.message ?: "Invalid JSON")
            }

        return validateAndBuild(node)
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
    private fun validateAndBuild(rootNode: JsonNode): ParseResult {
        val root =
            rootNode as? JsonNode.Obj
                ?: return ParseResult.Failure(FailureReason.INVALID_JSON, "Root must be a JSON object")

        val entries = root.map
        val allowedRootKeys =
            setOf(
                "schemaVersion",
                "publisherId",
                "keyId",
                "sequence",
                "issuedAt",
                "expiresAt",
                "packages",
            )
        val unknownKeys = entries.keys - allowedRootKeys
        if (unknownKeys.isNotEmpty()) {
            return ParseResult.Failure(
                FailureReason.INVALID_FIELD_VALUE,
                "Unknown top-level fields: $unknownKeys",
            )
        }

        val schemaVersion =
            (entries["schemaVersion"] as? JsonNode.Str)?.value
                ?: return ParseResult.Failure(
                    FailureReason.INVALID_FIELD_VALUE,
                    "Missing or invalid schemaVersion",
                )
        if (schemaVersion != ConnectorIndexConstants.SCHEMA_VERSION_V1) {
            return ParseResult.Failure(
                FailureReason.UNKNOWN_SCHEMA_VERSION,
                "Unsupported schemaVersion: $schemaVersion",
            )
        }

        val publisherId =
            (entries["publisherId"] as? JsonNode.Str)?.value
                ?: return ParseResult.Failure(
                    FailureReason.INVALID_FIELD_VALUE,
                    "Missing or invalid publisherId",
                )
        if (!ConnectorIndexConstants.ID_REGEX.matches(publisherId)) {
            return ParseResult.Failure(
                FailureReason.INVALID_FIELD_VALUE,
                "Invalid publisherId format: $publisherId",
            )
        }

        val keyId =
            (entries["keyId"] as? JsonNode.Str)?.value
                ?: return ParseResult.Failure(
                    FailureReason.INVALID_FIELD_VALUE,
                    "Missing or invalid keyId",
                )
        if (!ConnectorIndexConstants.ID_REGEX.matches(keyId)) {
            return ParseResult.Failure(
                FailureReason.INVALID_FIELD_VALUE,
                "Invalid keyId format: $keyId",
            )
        }

        val sequence =
            (entries["sequence"] as? JsonNode.Num)?.value
                ?: return ParseResult.Failure(
                    FailureReason.INVALID_FIELD_VALUE,
                    "Missing or invalid sequence number",
                )
        if (sequence <= 0) {
            return ParseResult.Failure(
                FailureReason.INVALID_FIELD_VALUE,
                "Sequence must be strictly positive: $sequence",
            )
        }

        val issuedAt =
            (entries["issuedAt"] as? JsonNode.Num)?.value
                ?: return ParseResult.Failure(
                    FailureReason.INVALID_FIELD_VALUE,
                    "Missing or invalid issuedAt timestamp",
                )
        val expiresAt =
            (entries["expiresAt"] as? JsonNode.Num)?.value
                ?: return ParseResult.Failure(
                    FailureReason.INVALID_FIELD_VALUE,
                    "Missing or invalid expiresAt timestamp",
                )
        if (expiresAt < issuedAt) {
            return ParseResult.Failure(
                FailureReason.INVALID_FIELD_VALUE,
                "expiresAt ($expiresAt) cannot be earlier than issuedAt ($issuedAt)",
            )
        }

        val packagesArr =
            entries["packages"] as? JsonNode.Arr
                ?: return ParseResult.Failure(
                    FailureReason.INVALID_FIELD_VALUE,
                    "Missing or invalid packages array",
                )
        if (packagesArr.items.size > ConnectorIndexConstants.MAX_PACKAGES) {
            return ParseResult.Failure(
                FailureReason.TOO_MANY_PACKAGES,
                "Package count ${packagesArr.items.size} exceeds maximum ${ConnectorIndexConstants.MAX_PACKAGES}",
            )
        }

        val packages = mutableListOf<ConnectorIndexEntry>()
        val seenPackageVersions = mutableSetOf<String>()

        for (item in packagesArr.items) {
            val entryObj =
                item as? JsonNode.Obj
                    ?: return ParseResult.Failure(FailureReason.INVALID_FIELD_VALUE, "Package item must be an object")
            when (val entryResult = validatePackageEntry(entryObj)) {
                is PackageEntryResult.Failure -> {
                    return ParseResult.Failure(entryResult.reason, entryResult.detail)
                }

                is PackageEntryResult.Success -> {
                    val key = "${entryResult.entry.packageId}@${entryResult.entry.version}"
                    if (!seenPackageVersions.add(key)) {
                        return ParseResult.Failure(
                            FailureReason.DUPLICATE_PACKAGE_ENTRY,
                            "Duplicate package version entry: $key",
                        )
                    }
                    packages.add(entryResult.entry)
                }
            }
        }

        return ParseResult.Success(
            ConnectorIndex(
                schemaVersion = schemaVersion,
                publisherId = publisherId,
                keyId = keyId,
                sequence = sequence,
                issuedAt = issuedAt,
                expiresAt = expiresAt,
                packages = packages,
            ),
        )
    }

    private sealed interface PackageEntryResult {
        data class Success(
            val entry: ConnectorIndexEntry,
        ) : PackageEntryResult

        data class Failure(
            val reason: FailureReason,
            val detail: String,
        ) : PackageEntryResult
    }

    @Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod")
    private fun validatePackageEntry(obj: JsonNode.Obj): PackageEntryResult {
        val fields = obj.map
        val allowedKeys =
            setOf(
                "packageId",
                "version",
                "sourceUrl",
                "archiveSha256",
                "sizeBytes",
                "license",
                "sourceNoticeUrl",
                "minAppVersion",
            )
        val unknown = fields.keys - allowedKeys
        if (unknown.isNotEmpty()) {
            return PackageEntryResult.Failure(
                FailureReason.INVALID_FIELD_VALUE,
                "Unknown package entry fields: $unknown",
            )
        }

        val packageId =
            (fields["packageId"] as? JsonNode.Str)?.value
                ?: return PackageEntryResult.Failure(
                    FailureReason.INVALID_FIELD_VALUE,
                    "Missing or invalid packageId",
                )
        if (!ConnectorIndexConstants.PACKAGE_ID_REGEX.matches(packageId)) {
            return PackageEntryResult.Failure(
                FailureReason.INVALID_FIELD_VALUE,
                "Invalid packageId: $packageId",
            )
        }

        val version =
            (fields["version"] as? JsonNode.Str)?.value
                ?: return PackageEntryResult.Failure(
                    FailureReason.INVALID_FIELD_VALUE,
                    "Missing or invalid version",
                )
        if (!ConnectorIndexConstants.VERSION_REGEX.matches(version)) {
            return PackageEntryResult.Failure(
                FailureReason.INVALID_FIELD_VALUE,
                "Invalid fixed version: $version",
            )
        }

        val sourceUrl =
            (fields["sourceUrl"] as? JsonNode.Str)?.value
                ?: return PackageEntryResult.Failure(
                    FailureReason.INVALID_FIELD_VALUE,
                    "Missing or invalid sourceUrl",
                )
        if (!isValidUrl(sourceUrl)) {
            return PackageEntryResult.Failure(
                FailureReason.INVALID_FIELD_VALUE,
                "Invalid HTTPS sourceUrl: $sourceUrl",
            )
        }

        val archiveSha256 =
            (fields["archiveSha256"] as? JsonNode.Str)?.value
                ?: return PackageEntryResult.Failure(
                    FailureReason.INVALID_FIELD_VALUE,
                    "Missing or invalid archiveSha256",
                )
        if (!ConnectorIndexConstants.SHA256_REGEX.matches(archiveSha256)) {
            return PackageEntryResult.Failure(
                FailureReason.INVALID_FIELD_VALUE,
                "Invalid archiveSha256: $archiveSha256",
            )
        }

        val sizeBytes =
            (fields["sizeBytes"] as? JsonNode.Num)?.value
                ?: return PackageEntryResult.Failure(
                    FailureReason.INVALID_FIELD_VALUE,
                    "Missing or invalid sizeBytes",
                )
        if (sizeBytes <= 0 || sizeBytes > ConnectorIndexConstants.MAX_PACKAGE_SIZE) {
            return PackageEntryResult.Failure(
                FailureReason.INVALID_FIELD_VALUE,
                "Invalid sizeBytes: $sizeBytes",
            )
        }

        val license =
            (fields["license"] as? JsonNode.Str)?.value
                ?: return PackageEntryResult.Failure(
                    FailureReason.INVALID_FIELD_VALUE,
                    "Missing or invalid license",
                )
        if (!ConnectorIndexConstants.LICENSE_REGEX.matches(license)) {
            return PackageEntryResult.Failure(
                FailureReason.INVALID_FIELD_VALUE,
                "Invalid license: $license",
            )
        }

        val sourceNoticeUrl = (fields["sourceNoticeUrl"] as? JsonNode.Str)?.value
        if (sourceNoticeUrl != null && !isValidUrl(sourceNoticeUrl)) {
            return PackageEntryResult.Failure(
                FailureReason.INVALID_FIELD_VALUE,
                "Invalid sourceNoticeUrl: $sourceNoticeUrl",
            )
        }

        val minAppVersion = (fields["minAppVersion"] as? JsonNode.Str)?.value

        return PackageEntryResult.Success(
            ConnectorIndexEntry(
                packageId = packageId,
                version = version,
                sourceUrl = sourceUrl,
                archiveSha256 = archiveSha256,
                sizeBytes = sizeBytes,
                license = license,
                sourceNoticeUrl = sourceNoticeUrl,
                minAppVersion = minAppVersion,
            ),
        )
    }

    private fun isValidUrl(url: String): Boolean =
        runCatching {
            val uri = URI(url)
            url.length <= ConnectorIndexConstants.MAX_URL_LENGTH &&
                uri.scheme == "https" &&
                !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null &&
                uri.rawQuery == null &&
                uri.rawFragment == null &&
                '$' !in url && '{' !in url && '}' !in url
        }.getOrDefault(false)

    private class DuplicateKeyException(
        message: String,
    ) : IllegalArgumentException(message)

    private class ExcessiveDepthException(
        message: String,
    ) : IllegalArgumentException(message)

    private sealed interface JsonNode {
        data class Str(
            val value: String,
        ) : JsonNode

        data class Num(
            val value: Long,
        ) : JsonNode

        data class Bool(
            val value: Boolean,
        ) : JsonNode

        data object Null : JsonNode

        data class Arr(
            val items: List<JsonNode>,
        ) : JsonNode

        data class Obj(
            val map: Map<String, JsonNode>,
        ) : JsonNode
    }

    @Suppress("TooManyFunctions")
    private class StrictJsonReader(
        private val s: String,
    ) {
        private var pos = 0
        private var depth = 0

        fun parseDocument(): JsonNode {
            skipWhitespace()
            val node = parseValue()
            skipWhitespace()
            if (pos < s.length) throw IllegalArgumentException("Trailing characters at pos $pos")
            return node
        }

        private fun skipWhitespace() {
            while (pos < s.length && s[pos] in " \t\r\n") pos++
        }

        private fun parseValue(): JsonNode {
            skipWhitespace()
            if (pos >= s.length) throw IllegalArgumentException("Unexpected end of JSON input")
            return when (val c = s[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonNode.Str(parseString())
                't' -> parseLiteral("true", JsonNode.Bool(true))
                'f' -> parseLiteral("false", JsonNode.Bool(false))
                'n' -> parseLiteral("null", JsonNode.Null)
                in '0'..'9', '-' -> parseNumber()
                else -> throw IllegalArgumentException("Unexpected character '$c' at pos $pos")
            }
        }

        private fun parseObject(): JsonNode.Obj {
            pos++ // consume '{'
            enterDepth()
            val map = LinkedHashMap<String, JsonNode>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                leaveDepth()
                return JsonNode.Obj(map)
            }
            while (true) {
                skipWhitespace()
                expect('"')
                val key = readStringBody()
                if (map.containsKey(key)) {
                    throw DuplicateKeyException("Duplicate key '$key' at pos $pos")
                }
                skipWhitespace()
                expect(':')
                val value = parseValue()
                map[key] = value
                skipWhitespace()
                when (val c = peek()) {
                    ',' -> {
                        pos++
                    }

                    '}' -> {
                        pos++
                        leaveDepth()
                        return JsonNode.Obj(map)
                    }

                    else -> {
                        throw IllegalArgumentException("Expected ',' or '}' but found '$c' at pos $pos")
                    }
                }
            }
        }

        private fun parseArray(): JsonNode.Arr {
            pos++ // consume '['
            enterDepth()
            val items = ArrayList<JsonNode>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                leaveDepth()
                return JsonNode.Arr(items)
            }
            while (true) {
                items.add(parseValue())
                skipWhitespace()
                when (val c = peek()) {
                    ',' -> {
                        pos++
                    }

                    ']' -> {
                        pos++
                        leaveDepth()
                        return JsonNode.Arr(items)
                    }

                    else -> {
                        throw IllegalArgumentException("Expected ',' or ']' but found '$c' at pos $pos")
                    }
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            return readStringBody()
        }

        private fun readStringBody(): String {
            val sb = java.lang.StringBuilder()
            while (pos < s.length) {
                val c = s[pos++]
                when (c) {
                    '"' -> {
                        return sb.toString()
                    }

                    '\\' -> {
                        appendEscape(sb)
                    }

                    else -> {
                        if (c.code < 0x20) throw IllegalArgumentException("Unescaped control char at pos ${pos - 1}")
                        sb.append(c)
                    }
                }
            }
            throw IllegalArgumentException("Unterminated string")
        }

        private fun appendEscape(sb: java.lang.StringBuilder) {
            if (pos >= s.length) throw IllegalArgumentException("Unterminated escape")
            when (val c = s[pos++]) {
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
                '/' -> sb.append('/')
                'b' -> sb.append('\b')
                'f' -> sb.append(0x0C.toChar())
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                'u' -> appendUnicode(sb)
                else -> throw IllegalArgumentException("Invalid escape '\\$c' at pos ${pos - 1}")
            }
        }

        private fun appendUnicode(sb: java.lang.StringBuilder) {
            if (s.length - pos < 4) throw IllegalArgumentException("Truncated unicode escape")
            val hex = s.substring(pos, pos + 4)
            for (c in hex) {
                if (c !in '0'..'9' && c.lowercaseChar() !in 'a'..'f') {
                    throw IllegalArgumentException("Invalid hex in unicode escape '\\u$hex'")
                }
            }
            sb.append(hex.toInt(16).toChar())
            pos += 4
        }

        @Suppress("ThrowsCount")
        private fun parseNumber(): JsonNode.Num {
            val start = pos
            if (peek() == '-') pos++
            if (pos >= s.length || s[pos] !in '0'..'9') throw IllegalArgumentException("Invalid number at pos $start")
            if (s[pos] == '0') {
                pos++
                if (pos < s.length && s[pos] in '0'..'9') throw IllegalArgumentException("Leading zero at pos $start")
            } else {
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            if (pos < s.length && (s[pos] == '.' || s[pos].lowercaseChar() == 'e')) {
                throw IllegalArgumentException("Floats and scientific notation not accepted at pos $pos")
            }
            val numStr = s.substring(start, pos)
            val value = numStr.toLongOrNull() ?: throw IllegalArgumentException("Number out of 64-bit range: $numStr")
            return JsonNode.Num(value)
        }

        private fun parseLiteral(
            literal: String,
            node: JsonNode,
        ): JsonNode {
            if (!s.startsWith(literal, pos)) throw IllegalArgumentException("Invalid literal at pos $pos")
            pos += literal.length
            return node
        }

        private fun enterDepth() {
            depth++
            if (depth > ConnectorIndexConstants.MAX_JSON_DEPTH) {
                throw ExcessiveDepthException("Exceeded maximum JSON depth ${ConnectorIndexConstants.MAX_JSON_DEPTH}")
            }
        }

        private fun leaveDepth() {
            depth--
        }

        private fun expect(expected: Char) {
            if (peek() != expected) {
                throw IllegalArgumentException("Expected '$expected' but found '${peek()}' at pos $pos")
            }
            pos++
        }

        private fun peek(): Char {
            if (pos >= s.length) throw IllegalArgumentException("Unexpected end of input")
            return s[pos]
        }
    }
}
