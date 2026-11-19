package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.audio.PlaybackState
import java.io.File
import kotlin.math.abs

@Composable
fun AudioTileCard(
    audioPath: String?,
    durationMs: Long,
    caption: String,
    playbackState: PlaybackState,
    onTogglePlay: (String) -> Unit,
    onSeek: (String, Float) -> Unit,
    modifier: Modifier = Modifier,
    editable: Boolean = false,
    onCaptionChange: ((String) -> Unit)? = null,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    val fileAvailable = remember(audioPath) {
        !audioPath.isNullOrBlank() && File(audioPath).exists()
    }
    val isThisActive = audioPath != null && playbackState.activePath == audioPath
    val isPlaying = isThisActive && playbackState.isPlaying
    val progress = if (isThisActive) playbackState.progress else 0f
    val displayTime = if (isThisActive && playbackState.positionMs > 0L) {
        "${formatDurationMs(playbackState.positionMs)} / ${formatDurationMs(durationMs.coerceAtLeast(playbackState.durationMs))}"
    } else {
        formatDurationMs(durationMs)
    }

    // Deterministic decorative waveform heights seeded by file path so each clip has a distinct visual signature
    val waveformBars = remember(audioPath, durationMs) {
        val seed = (audioPath ?: "clip").hashCode() xor durationMs.hashCode()
        List(30) { index ->
            val h = abs(((seed * 31 + index * 17) xor (index * 97)) % 100)
            0.22f + (h / 100f) * 0.78f
        }
    }

    val activeColor = MaterialTheme.colorScheme.secondary
    val inactiveColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            width = 1.dp,
            color = if (isPlaying) activeColor else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    onClick = {
                        if (fileAvailable && audioPath != null) {
                            onTogglePlay(audioPath)
                        }
                    },
                    enabled = fileAvailable,
                    shape = CircleShape,
                    color = if (fileAvailable) {
                        if (isPlaying) activeColor else MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("audio_tile_play_button")
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause melody clip" else "Play melody clip",
                        tint = if (fileAvailable) {
                            if (isPlaying) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(11.dp)
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.GraphicEq,
                                contentDescription = null,
                                tint = activeColor,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = if (fileAvailable) "MELODY TILE" else "AUDIO NOT ATTACHED",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (fileAvailable) activeColor else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = displayTime,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(28.dp)
                            .pointerInput(audioPath, isThisActive) {
                                detectTapGestures { offset ->
                                    if (fileAvailable && audioPath != null && size.width > 0) {
                                        val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                        if (!isThisActive) {
                                            onTogglePlay(audioPath)
                                        }
                                        onSeek(audioPath, fraction)
                                    }
                                }
                            }
                    ) {
                        val count = waveformBars.size
                        val barW = (size.width / (count * 1.65f)).coerceAtLeast(3f)
                        val gap = (size.width - count * barW) / (count - 1).coerceAtLeast(1)
                        waveformBars.forEachIndexed { idx, heightRatio ->
                            val frac = idx.toFloat() / count.toFloat()
                            val barH = (size.height * heightRatio).coerceAtLeast(5f)
                            val x = idx * (barW + gap)
                            val y = (size.height - barH) / 2f
                            drawRoundRect(
                                color = if (frac <= progress && isThisActive) activeColor else inactiveColor,
                                topLeft = Offset(x, y),
                                size = Size(barW, barH),
                                cornerRadius = CornerRadius(barW / 2f, barW / 2f)
                            )
                        }
                    }
                }
            }

            if (editable && onCaptionChange != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    BasicTextField(
                        value = caption,
                        onValueChange = onCaptionChange,
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp, vertical = 4.dp)
                            .testTag("audio_tile_caption_input"),
                        decorationBox = { innerTextField ->
                            if (caption.isBlank()) {
                                Text(
                                    text = "Label this melody (e.g. 'hummed chorus hook, minor key') — makes it searchable",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                            innerTextField()
                        }
                    )
                    if (onMoveUp != null) {
                        IconButton(onClick = onMoveUp, modifier = Modifier.size(40.dp)) {
                            Icon(
                                imageVector = Icons.Filled.ArrowUpward,
                                contentDescription = "Move audio tile up",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    if (onMoveDown != null) {
                        IconButton(onClick = onMoveDown, modifier = Modifier.size(40.dp)) {
                            Icon(
                                imageVector = Icons.Filled.ArrowDownward,
                                contentDescription = "Move audio tile down",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    if (onDelete != null) {
                        IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                            Icon(
                                imageVector = Icons.Filled.DeleteOutline,
                                contentDescription = "Delete audio tile",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            } else if (caption.isNotBlank()) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
    }
}
