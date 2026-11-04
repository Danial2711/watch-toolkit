package dev.kafli.rftoolkit.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text

data class HomeEntry(val title: String, val subtitle: String, val route: String)

@Composable
fun HomeScreen(entries: List<HomeEntry>, surveyRunning: Boolean, onOpen: (String) -> Unit) {
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text("RF Toolkit") } }
            entries.forEach { e ->
                item {
                    Button(
                        onClick = { onOpen(e.route) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(e.title) },
                        secondaryLabel = {
                            Text(if (e.route == "survey" && surveyRunning) "● recording" else e.subtitle)
                        },
                    )
                }
            }
        }
    }
}
