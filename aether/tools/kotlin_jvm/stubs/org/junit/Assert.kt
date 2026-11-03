package org.junit

annotation class Test

object Assert {
    fun assertEquals(expected: Any?, actual: Any?) {
        if (expected != actual) throw AssertionError("expected <$expected> but was <$actual>")
    }
    fun assertTrue(condition: Boolean) {
        if (!condition) throw AssertionError("expected true")
    }
    fun assertFalse(condition: Boolean) {
        if (condition) throw AssertionError("expected false")
    }
    fun assertNotNull(value: Any?) {
        if (value == null) throw AssertionError("expected non-null")
    }
    fun fail(message: String): Nothing = throw AssertionError(message)
}
