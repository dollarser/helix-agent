package com.helix.core.storage.internal

import com.helix.core.storage.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniJsonTest {
    @Test
    fun `parses a fixed-shape object with all value kinds`() {
        val value =
            MiniJson.parse("""{"a":"x\ny","b":42,"c":-7,"d":true,"e":false,"f":null,"g":[1,"z"]}""")
        val obj = value as Value.Obj
        assertEquals(listOf("a", "b", "c", "d", "e", "f", "g"), obj.entries.keys.toList())
        assertEquals("x\ny", (obj.entries.getValue("a") as Value.Str).value)
        assertEquals(42L, (obj.entries.getValue("b") as Value.Num).value)
        assertEquals(-7L, (obj.entries.getValue("c") as Value.Num).value)
        assertEquals(Value.True, obj.entries.getValue("d"))
        assertEquals(Value.False, obj.entries.getValue("e"))
        assertEquals(Value.Null, obj.entries.getValue("f"))
        val arr = obj.entries.getValue("g") as Value.Arr
        assertEquals(listOf<Value.Num>(Value.Num(1L)), listOf(arr.items[0]))
        assertEquals("z", (arr.items[1] as Value.Str).value)
    }

    @Test
    fun `parses unicode escapes including surrogate-range values`() {
        val value = MiniJson.parse("\"\\u0041\\u00e9\"") as Value.Str
        assertEquals("Aé", value.value)
    }

    @Test
    fun `allows insignificant whitespace between tokens`() {
        val value = MiniJson.parse("  { \"a\" : [ 1 , 2 ] }  ") as Value.Obj
        assertEquals(listOf(1L, 2L), (value.entries.getValue("a") as Value.Arr).items.map { (it as Value.Num).value })
    }

    @Test
    fun `rejects floats and exponents`() {
        assertThrows("float") { MiniJson.parse("1.5") }
        assertThrows("exponent") { MiniJson.parse("1e3") }
    }

    @Test
    fun `rejects leading zeros`() {
        assertThrows("leading zero") { MiniJson.parse("01") }
        assertThrows("negative leading zero") { MiniJson.parse("-01") }
    }

    @Test
    fun `rejects numbers out of 64-bit range`() {
        assertThrows("overflow") { MiniJson.parse("9223372036854775808") }
    }

    @Test
    fun `rejects duplicate object keys`() {
        assertThrows("duplicate key") { MiniJson.parse("""{"a":1,"a":2}""") }
    }

    @Test
    fun `rejects trailing content`() {
        assertThrows("trailing") { MiniJson.parse("1 2") }
        assertThrows("trailing garbage") { MiniJson.parse("""{"a":1}x""") }
    }

    @Test
    fun `rejects unescaped control characters in strings`() {
        assertThrows("raw newline") { MiniJson.parse("\"a\nb\"") }
        assertThrows("raw tab") { MiniJson.parse("\"a\tb\"") }
    }

    @Test
    fun `rejects malformed escapes`() {
        assertThrows("unknown escape") { MiniJson.parse("\"\\q\"") }
        assertThrows("bad unicode") { MiniJson.parse("\"\\u00G1\"") }
        assertThrows("truncated unicode") { MiniJson.parse("\"\\u00\"") }
    }

    @Test
    fun `rejects empty input and wrong top level`() {
        assertThrows("empty") { MiniJson.parse("") }
        val top = MiniJson.parse("[1]")
        assertTrue(top is Value.Arr)
    }
}
