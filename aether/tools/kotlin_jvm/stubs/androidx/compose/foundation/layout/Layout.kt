package androidx.compose.foundation.layout
import androidx.compose.ui.Modifier
object Arrangement {
    val SpaceBetween: Any = Unit
    fun spacedBy(v: Any?): Any = Unit
}
fun Modifier.fillMaxWidth(): Modifier = this
fun Modifier.fillMaxSize(): Modifier = this
fun Modifier.padding(v: Any?): Modifier = this
fun Modifier.height(v: Any?): Modifier = this
fun Modifier.weight(v: Float): Modifier = this
fun Column(modifier: Any? = null, verticalArrangement: Any? = null, content: () -> Unit = {}) { content() }
fun Row(modifier: Any? = null, horizontalArrangement: Any? = null, content: () -> Unit = {}) { content() }
fun Spacer(modifier: Any? = null) { }
