package com.helix.app.update

/** SemVer precedence, independent of Android's deliberately fixed preview versionCode. */
internal class AppVersion private constructor(
    private val numbers: List<java.math.BigInteger>,
    private val prerelease: List<String>,
) : Comparable<AppVersion> {
    @Suppress("ReturnCount") // Lexicographic SemVer comparison stops at the first differing component.
    override fun compareTo(other: AppVersion): Int {
        numbers.zip(other.numbers).forEach { (left, right) ->
            left.compareTo(right).takeIf { it != 0 }?.let { return it }
        }
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) {
            return when {
                prerelease.isEmpty() && other.prerelease.isEmpty() -> 0
                prerelease.isEmpty() -> 1
                else -> -1
            }
        }
        prerelease.zip(other.prerelease).forEach { (left, right) ->
            compareIdentifier(left, right).takeIf { it != 0 }?.let { return it }
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    companion object {
        private val pattern =
            Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z.-]+))?(?:\\+([0-9A-Za-z.-]+))?")

        fun parse(value: String): AppVersion {
            require(value.length in 5..100)
            val match = requireNotNull(pattern.matchEntire(value))
            val pre =
                match.groupValues[4]
                    .takeIf { it.isNotEmpty() }
                    ?.split('.')
                    .orEmpty()
            val build =
                match.groupValues[5]
                    .takeIf { it.isNotEmpty() }
                    ?.split('.')
                    .orEmpty()
            require((pre + build).none { it.isEmpty() })
            require(pre.none { it.all(Char::isDigit) && it.length > 1 && it.startsWith('0') })
            return AppVersion((1..3).map { match.groupValues[it].toBigInteger() }, pre)
        }

        private fun compareIdentifier(
            left: String,
            right: String,
        ): Int {
            val leftNumber = left.takeIf { it.all(Char::isDigit) }?.toBigIntegerOrNull()
            val rightNumber = right.takeIf { it.all(Char::isDigit) }?.toBigIntegerOrNull()
            return when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> left.compareTo(right)
            }
        }
    }
}
