package io.github.vortexmusic.player

import android.app.Activity
import android.content.Intent

class ToolEntry(
    val title: String,
    val subtitle: String,
    val iconKind: Int,
    val action: () -> Unit
)

class ToolHost(
    val activity: Activity,
    val ui: Ui,
    val songs: () -> List<Song>,
    val reloadData: () -> Unit,
    val restartService: () -> Unit
) {
    val resultHandlers = ArrayList<(Int, Int, Intent?) -> Boolean>()
}

// Yeni araçlar buraya eklenir: install() ve entries() içine birer satır yeter.
object Tools {

    fun install(host: ToolHost) {
        ThemeTool.install(host)
        BackupTool.install(host)
        DuplicatesTool.install(host)
        LyricsTool.install(host)
    }

    fun entries(host: ToolHost): List<ToolEntry> {
        val list = ArrayList<ToolEntry>()
        list.addAll(ThemeTool.entries(host))
        list.addAll(LyricsTool.entries(host))
        list.addAll(DuplicatesTool.entries(host))
        list.addAll(BackupTool.entries(host))
        return list
    }
}