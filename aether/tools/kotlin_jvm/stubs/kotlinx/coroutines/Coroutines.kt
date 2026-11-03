package kotlinx.coroutines
interface CoroutineScope
object SimpleScope : CoroutineScope
fun CoroutineScope.launch(block: suspend () -> Unit) { /* stub: no-op */ }
