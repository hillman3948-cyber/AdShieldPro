package android.content
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

class AssetManager(private val base: File) {
    fun open(name: String): InputStream = FileInputStream(File(base, name))
}
class SharedPreferences {
    private val values = HashMap<String, String?>()
    fun getString(key: String, def: String?): String? = values[key] ?: def
    fun edit(): Editor = Editor(values)
}
class Editor(private val values: HashMap<String, String?>) {
    fun putString(key: String, value: String?): Editor { values[key] = value; return this }
    fun apply() {}
}
open class Context(val assets: AssetManager) {
    constructor() : this(AssetManager(File(".")))
    companion object { const val MODE_PRIVATE: Int = 0 }
    private val shared = SharedPreferences()
    val applicationContext: Context get() = this
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences = shared
}
