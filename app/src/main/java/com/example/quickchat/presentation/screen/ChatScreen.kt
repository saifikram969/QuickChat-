package com.example.quickchat.presentation.screen

import android.R.id.message
import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import android.content.ContentResolver
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.outlined.Archive
import android.os.Build
import android.webkit.MimeTypeMap
import java.io.InputStream
import android.content.Intent
import com.example.quickchat.utils.ChatExporter
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.DisposableEffect
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.window.PopupProperties
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.cloudinary.android.MediaManager
import com.cloudinary.android.callback.ErrorInfo
import com.cloudinary.android.callback.UploadCallback
import com.example.quickchat.data.model.ChatMessage
import com.example.quickchat.data.model.MessageStatus
import com.example.quickchat.navigation.Routes
import com.example.quickchat.presentation.component.ChatTopBar
import com.example.quickchat.presentation.component.MessageBubble
import com.example.quickchat.presentation.viewmodel.ChatUiState
import com.example.quickchat.presentation.viewmodel.ChatViewModel
import com.example.quickchat.utils.connectivityState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.WatchEvent
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.ContinuationInterceptor
import kotlin.math.abs

@Composable
fun ChatScreen(
    viewModel: ChatViewModel = koinViewModel(),
    currentUserId: String,
    roomId: String,
    otherUserId: String,
    onBackClick: () -> Unit,
    navController: NavController
) {

    LaunchedEffect(Unit) {
        viewModel.markMessagesAsRead(roomId, currentUserId)
    }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isUploading by remember { mutableStateOf(false) }
    var uploadProgress by remember { mutableStateOf(0f) }

    // Presence state
    val presenceStatus by viewModel.presenceStatus.collectAsState()
    val otherUserTyping by viewModel.otherUserTyping.collectAsState()

    // drop down mene delete chatroom or export chat
    var showOptionsMenu by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val networkStatus by viewModel.networkStatus.collectAsState()

    val uiState by viewModel.uiState.collectAsState()
    // Add reply state
    var replyingToMessage by remember { mutableStateOf<ChatMessage?>(null) }

    // Handle lifecycle events for presence
    DisposableEffect(Unit) {
        viewModel.updatePresence(currentUserId, true)
        viewModel.observePresence(otherUserId)

        onDispose {
            viewModel.updatePresence(currentUserId, false)
            viewModel.updateTypingStatus(roomId, currentUserId, false)
        }
    }

    // Combined initialization and cleanup effect
    DisposableEffect(roomId, currentUserId, otherUserId) {
        // Initialization
        viewModel.initializeChat(roomId, currentUserId)
        viewModel.updatePresence(currentUserId, true)
        viewModel.observePresence(otherUserId)
        viewModel.observeTypingStatus(roomId, otherUserId)
        viewModel.markMessagesAsRead(roomId, currentUserId)

        onDispose {
            viewModel.updatePresence(currentUserId, false)
            viewModel.updateTypingStatus(roomId, currentUserId, false)
        }
    }
    val roomName by remember(uiState) {
        derivedStateOf {
            when (uiState) {
                is ChatUiState.Success -> (uiState as ChatUiState.Success).roomName
                else -> "Chat Room"
            }
        }
    }

    // Typing status tracking
    var isTyping by remember { mutableStateOf(false) }
    var typingDebounceJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    //image picker
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { imageUri ->
            coroutineScope.launch {
                try {
                    val fileSize = context.contentResolver.openInputStream(imageUri)?.available() ?: 0
                    val maxSize = 5 * 1024 * 1024 // 5MB

                    if (fileSize > maxSize) {
                        Toast.makeText(context, "File too large. Max 5MB allowed.", Toast.LENGTH_SHORT).show()
                        return@launch
                    }

                    isUploading = true
                    uploadProgress = 0f

                    val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val source = ImageDecoder.createSource(context.contentResolver, imageUri)
                        ImageDecoder.decodeBitmap(source)
                    } else {
                        MediaStore.Images.Media.getBitmap(context.contentResolver, imageUri)
                    }

                    // Compress image
                    val compressedFile = File(context.cacheDir, "compressed_${System.currentTimeMillis()}.jpg")
                    val outStream = FileOutputStream(compressedFile)
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outStream)
                    outStream.flush()
                    outStream.close()

                    // Upload full image
                    MediaManager.get().upload(compressedFile.absolutePath)
                        .option("resource_type", "image")
                        .callback(object : UploadCallback {
                            override fun onStart(requestId: String?) {}
                            override fun onProgress(requestId: String?, bytes: Long, totalBytes: Long) {
                                uploadProgress = bytes.toFloat() / totalBytes.toFloat()
                            }
                            override fun onSuccess(requestId: String?, resultData: Map<*, *>) {
                                val fullImageUrl = resultData["secure_url"] as? String ?: return

                                // Generate thumbnail
                                val thumbBitmap = Bitmap.createScaledBitmap(bitmap, 200, 200, true)
                                val thumbFile = File(
                                    context.cacheDir,
                                    "thumb_${System.currentTimeMillis()}.jpg"
                                )
                                val thumbStream = FileOutputStream(thumbFile)
                                thumbBitmap.compress(Bitmap.CompressFormat.JPEG, 50, thumbStream)
                                thumbStream.flush()
                                thumbStream.close()

                                // Upload thumbnail
                                MediaManager.get().upload(thumbFile.absolutePath)
                                    .option("resource_type", "image")
                                    .callback(object : UploadCallback {
                                        override fun onSuccess(requestId: String?, result: Map<*, *>) {
                                            val thumbUrl = result["secure_url"] as? String ?: return
                                            viewModel.sendMessage(
                                                roomId = roomId,
                                                senderId = currentUserId,
                                                text = "",
                                                imageUrl = fullImageUrl,
                                               // thumbnailUrl = thumbUrl,
                                                repliedToMessage = replyingToMessage // Pass reply info
                                            )
                                            isUploading = false
                                            uploadProgress = 0f
                                            replyingToMessage = null // Clear reply after sending
                                        }
                                        override fun onError(requestId: String?, error: ErrorInfo?) {
                                            Toast.makeText(context, "Thumbnail upload failed", Toast.LENGTH_SHORT).show()
                                            isUploading = false
                                        }

                                        override fun onStart(requestId: String?) {}
                                        override fun onProgress(requestId: String?, bytes: Long, totalBytes: Long) {}
                                        override fun onReschedule(requestId: String?, error: ErrorInfo?) {}
                                    }).dispatch()
                            }
                            override fun onError(requestId: String?, error: ErrorInfo?) {
                                Toast.makeText(context, "Upload failed: ${error?.description}", Toast.LENGTH_SHORT).show()
                                isUploading = false
                            }
                            override fun onReschedule(requestId: String?, error: ErrorInfo?) {}
                        }).dispatch()

                } catch (e: Exception) {
                    Toast.makeText(context, "Upload failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                    isUploading = false
                }
            }
        }

    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { fileUri ->
            coroutineScope.launch {
                try {
                    // Get file details
                    val inputStream = context.contentResolver.openInputStream(fileUri)
                    val mimeType = context.contentResolver.getType(fileUri)
                    val fileExtension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
                    val fileName = context.contentResolver.query(fileUri, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        cursor.moveToFirst()
                        cursor.getString(nameIndex)
                    } ?: "file_${System.currentTimeMillis()}.$fileExtension"

                    val fileSize = context.contentResolver.openFileDescriptor(fileUri, "r")?.use {
                        it.statSize
                    }

                    // Create temp file
                    val tempFile = File.createTempFile(
                        "upload_${System.currentTimeMillis()}",
                        ".$fileExtension",
                        context.cacheDir
                    ).apply {
                        inputStream?.use { input ->
                            FileOutputStream(this).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }

                    // Check file size
                    val maxSize = 10 * 1024 * 1024 // 10MB
                    if (tempFile.length() > maxSize) {
                        Toast.makeText(context, "File too large. Max 10MB allowed.", Toast.LENGTH_SHORT).show()
                        tempFile.delete()
                        return@launch
                    }

                    isUploading = true
                    uploadProgress = 0f

                    // Create temporary message
                    val tempMessage = ChatMessage(
                        id = UUID.randomUUID().toString(),
                        text = "",
                        senderId = currentUserId,
                        timestamp = System.currentTimeMillis(),
                        status = MessageStatus.SENDING,
                        fileUrl = null,
                        fileName = fileName,
                        fileType = mimeType,
                        fileSize = fileSize,
                        imageUrl = null,
                        isTemp = true,
                        uploadProgress = 0f,
                        // Include reply info in temp message
                        repliedToMessageId = replyingToMessage?.id,
                        repliedToMessageText = replyingToMessage?.text,
                        repliedToMessageType = replyingToMessage?.messageType,
                        repliedToSenderId = replyingToMessage?.senderId,
                        repliedToImageUrl = replyingToMessage?.imageUrl,
                        repliedToFileName = replyingToMessage?.fileName
                    )

                    // Add to UI immediately
                    viewModel.addTempMessage(tempMessage)

                    // Determine Cloudinary resource type
                    val resourceType = when {
                        mimeType?.startsWith("image/") == true -> "image"
                        mimeType?.startsWith("audio/") == true -> "video"
                        else -> "raw"
                    }

                    // Upload to Cloudinary
                    MediaManager.get().upload(tempFile.absolutePath)
                        .option("resource_type", resourceType)
                        .callback(object : UploadCallback {
                            override fun onStart(requestId: String?) {
                                Log.d("FileUpload", "Upload started for $fileName")
                            }

                            override fun onProgress(requestId: String?, bytes: Long, totalBytes: Long) {
                                uploadProgress = bytes.toFloat() / totalBytes.toFloat()
                                viewModel.updateTempMessage(
                                    tempMessage.id,
                                    uploadProgress = uploadProgress,
                                    status = MessageStatus.SENT
                                )
                            }

                            override fun onSuccess(requestId: String?, resultData: Map<*, *>) {
                                val fileUrl = resultData["secure_url"] as? String ?: run {
                                    Toast.makeText(context, "Upload failed: No URL returned", Toast.LENGTH_SHORT).show()
                                    return
                                }

                                Log.d("FileUpload", "Upload successful: $fileUrl")

                                viewModel.updateTempMessage(
                                    tempMessage.id,
                                    fileUrl = fileUrl,
                                    status = MessageStatus.SENT,
                                    uploadProgress = 1f,
                                    isTemp = false
                                )
                                isUploading = false
                                tempFile.delete()
                                replyingToMessage = null
                            }

                            override fun onError(requestId: String?, error: ErrorInfo?) {
                                Log.e("FileUpload", "Upload failed: ${error?.description}")
                                viewModel.updateTempMessage(
                                    tempMessage.id,
                                    status = MessageStatus.FAILED
                                )
                                Toast.makeText(
                                    context,
                                    "Upload failed: ${error?.description ?: "Unknown error"}",
                                    Toast.LENGTH_SHORT
                                ).show()
                                isUploading = false
                                tempFile.delete()
                            }

                            override fun onReschedule(requestId: String?, error: ErrorInfo?) {
                                Log.w("FileUpload", "Upload rescheduled: ${error?.description}")
                            }
                        }).dispatch()

                } catch (e: Exception) {
                    Log.e("FileUpload", "Upload failed", e)
                    Toast.makeText(
                        context,
                        "Upload failed: ${e.localizedMessage}",
                        Toast.LENGTH_SHORT
                    ).show()
                    isUploading = false
                }
            }
        }
    }

    var messageText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(uiState) {
        if (uiState is ChatUiState.Success) {
            val messages = (uiState as ChatUiState.Success).messages
            if (messages.any {
                    it.senderId != currentUserId &&
                            it.status != MessageStatus.SEEN
                }) {
                viewModel.markMessagesAsRead(roomId, currentUserId)
            }
        }
    }
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            // When scrolling stops, mark messages as read
            viewModel.markMessagesAsRead(roomId, currentUserId)
        }
    }

    val showScrollToBottomButton by remember {
        derivedStateOf {
            val lastVisibleItemIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            val totalItemsCount = listState.layoutInfo.totalItemsCount
            lastVisibleItemIndex != null && totalItemsCount > 0 && lastVisibleItemIndex < totalItemsCount - 1
        }
    }

    val charCount by remember { derivedStateOf { messageText.length } }
    val maxCharCount = 300
    val charCountColor by remember {
        derivedStateOf {
            when {
                charCount > maxCharCount -> Color.Red
                charCount == maxCharCount -> Color(0xFFFFA000)
                else -> Color.Gray
            }
        }
    }

    var wasOffline by remember { mutableStateOf(false) }
    val isOnline by connectivityState()

    LaunchedEffect(isOnline) {
        if (isOnline && wasOffline) {
            viewModel.onNetworkRestored(roomId)
        }
        wasOffline = !isOnline
    }

    // Handle typing status changes
    LaunchedEffect(messageText) {
        typingDebounceJob?.cancel()

        if (messageText.isNotEmpty()) {
            if (!isTyping) {
                viewModel.updateTypingStatus(roomId, currentUserId, true)
                isTyping = true
            }

            typingDebounceJob = coroutineScope.launch {
                delay(2000) // 2 second delay after last keystroke
                if (messageText.isEmpty()) {
                    viewModel.updateTypingStatus(roomId, currentUserId, false)
                    isTyping = false
                }
            }
        } else if (isTyping) {
            viewModel.updateTypingStatus(roomId, currentUserId, false)
            isTyping = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        // Network status indicator
        if (!networkStatus) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Red.copy(alpha = 0.7f))
                    .padding(4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Offline - Messages will be sent when connection is restored",
                    color = Color.Black,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        ChatTopBar(
            chatRoomName = roomName?: "Loading...",
            participantName = otherUserId,
            isOnline = presenceStatus ?: false,
            isTyping = otherUserTyping,
            onBackClick = onBackClick,
            onMoreOptionsClick = { showOptionsMenu = true },
            modifier = Modifier.padding(horizontal = 4.dp)
        )


        // Updated Options dropdown menu - opens upwards
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopEnd
        ) {
            // Custom popup that matches WhatsApp style exactly
            if (showOptionsMenu) {
                Card(
                    modifier = Modifier
                        .width(220.dp)
                        .padding(end = 16.dp, top = 8.dp)
                        .offset(y = (-16).dp),
                    elevation = CardDefaults.cardElevation(8.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        // Group Info option
                        TextButton(
                            onClick = {
                                showOptionsMenu = false
                                navController.navigate(Routes.groupInfoRoute(roomId, roomName))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    Icons.Outlined.Info,
                                    contentDescription = "Group Info",
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(
                                    text = "Group Info",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }

                        Divider(modifier = Modifier.padding(vertical = 4.dp))

                        // Export options section
                        Text(
                            text = "Export chat",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp)
                        )

                        // Without media option
                        TextButton(
                            onClick = {
                                showOptionsMenu = false
                                showExportDialog = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    Icons.Outlined.FileDownload,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Text("Export chat")
                            }
                        }

                        // With media (ZIP) option
                        TextButton(
                            onClick = {
                                showOptionsMenu = false
                                coroutineScope.launch {
                                    try {
                                        if (uiState is ChatUiState.Success) {
                                            val messages = (uiState as ChatUiState.Success).messages
                                            val uri = ChatExporter.exportToZip(context, messages, roomId)
                                            val shareIntent = ChatExporter.createShareIntent(
                                                context = context,
                                                uri = uri,
                                                type = "application/zip"
                                            )
                                            context.startActivity(Intent.createChooser(
                                                shareIntent,
                                                "Export chat"
                                            ))
                                        }
                                    } catch (e: Exception) {
                                        Toast.makeText(
                                            context,
                                            "Export failed: ${e.localizedMessage}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    Icons.Outlined.Archive,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Text("Export chat(ZIP)")
                            }
                        }

                        Divider(modifier = Modifier.padding(vertical = 4.dp))

                        // Delete option
                        TextButton(
                            onClick = {
                                showOptionsMenu = false
                                showDeleteDialog = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = null,
                                    tint = Color.Red,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Text("Delete chatroom", color = Color.Red)
                            }
                        }
                    }
                }
            }
        }

        // Export confirmation dialog
        if (showExportDialog && uiState is ChatUiState.Success) {
            val messages = (uiState as ChatUiState.Success).messages
            AlertDialog(
                onDismissRequest = { showExportDialog = false },
                title = { Text("Export Chat") },
                text = { Text("Choose the format to export your chat history:") },
                confirmButton = {
                    Row {
                        // TXT Export Button
                        TextButton(
                            onClick = {
                                showExportDialog = false
                                coroutineScope.launch {
                                    try {
                                        val uri = ChatExporter.exportToTxt(context, messages, roomId)
                                        val shareIntent = ChatExporter.createShareIntent(
                                            context = context,
                                            uri = uri,
                                            type = "text/plain"
                                        )
                                        context.startActivity(Intent.createChooser(shareIntent, "Export chat as TXT"))
                                    } catch (e: Exception) {
                                        Toast.makeText(
                                            context,
                                            "TXT export failed: ${e.message}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        Log.e("ChatScreen", "TXT export failed", e)
                                    }
                                }
                            }
                        ) {
                            Text("TXT")
                        }

                        // JSON Export Button
                        TextButton(
                            onClick = {
                                showExportDialog = false
                                coroutineScope.launch {
                                    try {
                                        val uri = ChatExporter.exportToJson(context, messages, roomId)
                                        val shareIntent = ChatExporter.createShareIntent(
                                            context = context,
                                            uri = uri,
                                            type = "application/json"
                                        )
                                        context.startActivity(Intent.createChooser(shareIntent, "Export chat as JSON"))
                                    } catch (e: Exception) {
                                        Toast.makeText(
                                            context,
                                            "JSON export failed: ${e.message}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        Log.e("ChatScreen", "JSON export failed", e)
                                    }
                                }
                            }
                        ) {
                            Text("JSON")
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showExportDialog = false }
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Delete confirmation dialog
        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text("Delete Chatroom") },
                text = { Text("Are you sure you want to delete this chatroom? All messages will be permanently deleted.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDeleteDialog = false
                            viewModel.deleteChatroomForUser(roomId, currentUserId)
                            onBackClick()
                        }
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showDeleteDialog = false }
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (isUploading) {
            LinearProgressIndicator(
                progress = { uploadProgress },
                modifier = Modifier.fillMaxWidth()
                    .height(4.dp)
            )
        }

        when (uiState) {
            is ChatUiState.Loading -> Box(Modifier.fillMaxSize(),
                contentAlignment =Alignment.Center)
            {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    strokeWidth = 4.dp,
                )
            }
            is ChatUiState.Error -> Box(
                modifier = Modifier.fillMaxSize(),
                Alignment.Center)
            { Text("Failed to load chat.",)
            }
            is ChatUiState.Success -> {
                val state = uiState as ChatUiState.Success
                val (systemMessages, regularMessages) = state.messages.partition { it.isSystemMessage }

                LaunchedEffect(state.messages) {
                    delay(100)

                    // Scroll to the last index in LazyColumn — account for date headers
                    val groupedMessages = state.messages
                        .filterNot { it.isSystemMessage }
                        .groupBy {
                            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(it.timestamp))
                        }

                    // Total item count = total headers + total messages
                    val totalItems = groupedMessages.size + state.messages.count { !it.isSystemMessage }

                    if (totalItems > 0) {
                        listState.scrollToItem(totalItems - 1)
                    }
                }

                Box(modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()) {
                    when {
                        state.messages.isEmpty() -> {
                            // Empty state UI
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Chat,
                                    contentDescription = "No messages",
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "No messages yet",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                                Text(
                                    text = "Start the conversation by sending a message",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                        }
                        else -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                state = listState,
                                contentPadding = PaddingValues(vertical = 10.dp, horizontal = 6.dp)
                            ) {
                                val groupedMessages = regularMessages.groupBy {
                                    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(it.timestamp))
                                }
                                groupedMessages.forEach { (dateKey, messagesForDate) ->
                                    item(key = "header_$dateKey") { DateHeader(dateKey) }
                                    items(messagesForDate, key = { it.id }) { message ->
                                        MessageBubble(
                                            message = message,
                                            isCurrentUser = message.senderId == state.currentUserId,
                                            onReply = { messageToReply ->
                                                replyingToMessage = messageToReply
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Scroll to bottom FAB
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showScrollToBottomButton,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp)
                    ) {
                        FloatingActionButton(
                            onClick = {
                                coroutineScope.launch {
                                    delay(100)
                                    val groupedMessages = state.messages
                                        .filterNot { it.isSystemMessage }
                                        .groupBy {
                                            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(it.timestamp))
                                        }

                                    val totalItems = groupedMessages.size + state.messages.count { !it.isSystemMessage }

                                    if (totalItems > 0) {
                                        listState.scrollToItem(totalItems - 1)
                                    }
                                }
                            },
                            containerColor = MaterialTheme.colorScheme.onSurface
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowDownward,
                                contentDescription = "Scroll to bottom",
                                tint = Color.White
                            )
                        }
                    }
                }

                Column {
                    if (messageText.isNotEmpty()) {
                        Text(
                            text = "$charCount/$maxCharCount",
                            color = charCountColor,
                            modifier = Modifier.padding(horizontal = 24.dp)
                                .align(Alignment.End)
                        )
                    }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(32.dp),
                        tonalElevation = 4.dp,
                    ) {
                        Column {
                            // Reply preview in input field
                            replyingToMessage?.let { message ->
                                ReplyPreviewInInput(
                                    message = message,
                                    currentUserId = currentUserId,
                                    onCancel = { replyingToMessage = null },
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .heightIn(min = 48.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // File attachment button - always visible
                                IconButton(
                                    onClick = {
                                        filePickerLauncher.launch("*/*")
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Outlined.AttachFile,
                                        contentDescription = "Attach File",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp))
                                }

                                Spacer(modifier = Modifier.width(4.dp))

                                // Image picker button - hides when typing
                                AnimatedVisibility(
                                    visible = messageText.isBlank(),
                                    enter = fadeIn(animationSpec = tween(100)),
                                    exit = fadeOut(animationSpec = tween(100)),
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    IconButton(
                                        onClick = { imagePickerLauncher.launch("image/*") },
                                        enabled = !isUploading
                                    ) {
                                        if (isUploading) {
                                            CircularProgressIndicator(
                                                Modifier.size(20.dp),
                                                strokeWidth = 2.dp)
                                        } else {
                                            Icon(
                                                Icons.Outlined.Image,
                                                contentDescription = "Pick Image",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(24.dp))
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(4.dp))

                                TextField(
                                    value = messageText,
                                    onValueChange = {
                                        if (it.length <= maxCharCount) {
                                            messageText = it
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 4.dp)
                                        .heightIn(max = 100.dp),
                                    placeholder = { Text("How's your day?") },
                                    singleLine = false,
                                    maxLines = 4,
                                    shape = RoundedCornerShape(24.dp),
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        disabledContainerColor = Color.Transparent,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                        cursorColor = MaterialTheme.colorScheme.primary
                                    ),
                                    trailingIcon = {
                                        if (messageText.isNotBlank()) {
                                            IconButton(
                                                onClick = { messageText = "" },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Close,
                                                    contentDescription = "Clear",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                )

                                // Dynamic button that changes between mic and send
                                Crossfade(
                                    targetState = messageText.isNotBlank(),
                                    animationSpec = tween(100),
                                    modifier = Modifier.size(40.dp)
                                ) { showSendButton ->
                                    if (showSendButton) {
                                        IconButton(
                                            onClick = {
                                                if (messageText.isNotBlank()) {
                                                    viewModel.sendMessage(
                                                        roomId = roomId,
                                                        senderId = currentUserId,
                                                        text = messageText,
                                                        imageUrl = null,
                                                        repliedToMessage = replyingToMessage
                                                    )
                                                    messageText = ""
                                                    replyingToMessage = null
                                                    viewModel.updateTypingStatus(roomId, currentUserId, false)
                                                    isTyping = false
                                                    coroutineScope.launch {
                                                        delay(100)
                                                        val groupedMessages = state.messages
                                                            .filterNot { it.isSystemMessage }
                                                            .groupBy {
                                                                SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                                                                    .format(Date(it.timestamp))
                                                            }
                                                        val totalItems = groupedMessages.size +
                                                                state.messages.count { !it.isSystemMessage }
                                                        if (totalItems > 0) {
                                                            listState.animateScrollToItem(totalItems - 1)
                                                        }
                                                    }
                                                }
                                            },
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(
                                                    color = Color.Black,
                                                    shape = RoundedCornerShape(50))
                                        ) {
                                            Icon(
                                                Icons.Default.Send,
                                                contentDescription = "Send",
                                                tint = Color.White,
                                                modifier = Modifier.size(20.dp))
                                        }
                                    } else {
                                        var isRecording by remember { mutableStateOf(false) }
                                        IconButton(
                                            onClick = {
                                                isRecording = true
                                                // TODO: Implement audio recording logic
                                                Toast.makeText(context, "Audio recording coming soon", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(
                                                    MaterialTheme.colorScheme.primary,
                                                    shape = RoundedCornerShape(50)),
                                            enabled = !isRecording
                                        ) {
                                            if (isRecording) {
                                                CircularProgressIndicator(
                                                    color = Color.White,
                                                    modifier = Modifier.size(20.dp),
                                                    strokeWidth = 2.dp)
                                            } else {
                                                Icon(
                                                    Icons.Outlined.Mic,
                                                    contentDescription = "Record Audio",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(20.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ReplyBar(
    message: ChatMessage,
    currentUserId: String,
    onCancelReply: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Reply,
            contentDescription = "Replying to",
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Replying to ${if (message.senderId == currentUserId) "yourself" else "them"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = when {
                    message.imageUrl != null -> "📷 Photo"
                    message.fileUrl != null -> "📎 ${message.fileName ?: "File"}"
                    else -> message.text ?: ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        IconButton(onClick = onCancelReply) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Cancel reply",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ReplyPreviewInInput(
    message: ChatMessage,
    currentUserId: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(24.dp)
                .background(MaterialTheme.colorScheme.primary)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Replying to ${if (message.senderId == currentUserId) "yourself" else "them"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = when {
                    message.imageUrl != null -> "📷 Photo"
                    message.fileUrl != null -> "📎 ${message.fileName ?: "File"}"
                    else -> message.text ?: ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        IconButton(onClick = onCancel) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Cancel",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun DateHeader(dateKey: String) {
    val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dateKey)
    val today = Calendar.getInstance()
    val messageDate = Calendar.getInstance().apply { time = date }

    val label = when {
        today.get(Calendar.YEAR) == messageDate.get(Calendar.YEAR) &&
                today.get(Calendar.DAY_OF_YEAR) == messageDate.get(Calendar.DAY_OF_YEAR) -> "Today"
        today.get(Calendar.YEAR) == messageDate.get(Calendar.YEAR) &&
                today.get(Calendar.DAY_OF_YEAR) - 1 == messageDate.get(Calendar.DAY_OF_YEAR) -> "Yesterday"
        else -> SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(date!!)
    }

    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            modifier = Modifier.background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp)
            ).padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}