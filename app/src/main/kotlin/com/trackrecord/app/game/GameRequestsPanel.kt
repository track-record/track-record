package com.trackrecord.app.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.trackrecord.app.R
import com.trackrecord.app.ui.components.OutlinedButton

/**
 * A game's lookups and covers (feedback round 48): what is done of what is queued, and Stop, which ends them for the
 * rest of the game. In the game's settings and, while they run, in the library; the co-host has the same.
 */
@Composable
fun GameRequestsPanel(requests: GameRequests, onStop: () -> Unit, modifier: Modifier = Modifier, title: Boolean = true) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (title) Text(stringResource(R.string.requests_title), style = MaterialTheme.typography.titleSmall)
        if (requests.stopped) {
            Text(stringResource(R.string.requests_stopped), style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        if (!requests.running) {
            Text(stringResource(R.string.requests_none), style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        Progress(stringResource(R.string.requests_years, requests.yearsDone, requests.yearsTotal), requests.yearsDone, requests.yearsTotal)
        Progress(stringResource(R.string.requests_covers, requests.coversDone, requests.coversTotal), requests.coversDone, requests.coversTotal)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.requests_stop_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onStop, modifier = Modifier.padding(start = 8.dp)) { Text(stringResource(R.string.requests_stop)) }
        }
    }
}

@Composable
private fun Progress(label: String, done: Int, total: Int) {
    if (total == 0) return
    Text(label, style = MaterialTheme.typography.bodyMedium)
    LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), drawStopIndicator = {})
}
