package io.github.soulxyz.xyprt.render

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import io.github.soulxyz.xyprt.R
import io.github.soulxyz.xyprt.model.LabelFont
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Font registry with two deliberately separate namespaces:
 * - bundled [LabelFont] values are permanent offline fallbacks;
 * - remote fonts are addressed by stable asset slug and may arrive/remove independently of APKs.
 *
 * A remote-font failure never makes existing templates unreadable: the renderer immediately falls
 * back to the bundled enum stored in the same TextElement.
 */
object FontRegistry {
    private val bundled = mutableMapOf<LabelFont, Typeface>()
    // 同时跟踪字体文件路径，使清理时能精确删除自己的文件，不依赖目录约定。
    private val remote = ConcurrentHashMap<String, Typeface>()
    private val remoteFiles = ConcurrentHashMap<String, File>()

    fun init(context: Context) {
        fun load(font: LabelFont, resId: Int) {
            runCatching { ResourcesCompat.getFont(context, resId) }.getOrNull()?.let { bundled[font] = it }
        }
        load(LabelFont.OSWALD, R.font.oswald)
        load(LabelFont.ZILLA_SLAB, R.font.zilla_slab)
        load(LabelFont.COMFORTAA, R.font.comfortaa)
        load(LabelFont.CAVEAT, R.font.caveat)
        load(LabelFont.PACIFICO, R.font.pacifico)
    }

    fun registerRemote(assetId: String, file: File): Boolean {
        val id = assetId.trim()
        if (id.isEmpty() || !file.isFile || file.length() <= 0L) return false
        val face = runCatching { Typeface.Builder(file).build() }
            .recoverCatching { Typeface.createFromFile(file) }
            .getOrNull() ?: return false
        remote[id] = face
        remoteFiles[id] = file
        return true
    }

    fun unregisterRemote(assetId: String) {
        val id = assetId.trim()
        remote.remove(id)
        remoteFiles.remove(id)
    }
    fun remote(assetId: String?): Typeface? = assetId?.trim()?.takeIf { it.isNotEmpty() }?.let(remote::get)
    fun availableRemoteIds(): Set<String> = remote.keys.toSet()
    fun remoteFontCount(): Int = remote.size
    /** 清除所有字体内存引用并删除其磁盘文件。返回被删除的文件数。 */
    fun clearRemoteFonts(): Int {
        val files = remoteFiles.values.toList()
        remote.clear()
        remoteFiles.clear()
        var deleted = 0
        files.forEach { f -> if (f.isFile && f.delete()) deleted++ }
        return deleted
    }
    /** 当前注册的字体磁盘文件（用于统计大小） */
    fun remoteFontFiles(): List<File> = remoteFiles.values.toList()

    fun base(font: LabelFont, remoteAssetId: String? = null): Typeface {
        remote(remoteAssetId)?.let { return it }
        return when (font) {
            LabelFont.SANS -> Typeface.SANS_SERIF
            LabelFont.SERIF -> Typeface.SERIF
            LabelFont.MONO -> Typeface.MONOSPACE
            else -> bundled[font] ?: Typeface.SANS_SERIF
        }
    }
}
