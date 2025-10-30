package com.example.quickchat.presentation.ChatRoomListScreen
import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import com.example.quickchat.presentation.component.CreateRoomBottomSheet
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.modifier.modifierLocalConsumer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.quickchat.R
import com.example.quickchat.data.model.ChatRoom
import com.example.quickchat.data.model.User
import com.example.quickchat.presentation.component.CreateRoomBottomSheet
import com.example.quickchat.presentation.viewmodel.ChatRoomListViewModel
import com.google.firebase.firestore.BuildConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatRoomListScreen(
    userId: String,
    onChatRoomClick: (roomId: String, otherUserId: String) -> Unit,
    onBackClick: () -> Unit,
    viewModel: ChatRoomListViewModel = koinViewModel(parameters = { parametersOf(userId) })
) {
    // Add debug logging
    LaunchedEffect(Unit) {
        viewModel.fetchChatRooms(true)
    }


    val rooms by viewModel.chatRooms.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val creationState by viewModel.roomCreationState.collectAsState()

    val showUndo by viewModel.showUndo.collectAsState()
    val shareLinkState by viewModel.shareLinkState.collectAsState()

// Add this to track if rooms have been loaded at least once
    var isInitialLoad by remember { mutableStateOf(true) }

    // Existing state declarations...
    var showCreateRoomSheet by remember { mutableStateOf(false) }
    var newChatUsername by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showShareDialog by remember { mutableStateOf(false) }
    var selectedRoomId by remember { mutableStateOf("") }

    var newGroupTitle by remember { mutableStateOf("") }
    var selectedUsers by remember { mutableStateOf<List<User>>(emptyList()) }
    var isCreatingGroup by remember { mutableStateOf(false) }
    var availableUsers by remember { mutableStateOf<List<User>>(emptyList()) }

    val isLoadingUsers by viewModel.isLoadingUsers.collectAsState()
    LaunchedEffect(availableUsers) {
        Log.d("UI_DEBUG", "UI received ${availableUsers.size} users")
        availableUsers.forEach { user ->
            Log.d("UI_DEBUG", "UI user: ${user.userName}")
        }
    }

    LaunchedEffect(showCreateRoomSheet, isCreatingGroup) {
        if (showCreateRoomSheet) {
            Log.d("UI_DEBUG", "Loading users for sheet...")
            viewModel.loadAvailableUsers(userId) // Use userId as roomId for now
        }
    }

    // Handle creation based on type
    val onCreateClick = {
        if (isCreatingGroup) {
            viewModel.createGroupChat(newGroupTitle, selectedUsers.map { it.deviceId })
        } else {
            if (selectedUsers.size == 1) {
                viewModel.createChatRoom(selectedUsers.first().deviceId)
            }
        }
    }


    // Handle undo action
    LaunchedEffect(showUndo) {
        showUndo?.let { undoAction ->
            val result = snackbarHostState.showSnackbar(
                message = "Chat ${undoAction.action}",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.undoAction(undoAction.roomId, undoAction.action)
            }
            viewModel.clearUndo()
        }
    }

    // Handle room creation state changes
    LaunchedEffect(creationState) {
        when (creationState) {
            is ChatRoomListViewModel.RoomCreationState.Success -> {
                val roomId = (creationState as ChatRoomListViewModel.RoomCreationState.Success).roomId
                showCreateRoomSheet = false
                newChatUsername = ""
                viewModel.resetRoomCreationState()
                val otherUserId = newChatUsername
                onChatRoomClick(roomId, otherUserId)
            }
            is ChatRoomListViewModel.RoomCreationState.Error -> {
                val errorMessage = (creationState as ChatRoomListViewModel.RoomCreationState.Error).message
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = errorMessage ?: "Failed to create chat room",
                        withDismissAction = true
                    )
                }
            }
            else -> {}
        }
    }
    when {
        isLoading && rooms.isEmpty() -> FullScreenLoading()
        error != null -> ErrorState(error) { viewModel.fetchChatRooms() }
        rooms.isEmpty() -> EmptyState { viewModel.fetchChatRooms() }
        else -> {
            // Add debug text
            if (BuildConfig.DEBUG) {
                Text(
                    text = "Showing ${rooms.size} rooms (${rooms.count { it.isLocal }} local)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            LazyColumn {
                items(rooms) { room ->
                    // Your existing room item
                }
            }
        }
    }




    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.chat_rooms)) },
                actions = {
                    IconButton(
                        onClick = { viewModel.fetchChatRooms() },
                        enabled = !isLoading
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.refresh)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                // Group chat FAB
                FloatingActionButton(
                    onClick = {
                        isCreatingGroup = true
                        showCreateRoomSheet = true
                    },
                    modifier = Modifier.size(40.dp),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Create group")
                }

                Spacer(modifier = Modifier.height(8.dp))

                // DM FAB
                FloatingActionButton(
                    onClick = {
                        isCreatingGroup = false
                        showCreateRoomSheet = true
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Create chat")
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                isLoading && rooms.isEmpty() -> FullScreenLoading()
                error != null -> ErrorState(error) { viewModel.fetchChatRooms() }
                rooms.isEmpty() -> EmptyState { viewModel.fetchChatRooms() }
                else -> ChatRoomListContent(
                    rooms = rooms,
                    userId = userId,
                    onRoomClick = { room ->
                        val otherUserId = room.participants.firstOrNull { it != userId } ?: ""
                        viewModel.onRoomClicked(
                            roomId = room.roomId,
                            otherUserId = otherUserId,
                            onNavigate = { route ->
                                onChatRoomClick(room.roomId, otherUserId)
                            }
                        )
                    },
                    viewModel = viewModel,
                    isLoading = isLoading
                )
            }

            CreateRoomBottomSheet(
                show = showCreateRoomSheet,
                onDismiss = {
                    showCreateRoomSheet = false
                    newGroupTitle = ""
                    selectedUsers = emptyList()
                    viewModel.resetRoomCreationState()
                },
                roomTitle = newGroupTitle,
                onRoomTitleChange = { newGroupTitle = it },
                initialMembers = selectedUsers,
                onMemberSelectionChange = { user, selected ->
                    selectedUsers = if (selected) {
                        selectedUsers + user
                    } else {
                        selectedUsers.filter { it.deviceId != user.deviceId }
                    }
                },
                onCreateClick = onCreateClick,
                isGroup = isCreatingGroup,
                isLoading = creationState is ChatRoomListViewModel.RoomCreationState.Loading,
                viewModel = viewModel
            )
        }
    }
}




