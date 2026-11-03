package kotlinx.coroutines.flow
import kotlinx.coroutines.CoroutineScope
interface Flow<out T>
interface StateFlow<out T> : Flow<T> { val value: T }
class SharingStarted {
    companion object { fun WhileSubscribed(millis: Long): SharingStarted = SharingStarted() }
}
fun <T> flowOf(vararg items: T): Flow<T> = object : Flow<T> {}
fun <T> Flow<T>.stateIn(scope: CoroutineScope, started: SharingStarted, initial: T): StateFlow<T> =
    object : StateFlow<T> { override val value: T = initial }
