package androidx.lifecycle.compose
import androidx.compose.runtime.State
import kotlinx.coroutines.flow.StateFlow
fun <T> StateFlow<T>.collectAsStateWithLifecycle(): State<T> =
    object : State<T> { override val value: T = this@collectAsStateWithLifecycle.value }