@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRoomListContent(
    rooms: List<ChatRoom>,
    userId: String,
    onRoomClick: (ChatRoom) -> Unit,
    viewModel: ChatRoomListViewModel,
    isLoading: Boolean
) {
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp, horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(rooms, key = { it.roomId }) { room ->

                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .fillMaxWidth()
                ){
                SwipeActionsItem(
                    onArchive = { viewModel.archiveRoom(room.roomId) },
                    onDelete = { viewModel.deleteRoom(room.roomId) },
                    content = {
                        ChatRoomListItem(
                            room = room,
                            currentUserId = userId,
                            onClick = { onRoomClick(room) },
                            onMuteToggle = { viewModel.toggleMuteStatus(room.roomId) }
                        )
                    }
                )
            }
        }
    }
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
            )
        }
    }
}


@Composable
fun SwipeActionsItem(
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    content: @Composable () -> Unit
) {
    var offsetX by remember { mutableStateOf(0f) }
    val maxOffset = with(LocalDensity.current) { 100.dp.toPx() }
    val swipeThreshold = 0.5f
    var actionToPerform by remember { mutableStateOf<(() -> Unit)?>(null) }

    val animatedOffset by animateFloatAsState(
        targetValue = offsetX.coerceIn(-maxOffset, maxOffset),
        animationSpec = tween(durationMillis = 300),
        finishedListener = {
            actionToPerform?.invoke()
            actionToPerform = null
            offsetX = 0f
        }
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        // Delete background (left swipe - red)
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(100.dp)
                .background(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp)
                ),
            contentAlignment = Alignment.CenterStart
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete",
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(start = 16.dp)
            )
        }

        // Archive background (right swipe - green)
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(100.dp)
                .background(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ),
            contentAlignment = Alignment.CenterEnd
        ) {
            Icon(
                imageVector = Icons.Default.Archive,
                contentDescription = "Archive",
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(end = 16.dp)
            )
        }

        // Swipeable content
        Box(
            modifier = Modifier
                .offset { IntOffset(animatedOffset.roundToInt(), 0) }
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(8.dp)
                )
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (abs(offsetX) > maxOffset * swipeThreshold) {
                                actionToPerform = if (offsetX > 0) onArchive else onDelete
                            } else {
                                offsetX = 0f
                            }
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            offsetX += dragAmount
                        }
                    )
                }
        ) {
            content()
        }
    }
}

