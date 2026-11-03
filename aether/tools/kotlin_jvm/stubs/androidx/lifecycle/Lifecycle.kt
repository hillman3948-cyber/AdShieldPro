package androidx.lifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SimpleScope
open class ViewModel
val ViewModel.viewModelScope: CoroutineScope get() = SimpleScope
