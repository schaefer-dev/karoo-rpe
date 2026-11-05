package com.karoo_rpe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.karoo_rpe.data.RideRatingRepository
import com.karoo_rpe.extension.Feel
import com.karoo_rpe.extension.RPE_MAX
import com.karoo_rpe.extension.RPE_MIN
import com.karoo_rpe.extension.RideRating
import com.karoo_rpe.extension.rpeLabel
import kotlinx.coroutines.launch

/**
 * Full-screen RPE + Feel picker. Opened from the in-ride field, from the Control Center
 * notification posted on a manual pause ([ACTION_RATE_RIDE]), from the launcher, and from
 * Karoo's extension list. Each tap is saved immediately; the extension writes it to the FIT file.
 */
class RateRideActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = RideRatingRepository(applicationContext)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    RateScreen(repository, onDone = ::finish)
                }
            }
        }
    }

    companion object {
        const val ACTION_RATE_RIDE = "com.karoo_rpe.RATE_RIDE"
    }
}

@Composable
private fun RateScreen(repository: RideRatingRepository, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val rating by remember { repository.ratingFlow }.collectAsState(initial = RideRating())
    val syncStatus by remember { repository.syncStatusFlow }.collectAsState(initial = null)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("How hard was it?", style = MaterialTheme.typography.titleLarge)
        Text(
            text = rating.rpe?.let { "RPE $it · ${rpeLabel(it)}" } ?: "Pick RPE 1–10",
            style = MaterialTheme.typography.bodyMedium,
        )
        (RPE_MIN..RPE_MAX).chunked(5).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { value ->
                    ChoiceButton(
                        text = value.toString(),
                        selected = rating.rpe == value,
                        modifier = Modifier.weight(1f).height(56.dp),
                        fontSize = 22,
                    ) { scope.launch { repository.setRpe(value) } }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text("How did you feel?", style = MaterialTheme.typography.titleLarge)
        // Strong first, matching the left-to-right "good → bad" order riders know from Garmin.
        Feel.entries.reversed().forEach { feel ->
            ChoiceButton(
                text = feel.label,
                selected = rating.feel == feel,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                fontSize = 16,
            ) { scope.launch { repository.setFeel(feel) } }
        }

        Spacer(Modifier.height(4.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Done", fontSize = 18.sp)
        }
        Text(
            text = "Rate before tapping End Ride. " + if (BuildConfig.INTERVALS_API_KEY.isNotBlank()) {
                "It is sent to intervals.icu once the ride is uploaded."
            } else {
                "intervals.icu sync is off in this build."
            },
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        syncStatus?.let {
            Text(
                text = "Last ride: $it",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ChoiceButton(
    text: String,
    selected: Boolean,
    modifier: Modifier,
    fontSize: Int,
    onClick: () -> Unit,
) {
    val content: @Composable () -> Unit = {
        Text(text, fontSize = fontSize.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
    val padding = PaddingValues(0.dp)
    if (selected) {
        Button(onClick = onClick, modifier = modifier, contentPadding = padding, colors = ButtonDefaults.buttonColors()) {
            content()
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier, contentPadding = padding) { content() }
    }
}
