package com.rssai

import android.content.Context
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun rememberPersistentReaderListState(articleId: String): LazyListState {
    val context = LocalContext.current
    val prefs = remember(articleId) {
        context.getSharedPreferences("rss-ai-reader-memory", Context.MODE_PRIVATE)
    }
    val state = rememberLazyListState(
        initialFirstVisibleItemIndex = prefs.getInt("$articleId:index", 0),
        initialFirstVisibleItemScrollOffset = prefs.getInt("$articleId:offset", 0),
    )
    LaunchedEffect(articleId, state) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                prefs.edit()
                    .putInt("$articleId:index", index)
                    .putInt("$articleId:offset", offset)
                    .apply()
            }
    }
    return state
}
