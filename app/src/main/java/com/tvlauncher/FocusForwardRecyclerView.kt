package com.tvlauncher

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * RecyclerView that hands focus to a view outside of itself when a scroll direction is
 * exhausted. Used for the D-pad navigation between the app grid and the banner row on TV:
 * when the user walks past the last row of apps with DOWN, focus jumps to the banners, and
 * walking UP out of the banners returns to the grid.
 */
class FocusForwardRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    /**
     * Called with the direction that failed inside this RecyclerView; returns the view that
     * should receive the focus, or null for the default behaviour.
     */
    var nextFocusFallback: ((direction: Int) -> View?)? = null

    override fun onFocusSearchFailed(
        focused: View,
        focusDirection: Int,
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State
    ): View? {
        return nextFocusFallback?.invoke(focusDirection)
            ?: super.onFocusSearchFailed(focused, focusDirection, recycler, state)
    }
}
