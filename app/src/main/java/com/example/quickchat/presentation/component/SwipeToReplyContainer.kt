package com.example.quickchat.presentation.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.quickchat.data.model.ChatMessage
import kotlin.math.abs

@Composable
fun SwipeToReplyContainer(
    message: ChatMessage,
    isCurrentUser: Boolean,
    onReply: (ChatMessage) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var dragOffset by remember { mutableStateOf(0f) }
    val maxDragOffset = with(LocalDensity.current) { 80.dp.toPx() }
    val isDraggedEnough = abs(dragOffset) > maxDragOffset * 0.7f

    val replyIconAlpha by animateFloatAsState(
        targetValue = if (isDraggedEnough) 1f else 0.5f,
        label = "replyIconAlpha"
    )

    val dragEnabled = !message.isSystemMessage

    Box(
        modifier = modifier
    ) {
        if (abs(dragOffset) > 0 && dragEnabled) {
            ReplySwipeIndicator(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(horizontal = 16.dp)
                    .alpha(replyIconAlpha),
                isActive = isDraggedEnough,
                isCurrentUser = isCurrentUser
            )
        }

        Box(
            modifier = Modifier
                .offset(x = with(LocalDensity.current) { dragOffset.toDp() })
                .draggable(
                    enabled = dragEnabled,
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        val newOffset = dragOffset + delta
                        dragOffset = if (newOffset > 0) {
                            newOffset.coerceAtMost(maxDragOffset)
                        } else {
                            0f
                        }
                    },
                    onDragStopped = {
                        if (isDraggedEnough) onReply(message)
                        dragOffset = 0f
                    }
                )
        ) {
            content()
        }
    }
}

@Composable
private fun ReplySwipeIndicator(
    modifier: Modifier = Modifier,
    isActive: Boolean,
    isCurrentUser: Boolean
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Text(
            text = "Reply",
            style = MaterialTheme.typography.labelSmall,
            color = if (isActive) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )

        Spacer(modifier = Modifier.width(4.dp))

        Icon(
            imageVector = Icons.Default.Reply,
            contentDescription = "Reply",
            tint = if (isActive) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}