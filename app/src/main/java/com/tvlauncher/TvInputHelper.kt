package com.tvlauncher

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputManager

/** One entry of the "Entradas" tab: an HDMI/AV input, or live TV when [id] is null. */
data class TvInputEntry(val id: String?, val label: String)

object TvInputHelper {

    /** Inputs of the TV (HDMI, AV...) plus live TV. Empty on devices without a TV input service. */
    fun listInputs(context: Context): List<TvInputEntry> {
        return try {
            val manager = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
                ?: return emptyList()
            val all = manager.tvInputList
            if (all.isEmpty()) return emptyList()

            val inputs = mutableListOf<TvInputEntry>()
            all.forEach { info ->
                if (info.isPassthroughInput) {
                    val label = (info.loadCustomLabel(context) ?: info.loadLabel(context))
                        ?.toString()
                        .orEmpty()
                    inputs.add(TvInputEntry(info.id, label.ifBlank { info.id }))
                }
            }
            val result = mutableListOf(TvInputEntry(null, context.getString(R.string.inputs_live_tv)))
            result.addAll(inputs.sortedBy { it.label.lowercase() })
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun launch(context: Context, entry: TvInputEntry): Boolean {
        TvHomeOverrideService.pauseOverrideFor(8000)
        return try {
            val uri = if (entry.id != null) {
                TvContract.buildChannelUriForPassthroughInput(entry.id)
            } else {
                TvContract.Channels.CONTENT_URI
            }
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (_: Exception) {
            false
        }
    }
}
