package androidx.compose.runtime
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.TYPE, AnnotationTarget.PROPERTY,
        AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY_GETTER)
annotation class Composable
interface State<out T> { val value: T }
interface MutableState<T> : State<T> { override var value: T }
class MutableStateImpl<T>(initial: T) : MutableState<T> { override var value: T = initial }
fun <T> mutableStateOf(value: T): MutableState<T> = MutableStateImpl(value)
fun <T> remember(calculation: () -> T): T = calculation()
operator fun <T> State<T>.getValue(thisRef: Any?, property: Any?): T = value
operator fun <T> MutableState<T>.setValue(thisRef: Any?, property: Any?, v: T) { value = v }
