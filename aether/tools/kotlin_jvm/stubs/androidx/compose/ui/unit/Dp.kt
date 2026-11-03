package androidx.compose.ui.unit
class Dp(val value: Int)
val Int.dp: Dp get() = Dp(this)
