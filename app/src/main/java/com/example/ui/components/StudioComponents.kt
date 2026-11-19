package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.audio.RecordingState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatDurationMs(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}

fun formatRelativeDate(timestampMs: Long): String {
    val now = System.currentTimeMillis()
    val diff = (now - timestampMs).coerceAtLeast(0L)
    val minute = 60_000L
    val hour = 60 * minute
    val day = 24 * hour
    return when {
        diff < minute -> "Just now"
        diff < hour -> "${diff / minute}m ago"
        diff < day -> "${diff / hour}h ago"
        diff < 7 * day -> "${diff / day}d ago"
        else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(timestampMs))
    }
}

@Composable
fun UsageBadge(
    usedCount: Int,
    modifier: Modifier = Modifier
) {
    val isUsed = usedCount > 0
    val containerColor by animateColorAsState(
        targetValue = if (isUsed) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
        label = "usageContainer"
    )
    val contentColor by animateColorAsState(
        targetValue = if (isUsed) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        label = "usageContent"
    )

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = containerColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = if (isUsed) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = if (isUsed) "Used $usedCount times" else "Unused idea",
                tint = contentColor,
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = if (isUsed) "Used ${usedCount}x" else "Fresh",
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                maxLines = 1
            )
        }
    }
}

@Composable
fun MatchScoreBadge(
    scorePercent: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Text(
            text = "${scorePercent}% match",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            maxLines = 1
        )
    }
}

@Composable
fun TagPill(
    label: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Text(
            text = "#$label",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun EmptyStudioHeroCard(
    title: String,
    body: String,
    primaryActionLabel: String,
    onPrimaryAction: () -> Unit,
    primaryActionTag: String,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(176.dp)
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_studio_hero),
                    contentDescription = "Analog songwriting studio desk illustration",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
                                )
                            )
                        )
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onPrimaryAction,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag(primaryActionTag),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(text = primaryActionLabel)
                    }
                    if (secondaryActionLabel != null && onSecondaryAction != null) {
                        Surface(
                            onClick = onSecondaryAction,
                            modifier = Modifier.height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            ) {
                                Text(
                                    text = secondaryActionLabel,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LiveRecordingBar(
    recordingState: RecordingState,
    onStopAndSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "recPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(650),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    val coral = MaterialTheme.colorScheme.tertiary
    val onCoralContainer = MaterialTheme.colorScheme.onTertiaryContainer

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        border = BorderStroke(1.dp, coral.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(coral.copy(alpha = pulseAlpha))
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "REC",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = coral
                    )
                    Text(
                        text = formatDurationMs(recordingState.elapsedMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = onCoralContainer
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(26.dp)
                ) {
                    val bars = 28
                    val amps = recordingState.amplitudes
                    val barWidth = (size.width / (bars * 1.6f)).coerceAtLeast(3f)
                    val gap = (size.width - bars * barWidth) / (bars - 1).coerceAtLeast(1)
                    for (i in 0 until bars) {
                        val ampIdx = amps.size - bars + i
                        val amp = if (ampIdx in amps.indices) amps[ampIdx] else 0.12f
                        val barHeight = (size.height * amp).coerceAtLeast(4f)
                        val x = i * (barWidth + gap)
                        val y = (size.height - barHeight) / 2f
                        drawRoundRect(
                            color = coral,
                            topLeft = Offset(x, y),
                            size = Size(barWidth, barHeight),
                            cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                        )
                    }
                }
            }
            IconButton(
                onClick = onCancel,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("cancel_recording_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Cancel recording",
                    tint = onCoralContainer
                )
            }
            Button(
                onClick = onStopAndSave,
                colors = ButtonDefaults.buttonColors(
                    containerColor = coral,
                    contentColor = MaterialTheme.colorScheme.onTertiary
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("stop_save_recording_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Stop,
                    contentDescription = "Save audio tile",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Save")
            }
        }
    }
}
