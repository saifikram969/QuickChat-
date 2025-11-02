package com.example.quickchat.presentation.component

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.SegmentedButtonDefaults.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.quickchat.data.model.ChatMessage
import com.example.quickchat.data.model.MessageStatus
import com.example.quickchat.data.model.MessageType
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun MessageBubble(
    message: ChatMessage,
    isCurrentUser: Boolean,
    onReply: (ChatMessage) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (message.isSystemMessage) {
        SystemMessage(message = message)
    } else {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalAlignment = if (isCurrentUser) Alignment.End else Alignment.Start
        ) {
            // Show reply preview if this message is a reply
            if (message.repliedToMessageId != null) {
                ReplyPreview(
                    repliedMessage = message,
                    isCurrentUser = isCurrentUser,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            SwipeToReplyContainer(
                message = message,
                isCurrentUser = isCurrentUser,
                onReply = onReply,
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentWidth(if (isCurrentUser) Alignment.End else Alignment.Start) // FIX: Add this line
            ) {
                when {
                    message.imageUrl != null -> {
                        ImageMessageComponent(
                            message = message,
                            isCurrentUser = isCurrentUser,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }

                    message.fileUrl != null -> {
                        FileMessageComponent(
                            message = message,
                            isCurrentUser = isCurrentUser,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }

                    !message.text.isNullOrEmpty() -> {
                        TextMessageBubble(
                            message = message,
                            isCurrentUser = isCurrentUser
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ReplyPreview(
    repliedMessage: ChatMessage,
    isCurrentUser: Boolean,
    modifier: Modifier = Modifier
) {
    val previewColor = if (isCurrentUser) {
        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
    }

    val borderColor = if (isCurrentUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.primary
    }

    Row(
        modifier = modifier
            .widthIn(max = 280.dp)
            .padding(start = if (isCurrentUser) 0.dp else 8.dp, end = if (isCurrentUser) 8.dp else 0.dp)
            .border(
                width = 2.dp,
                color = borderColor,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Vertical line indicator
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(24.dp)
                .background(borderColor)
                .clip(RoundedCornerShape(2.dp))
        )

        Spacer(modifier = Modifier.width(8.dp))

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = "Replying to ${if (repliedMessage.repliedToSenderId == repliedMessage.senderId) "yourself" else "them"}",
                style = MaterialTheme.typography.labelSmall,
                color = previewColor,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(2.dp))

            when {
                repliedMessage.repliedToMessageType == MessageType.IMAGE -> {
                    Text(
                        text = "📷 Photo",
                        style = MaterialTheme.typography.bodySmall,
                        color = previewColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                repliedMessage.repliedToMessageType == MessageType.FILE -> {
                    Text(
                        text = "📎 ${repliedMessage.repliedToFileName ?: "File"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = previewColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                else -> {
                    Text(
                        text = repliedMessage.repliedToMessageText ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = previewColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(4.dp))

        androidx.compose.material3.Icon(
            imageVector = Icons.Filled.Reply,
            contentDescription = "Reply",
            tint = borderColor,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun ImageMessageComponent(
    message: ChatMessage,
    isCurrentUser: Boolean,
    modifier: Modifier = Modifier
) {
    var showFullScreen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val bubbleColor = if (isCurrentUser) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant

    val bubbleShape = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomStart = if (isCurrentUser) 16.dp else 4.dp,
        bottomEnd = if (isCurrentUser) 4.dp else 16.dp
    )

    Box(
        modifier = modifier
            .wrapContentWidth(if (isCurrentUser) Alignment.End else Alignment.Start)
            .widthIn(max = 280.dp)
            .clip(bubbleShape)
            .background(bubbleColor)
    ) {
        Column {
            // Image with progress overlay
            Box(
                modifier = Modifier
                    .clickable {
                        if (message.status != MessageStatus.SENDING) {
                            showFullScreen = true
                        }
                    }
            ) {
                // Show thumbnail if available, otherwise show full image
                AsyncImage(
                    model = message.thumbnailUrl ?: message.imageUrl,
                    contentDescription = "Chat image",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                )

                // Upload progress overlay
                if (message.status == MessageStatus.SENDING) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(Color.Black.copy(alpha = 0.4f))
                    ) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Circular progress indicator
                            CircularProgressIndicator(
                                progress = message.uploadProgress ?: 0f,
                                modifier = Modifier.size(48.dp),
                                strokeWidth = 4.dp,
                                color = Color.White
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // Percentage text
                            Text(
                                text = "${((message.uploadProgress ?: 0f) * 100).toInt()}%",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }

            // Text caption if available
            if (!message.text.isNullOrEmpty()) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            // Timestamp and status
            Row(
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(end = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = SimpleDateFormat("hh:mm a", Locale.getDefault())
                        .format(Date(message.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )

                Spacer(modifier = Modifier.width(4.dp))

                if (isCurrentUser) {
                    when (message.status) {
                        MessageStatus.SENT -> {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Sent",
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        MessageStatus.DELIVERED -> {
                            Row(horizontalArrangement = Arrangement.spacedBy((-4).dp)) {
                                repeat(2) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Delivered",
                                        modifier = Modifier.size(12.dp),
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                        MessageStatus.SEEN -> {
                            Row(horizontalArrangement = Arrangement.spacedBy((-4).dp)) {
                                repeat(2) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Seen",
                                        modifier = Modifier.size(12.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        MessageStatus.FAILED -> {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = "Failed",
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    // Full screen dialog
    if (showFullScreen) {
        Dialog(
            onDismissRequest = { showFullScreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = message.imageUrl,
                    contentDescription = "Full screen image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )

                IconButton(
                    onClick = { showFullScreen = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun FileMessageComponent(
    message: ChatMessage,
    isCurrentUser: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val bubbleColor = if (isCurrentUser) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant

    val bubbleShape = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomStart = if (isCurrentUser) 16.dp else 4.dp,
        bottomEnd = if (isCurrentUser) 4.dp else 16.dp
    )

    // FIX: Add alignment modifier
    Box(
        modifier = modifier
            .wrapContentWidth(if (isCurrentUser) Alignment.End else Alignment.Start)
            .widthIn(max = 280.dp)
            .clip(bubbleShape)
            .background(bubbleColor)
    ) {
        Column(
            modifier = Modifier
                .clickable {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(
                                Uri.parse(message.fileUrl),
                                message.fileType ?: "*/*"
                            )
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Cannot open file: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                .padding(12.dp)
        ) {
            // File info row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = when {
                            message.fileType?.startsWith("image/") == true -> Icons.Outlined.Image
                            message.fileType?.startsWith("audio/") == true -> Icons.Outlined.AudioFile
                            message.fileType?.startsWith("video/") == true -> Icons.Outlined.Videocam
                            message.fileType?.startsWith("application/pdf") == true -> Icons.Outlined.PictureAsPdf
                            else -> Icons.Outlined.InsertDriveFile
                        },
                        contentDescription = "File",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )

                    // Circular progress for upload/download
                    if (message.status == MessageStatus.SENDING && message.uploadProgress != null) {
                        CircularProgressIndicator(
                            progress = message.uploadProgress,
                            modifier = Modifier.size(48.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = message.fileName ?: "File",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Format file size
                    message.fileSize?.let { size ->
                        val fileSizeText = when {
                            size < 1024 -> "$size B"
                            size < 1024 * 1024 -> "${size / 1024} KB"
                            else -> "${size / (1024 * 1024)} MB"
                        }
                        Text(
                            text = fileSizeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }

                // Download/status icon
                when {
                    message.status == MessageStatus.SENDING -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    message.fileUrl != null -> {
                        IconButton(
                            onClick = {
                                // TODO: Implement download
                                Toast.makeText(context, "Downloading...", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FileDownload,
                                contentDescription = "Download",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // Linear progress bar (like Telegram)
            if (message.status == MessageStatus.SENDING) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = message.uploadProgress ?: 0f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Timestamp and status
            Row(
                modifier = Modifier.align(Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = SimpleDateFormat("hh:mm a", Locale.getDefault())
                        .format(Date(message.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )

                Spacer(modifier = Modifier.width(4.dp))

                if (isCurrentUser) {
                    when (message.status) {
                        MessageStatus.SENT -> {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Sent",
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        MessageStatus.DELIVERED -> {
                            Row(horizontalArrangement = Arrangement.spacedBy((-4).dp)) {
                                repeat(2) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Delivered",
                                        modifier = Modifier.size(12.dp),
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                        MessageStatus.SEEN -> {
                            Row(horizontalArrangement = Arrangement.spacedBy((-4).dp)) {
                                repeat(2) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Seen",
                                        modifier = Modifier.size(12.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        MessageStatus.FAILED -> {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = "Failed",
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                        else -> {}
                    }
                }
            }
        }
    }
}

@Composable
private fun TextMessageBubble(
    message: ChatMessage,
    isCurrentUser: Boolean,
    modifier: Modifier = Modifier
) {
    val bubbleColor = if (isCurrentUser) Color.Black else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isCurrentUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val bubbleShape = RoundedCornerShape(
        topStart = 8.dp,
        topEnd = 8.dp,
        bottomStart = if (isCurrentUser) 8.dp else 0.dp,
        bottomEnd = if (isCurrentUser) 0.dp else 8.dp
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = if (isCurrentUser) Alignment.End else Alignment.Start
    ) {
        Column(
            modifier = Modifier
                .wrapContentWidth()
                .clip(bubbleShape)
                .background(bubbleColor)
                .padding(12.dp)
        ) {
            Text(
                text = message.text ?: "",
                color = textColor,
                modifier = Modifier.wrapContentWidth()
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.align(Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = SimpleDateFormat("hh:mm a", Locale.getDefault())
                        .format(Date(message.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isCurrentUser) Color.White.copy(alpha = 0.8f)
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )

                Spacer(modifier = Modifier.width(4.dp))

                if (isCurrentUser) {
                    when (message.status) {
                        MessageStatus.SENDING -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 1.dp,
                                color = Color.White
                            )
                        }
                        MessageStatus.SENT -> {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Sent",
                                modifier = Modifier.size(12.dp),
                                tint = Color.White
                            )
                        }
                        MessageStatus.DELIVERED -> {
                            Row(horizontalArrangement = Arrangement.spacedBy((-4).dp)) {
                                repeat(2) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Delivered",
                                        modifier = Modifier.size(12.dp),
                                        tint = Color.White
                                    )
                                }
                            }
                        }
                        MessageStatus.SEEN -> {
                            Row(horizontalArrangement = Arrangement.spacedBy((-4).dp)) {
                                repeat(2) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Seen",
                                        modifier = Modifier.size(12.dp),
                                        tint = Color.Blue
                                    )
                                }
                            }
                        }
                        MessageStatus.FAILED -> {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = "Failed",
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SystemMessage(message: ChatMessage) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}