@Composable
fun ChatRoomListItem(
    room: ChatRoom,
    currentUserId: String,
    onClick: () -> Unit,
    onMuteToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Determine display name based on room type
    val displayName = remember(room) {
        when (room.type) {
            "group" -> room.name // Use the group name/title for group chats
            else -> { // For direct messages, show the other participant's name
                val otherUserId = room.participants.firstOrNull { it != currentUserId } ?: ""
                "Chat with $otherUserId"
            }
        }
    }

    val dateFormat = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }
    val timeString by remember(room.lastTimestamp) {
        derivedStateOf { dateFormat.format(Date(room.lastTimestamp)) }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Mute button
            IconButton(
                onClick = {
                    if (!room.isProcessingMute) {
                        onMuteToggle()
                    }
                },
                enabled = !room.isProcessingMute
            ) {
                if (room.isProcessingMute) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(
                        imageVector = if (room.isMuted) Icons.Default.NotificationsOff
                        else Icons.Default.Notifications,
                        contentDescription = if (room.isMuted) "Unmute" else "Mute",
                        tint = if (room.isMuted) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Avatar - show different icon for groups
            Surface(
                modifier = Modifier.size(48.dp),
                shape = MaterialTheme.shapes.medium,
                color = if (room.type == "group") MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (room.type == "group") {
                        // Show first letter of group name for groups
                        Text(
                            text = room.name.take(1).uppercase(),
                            style = MaterialTheme.typography.titleLarge
                        )
                    } else {
                        // Show first letter of other user's ID for DMs
                        val otherUserId = room.participants.firstOrNull { it != currentUserId } ?: ""
                        Text(
                            text = otherUserId.take(1).uppercase(),
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (room.isMuted) {
                        Icon(
                            imageVector = Icons.Default.VolumeOff,
                            contentDescription = "Muted",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Text(
                    text = room.lastMessage ?: "No messages yet",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = timeString,
                    style = MaterialTheme.typography.labelSmall
                )
                Spacer(modifier = Modifier.height(4.dp))
                if (room.unreadCount > 0) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(24.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primary,
                                shape = CircleShape
                            )
                    ) {
                        Text(
                            text = room.unreadCount.toString(),
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FullScreenLoading() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorState(
    error: String?,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = error ?: "Unknown error",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Icon(Icons.Default.Refresh, contentDescription = "Retry")
            Spacer(modifier = Modifier.width(8.dp))
            Text("Try Again")
        }
    }
}

@Composable
private fun EmptyState(onRefresh: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Icon above the text
        Icon(
            imageVector = Icons.Filled.Chat,
            contentDescription = "No chats available",
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Text with clickable refresh action
        Text(
            text = stringResource(R.string.no_chat_rooms),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.clickable(onClick = onRefresh)
        )
    }
}