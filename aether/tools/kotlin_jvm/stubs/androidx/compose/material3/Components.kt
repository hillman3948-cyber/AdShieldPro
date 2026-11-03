package androidx.compose.material3
object ButtonDefaults {
    fun buttonColors(containerColor: Any? = null, contentColor: Any? = null): Any = Unit
    fun outlinedButtonColors(contentColor: Any? = null, containerColor: Any? = null): Any = Unit
}
object CardDefaults { fun cardColors(containerColor: Any? = null): Any = Unit }
class Typography {
    val headlineMedium: Any = Unit; val titleMedium: Any = Unit; val titleSmall: Any = Unit
    val bodyLarge: Any = Unit; val bodyMedium: Any = Unit; val bodySmall: Any = Unit
    val labelLarge: Any = Unit; val labelMedium: Any = Unit; val labelSmall: Any = Unit
}
object MaterialTheme { val typography = Typography() }
fun Button(onClick: () -> Unit, modifier: Any? = null, colors: Any? = null, content: () -> Unit = {}) { content() }
fun OutlinedButton(onClick: () -> Unit, modifier: Any? = null, colors: Any? = null, content: () -> Unit = {}) { content() }
fun OutlinedTextField(value: String, onValueChange: (String) -> Unit, label: Any? = null,
                      singleLine: Boolean = false, modifier: Any? = null, colors: Any? = null) { }
fun Switch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Any? = null) { }
fun Card(modifier: Any? = null, colors: Any? = null, content: () -> Unit = {}) { content() }
fun Text(text: String, modifier: Any? = null, style: Any? = null, color: Any? = null,
         fontWeight: Any? = null, maxLines: Int = Int.MAX_VALUE) { }
