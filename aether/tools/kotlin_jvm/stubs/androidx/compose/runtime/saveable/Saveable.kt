package androidx.compose.runtime.saveable
fun <T> rememberSaveable(calculation: () -> T): T = calculation()
