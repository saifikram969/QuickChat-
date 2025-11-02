package com.example.quickchat.presentation.viewmodel

 import android.app.Application
 import android.content.ContentValues.TAG
 import android.util.Log
 import android.widget.Toast
 import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
 import com.google.firebase.messaging.FirebaseMessaging
import androidx.lifecycle.viewModelScope
import com.example.quickchat.data.local.AppUserEntity
import com.example.quickchat.data.model.ChatMessage
import com.example.quickchat.data.model.MessageStatus
import com.example.quickchat.data.model.MessageType
import com.example.quickchat.data.repository.ChatRepository
import com.example.quickchat.data.repository.ChatRoomRepository
import com.example.quickchat.data.repository.PresenceRepository
import com.example.quickchat.data.repository.UserRepository
import com.example.quickchat.utils.ConnectivityObserver
import com.google.firebase.Firebase
import com.google.firebase.firestore.FieldValue
 import com.google.firebase.firestore.ListenerRegistration
 import com.google.firebase.firestore.firestore
 import io.grpc.Context
 import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.*

private const val CHATROOMS_COLLECTION = "chatrooms"

class ChatViewModel(
    val repository: ChatRepository,
    private val chatRoomRepository: ChatRoomRepository,
    private val presenceRepository: PresenceRepository,
    private val connectivityObserver: ConnectivityObserver,
    private val userRepository: UserRepository,
    private val application: Application

) : ViewModel() {

    private val _uploadResult = MutableStateFlow<CloudinaryUploadResponse?>(null)
    val uploadResult: StateFlow<CloudinaryUploadResponse?> = _uploadResult

    private val _uploading = MutableStateFlow(false)
    val uploading: StateFlow<Boolean> = _uploading

    private val _uploadError = MutableStateFlow<String?>(null)
    val uploadError: StateFlow<String?> = _uploadError

    private val _uiState = MutableStateFlow<ChatUiState>(ChatUiState.Loading)
    val uiState: StateFlow<ChatUiState> = _uiState

    private val _typingUserId = MutableStateFlow<String?>(null)
    val typingUserId: StateFlow<String?> = _typingUserId

    private val _presenceStatus = MutableStateFlow<Boolean?>(null)
    val presenceStatus: StateFlow<Boolean?> = _presenceStatus

    private val _messages = mutableStateListOf<ChatMessage>()
    val messages: List<ChatMessage> get() = _messages

    private val _uploadingMessages = mutableStateMapOf<String, ChatMessage>()
    val uploadingMessages: Map<String, ChatMessage> get() = _uploadingMessages

    private val _networkStatus = MutableStateFlow(true)
    val networkStatus: StateFlow<Boolean> = _networkStatus

    private var currentRoomId: String? = null
    private var currentUserId: String? = null
    private var otherUserId: String? = null
    private var typingStatusListener: ListenerRegistration? = null


    private val _isTyping = MutableStateFlow(false)
    private val _otherUserTyping = MutableStateFlow<Boolean>(false)
    val otherUserTyping: StateFlow<Boolean> = _otherUserTyping

    private val _tempMessages = mutableStateListOf<ChatMessage>()
    val tempMessages: List<ChatMessage> get() = _tempMessages

    private val _showNameDialog = MutableStateFlow(false)
    val showNameDialog: StateFlow<Boolean> = _showNameDialog


    private val  _currentUser = MutableStateFlow<AppUserEntity?>(null)
    val currentUser: StateFlow<AppUserEntity?> = _currentUser

    private val _roomName = MutableStateFlow<String?>(null)
    val roomName: StateFlow<String?> = _roomName

    init {
        viewModelScope.launch {
            currentUserId?.let { deviceId ->
                initializeUser(deviceId)
                checkAndRequestName(deviceId)
            }
        }
    }
    fun checkAndRequestName(deviceId: String) {
        viewModelScope.launch {
            try {
                val firestoreName = repository.getUserNameFromDevice(deviceId)
                val localUser = userRepository.getUser(deviceId)

                Log.d("NameCheck", "Firestore name: $firestoreName | Local name: ${localUser?.name}")

                val shouldShow = firestoreName == null &&
                        (localUser?.isNameSet != true || hasFcmTokenChanged(deviceId))

                _showNameDialog.value = shouldShow
                if (firestoreName == null && localUser?.isNameSet == true) {
                    userRepository.markNameSet(deviceId, false)
                }
            } catch (e: Exception) {
                Log.e("carn", "Name check failed", e)
                _showNameDialog.value = true
            }
        }
    }

    fun storeUserName(deviceId: String, name: String) {
        if (name.isBlank()) {
            Toast.makeText(application, "Name cannot be empty", Toast.LENGTH_SHORT).show()
            return
        }

        viewModelScope.launch {
            try {
                val token = FirebaseMessaging.getInstance().token.await()
                userRepository.updateUserName(deviceId, name)
                var retries = 3
                var success = false

                while (retries > 0 && !success) {
                    repository.storeFcmTokenWithName(deviceId, token, name)
                    delay(1000)
                    val storedName = repository.getUserNameFromDevice(deviceId)
                    success = storedName == name
                    retries--
                }

                if (!success) throw Exception("Failed after 3 retries")

                userRepository.markNameDialogShown(deviceId, true)
                _showNameDialog.value = false

            } catch (e: Exception) {
                Log.e("sun", "Name storage failed", e)
                userRepository.markNameSet(deviceId, false)
                _showNameDialog.value = true
            }
        }
    }

    private suspend fun hasFcmTokenChanged(deviceId: String): Boolean {
        return try {
            val currentToken = FirebaseMessaging.getInstance().token.await()
            val storedToken = repository.getFcmToken(deviceId)
            storedToken != currentToken
        } catch (e: Exception) {
            Log.e("ChatViewModel", "Error checking FCM token", e)
            true
        }
    }

    suspend fun initializeUserWithToken(deviceId: String, token: String) {
        val existingData = repository.getDeviceData(deviceId)
        if (existingData?.fcmToken == token) {
            userRepository.syncWithFirestore(deviceId, existingData.userName)
            return
        }
        userRepository.createUserIfNotExists(deviceId)
        repository.storeFcmToken(deviceId, token)
    }


    suspend fun initializeUser(deviceId: String) {
        try {
            val currentToken = FirebaseMessaging.getInstance().token.await()
            val tokenChanged = hasFcmTokenChanged(deviceId)
            val firestoreName = repository.getUserNameFromDevice(deviceId)
            val localUser = userRepository.getUser(deviceId)
            when {
                tokenChanged -> {
                    Log.d("UserInit", "FCM token changed - treating as fresh install")
                    userRepository.createUserIfNotExists(deviceId)
                    repository.storeFcmToken(deviceId, currentToken)
                    userRepository.markNameDialogShown(deviceId, false)
                }

                firestoreName != null && (localUser == null || !localUser.isNameSet) -> {
                    Log.d("UserInit", "Syncing name from Firestore to local")
                    userRepository.syncWithFirestore(deviceId, firestoreName)
                }

                localUser != null && localUser.isNameSet && firestoreName == null -> {
                    Log.d("UserInit", "Pushing local name to Firestore")
                    repository.storeFcmTokenWithName(deviceId, currentToken, localUser.name ?: "")
                }

                else -> {
                    Log.d("UserInit", "No initialization needed")
                }
            }
        } catch (e: Exception) {
            Log.e("ChatViewModel", "Error initializing user", e)
        }
    }

    init {
        setupStatusTracking()
    }

    init {
        observeNetworkStatus()
    }

    private fun observeNetworkStatus() {
        viewModelScope.launch {
            connectivityObserver.observe().collect { isConnected ->
                _networkStatus.value = isConnected
                if (isConnected) {
                    currentRoomId?.let { roomId ->
                        repository.syncMissingMessages(roomId)
                        retryFailedMessages(roomId)
                    }
                }
            }
        }
    }

    private suspend fun retryFailedMessages(roomId: String) {
        val failedMessages = repository.getFailedMessages(roomId)
        failedMessages.forEach { message ->
            repository.sendMessageWithOfflineSupport(roomId, message.copy(
                timestamp = System.currentTimeMillis()
            ))
        }
    }


    private fun setupStatusTracking() {
        viewModelScope.launch {
            currentRoomId?.let { roomId ->
                currentUserId?.let { userId ->
                    repository.listenForMessageStatusUpdates(roomId, userId)
                        .collect { (messageId, status) ->
                            updateMessageStatus(messageId, status)
                        }
                }
            }
        }
    }

    init {
        Log.d("VM_LIFECYCLE", "ViewModel INITIALIZED - Hash: ${hashCode()}")
    }

    suspend fun storeFcmToken(deviceId: String, token: String) {
        repository.storeFcmToken(deviceId, token)
    }
    fun setCurrentUsers(roomId: String, currentUserId: String, otherUserId: String) {
        this.currentRoomId = roomId
        this.currentUserId = currentUserId
        this.otherUserId = otherUserId

        // Initialize all components that depend on these IDs
        initializeChat(roomId, currentUserId)
        observeTypingStatus(roomId, otherUserId)
        observePresence(otherUserId)
        updatePresence(currentUserId, true)
    }
    override fun onCleared() {
        currentUserId?.let { userId ->
            updatePresence(userId, false)
            currentRoomId?.let { roomId ->
                updateTypingStatus(roomId, userId, false)
            }
        }
        typingStatusListener?.remove()
        super.onCleared()
    }


    fun observePresence(userId: String) {
        viewModelScope.launch {
            if (userId.isBlank()) {
                Log.e("ChatViewModel", "Cannot observe presence - empty user ID")
                _presenceStatus.value = null
                return@launch
            }

            presenceRepository.observeUserPresence(userId)
                .onStart {
                    Log.d("ChatViewModel", "Starting presence observation for user: $userId")
                    _presenceStatus.value = null
                }
                .catch { e ->
                    Log.e("ChatViewModel", "Error observing presence for $userId", e)
                    _presenceStatus.value = null
                }
                .collect { isOnline ->
                    Log.d(
                        "ChatViewModel",
                        "Presence update for $userId: ${if (isOnline) "online" else "offline"}"
                    )
                    _presenceStatus.value = isOnline

                    _uiState.update { currentState ->
                        if (currentState is ChatUiState.Success) {
                            currentState.copy()
                        } else {
                            currentState
                        }
                    }
                }
        }
    }

    fun updatePresence(userId: String, isOnline: Boolean) {
        viewModelScope.launch {
            presenceRepository.updateUserPresence(userId, isOnline)
        }
    }

    fun onScreenEntered(roomId: String, userId: String) {
        viewModelScope.launch {
            repository.updateLastReadTimestamp(
                roomId = roomId,
                userId = userId,
                timestamp = System.currentTimeMillis()
            )

            _uiState.update { currentState ->
                if (currentState is ChatUiState.Success) {
                    currentState.copy()
                } else {
                    currentState
                }
            }
        }
    }

    fun markMessagesAsRead(roomId: String, userId: String) {
        viewModelScope.launch {
            try {
                repository.updateLastReadTimestamp(roomId, userId, System.currentTimeMillis())

                repository.markMessagesAsRead(roomId, userId)

                val currentState = _uiState.value
                if (currentState is ChatUiState.Success) {
                    _uiState.value = currentState.copy(
                        messages = currentState.messages.map { message ->
                            if (message.senderId != userId && message.status != MessageStatus.SEEN) {
                                message.copy(status = MessageStatus.SEEN)
                            } else {
                                message
                            }
                        }
                    )
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error marking messages as read", e)
            }
        }
    }

    fun handleNotificationDeepLink(roomId: String, currentUserId: String) {
        viewModelScope.launch {
            markMessagesAsRead(roomId, currentUserId)
            initializeChat(roomId, currentUserId)
        }
    }

    fun getUnreadCount(roomId: String, userId: String): Flow<Int> {
        return chatRoomRepository.getUnreadCountFlow(roomId, userId)
    }

    fun initializeChat(roomId: String, currentUserId: String) {
        this.currentRoomId = roomId
        this.currentUserId = currentUserId

        viewModelScope.launch {
            _uiState.value = ChatUiState.Loading


            // Fetch room details first to get the name immediately
            val roomDetails = chatRoomRepository.getRoomDetails(roomId, currentUserId)
            val roomName = roomDetails?.name ?: "Chat Room"
            _roomName.value = roomName // Set the room name immediately

            // Always show cached messages first
            val cachedMessages = repository.getCachedMessages(roomId)
            if (cachedMessages.isNotEmpty()) {
                _uiState.value = ChatUiState.Success(
                    messages = cachedMessages,
                    currentUserId = currentUserId,
                    roomId = roomId,
                    roomName = roomName,
                    isOffline = !networkStatus.value
                )
            }

            if (networkStatus.value) {
                try {
                    // Sync with server when online
                    repository.syncMissingMessages(roomId)
                    val updatedMessages = repository.getCachedMessages(roomId)
                    _uiState.value = if (updatedMessages.isNotEmpty()) {
                        ChatUiState.Success(
                            messages = updatedMessages,
                            currentUserId = currentUserId,
                            roomId = roomId,
                            roomName = roomName
                        )
                    } else {
                        ChatUiState.Error("No messages found")
                    }
                } catch (e: Exception) {
                    Log.e("ChatVM", "Sync failed", e)
                    if (cachedMessages.isNotEmpty()) {
                        _uiState.value = ChatUiState.Success(
                            messages = cachedMessages,
                            currentUserId = currentUserId,
                            roomId = roomId,
                            roomName,
                            isOffline = true
                        )
                    } else {
                        _uiState.value = ChatUiState.Error(
                            "Offline: No cached messages available"
                        )
                    }
                }
            } else if (cachedMessages.isEmpty()) {
                _uiState.value = ChatUiState.Error(
                    "Offline: No cached messages available"
                )
            }

            // Setup listener for real-time updates
            if (networkStatus.value) {
                repository.listenToMessages(roomId).collect { messages ->
                    _uiState.value = ChatUiState.Success(
                        messages = messages.sortedBy { it.timestamp },
                        currentUserId = currentUserId,
                        roomId = roomId,
                        roomName = roomName
                    )
                }
            }
        }
    }



    fun addUploadingMessage(message: ChatMessage) {
        _uploadingMessages[message.id] = message
    }

    fun updateUploadingMessage(
        messageId: String,
        fileUrl: String? = null,
        status: MessageStatus? = null,
        uploadProgress: Float? = null
    ) {
        _uploadingMessages[messageId]?.let { existing ->
            _uploadingMessages[messageId] = existing.copy(
                fileUrl = fileUrl ?: existing.fileUrl,
                status = status ?: existing.status,
                uploadProgress = uploadProgress ?: existing.uploadProgress
            )
        }
    }

    fun removeUploadingMessage(messageId: String) {
        _uploadingMessages.remove(messageId)
    }

    fun sendMessage(
        roomId: String,
        senderId: String,
        text: String? = null,
        imageUrl: String? = null,
        thumbnailUrl: String? = null,
        fileUrl: String? = null,
        fileType: String? = null,
        fileName: String? = null,
        fileSize: Long? = null,
        repliedToMessage: ChatMessage? = null
    ) {
        Log.d("SendMessage", " sendMessage called → roomId=$roomId, senderId=$senderId")
        Log.d("SendMessage", "Parameters → text=$text, imageUrl=$imageUrl, fileUrl=$fileUrl, fileType=$fileType, fileName=$fileName, fileSize=$fileSize")

        viewModelScope.launch {
            val messageType = when {
                !imageUrl.isNullOrBlank() -> MessageType.IMAGE
                !fileUrl.isNullOrBlank() -> when {
                    fileType?.startsWith("audio/") == true -> MessageType.AUDIO
                    fileType?.startsWith("application/pdf") == true -> MessageType.PDF
                    else -> MessageType.FILE
                }
                else -> MessageType.TEXT
            }
            Log.d("SendMessage", "📄 Determined messageType=$messageType")

            val message = ChatMessage(
                id = UUID.randomUUID().toString(),
                text = text ?: "",
                senderId = senderId,
                timestamp = System.currentTimeMillis(),
                status = MessageStatus.SENDING,
                messageType = messageType,
                imageUrl = imageUrl,
                thumbnailUrl = thumbnailUrl,
                fileUrl = fileUrl,
                fileType = fileType ?: "",
                fileName = fileName ?: "",
                fileSize = fileSize,
                // Reply fields
                repliedToMessageId = repliedToMessage?.id,
                repliedToMessageText = repliedToMessage?.text,
                repliedToMessageType = repliedToMessage?.messageType,
                repliedToSenderId = repliedToMessage?.senderId,
                repliedToImageUrl = repliedToMessage?.imageUrl,
                repliedToFileName = repliedToMessage?.fileName
            )
            Log.d("SendMessage", " Created ChatMessage object: $message")
            updateMessages(message)

            repository.sendMessageWithOfflineSupport(roomId, message)
                .onSuccess {
                    updateMessageStatus(message.id, MessageStatus.SENT)
                }
                .onFailure { e ->
                    updateMessageStatus(message.id, MessageStatus.FAILED)
                }
        }
    }
    
    private fun createMessage(
        senderId: String,
        text: String,
        messageType: MessageType,
        imageUrl: String? = null
    ): ChatMessage {
        return ChatMessage(
            id = UUID.randomUUID().toString(),
            text = text,
            senderId = senderId,
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.SENDING,
            clientGeneratedId = "${senderId}_${System.currentTimeMillis()}",
            messageType = messageType,
            imageUrl = imageUrl
        )
    }

    fun observeTypingStatus(roomId: String, userId: String) {
        // Clear previous listener if exists
        typingStatusListener?.remove()

        if (roomId.isEmpty() || userId.isEmpty()) {
            _otherUserTyping.value = false
            return
        }

        typingStatusListener = Firebase.firestore.collection(CHATROOMS_COLLECTION)
            .document(roomId)
            .collection("typingStatus")
            .document(userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error observing typing status", error)
                    _otherUserTyping.value = false
                    return@addSnapshotListener
                }

                val isTyping = snapshot?.getBoolean("isTyping") ?: false
                _otherUserTyping.value = isTyping

                _uiState.update { currentState ->
                    if (currentState is ChatUiState.Success) {
                        currentState.copy(
                            typingUserId = if (isTyping) userId else null,
                            isOtherUserTyping = isTyping
                        )
                    } else {
                        currentState
                    }
                }
            }
    }
    fun retryMessage(roomId: String, message: ChatMessage) {
        val newClientId = "${message.senderId}_${System.currentTimeMillis()}"
        val retriedMessage = message.copy(
            status = MessageStatus.SENDING,
            clientGeneratedId = newClientId,
            timestamp = System.currentTimeMillis()
        )

        updateMessages(retriedMessage)

        viewModelScope.launch {
            repository.cacheMessage(roomId, retriedMessage)

            val result = runCatching {
                repository.sendMessage(roomId, retriedMessage)
            }

            val finalStatus = if (result.getOrNull()?.isSuccess == true) {
                MessageStatus.SENT
            } else {
                MessageStatus.FAILED
            }

            updateMessageStatus(retriedMessage.id, finalStatus)
            repository.updateMessageStatus(retriedMessage.id, finalStatus)
        }
    }

    private fun updateMessages(newMessage: ChatMessage) {
        _uiState.update { current ->
            if (current is ChatUiState.Success) {
                current.copy(
                    messages = (current.messages + newMessage)
                        .distinctBy { it.id }
                        .sortedBy { it.timestamp }
                )
            } else current
        }
    }
    private fun updateMessageStatus(messageId: String, status: MessageStatus) {
        _uiState.update { current ->
            if (current is ChatUiState.Success) {
                current.copy(
                    messages = current.messages.map {
                        if (it.id == messageId) it.copy(status = status) else it
                    }
                )
            } else current
        }

        viewModelScope.launch {
            repository.updateMessageStatus(messageId, status)
        }
    }
    fun setTypingStatus(isTyping: Boolean) {
        currentRoomId?.let { roomId ->
            currentUserId?.let { userId ->
                viewModelScope.launch {
                    updateTypingStatus(roomId, userId, isTyping)
                    _isTyping.value = isTyping
                }
            }
        }
    }

    fun updateTypingStatus(roomId: String, userId: String, isTyping: Boolean) {
        val typingRef = Firebase.firestore
            .collection(CHATROOMS_COLLECTION)
            .document(roomId)
            .collection("typingStatus")
            .document(userId)

        val data = mapOf(
            "isTyping" to isTyping,
            "timestamp" to FieldValue.serverTimestamp()
        )

        typingRef.set(data)
    }

    fun enterChatRoom(userId: String) {
        viewModelScope.launch {
            presenceRepository.updateUserPresence(userId, true)
        }
    }

    fun onNetworkRestored(roomId: String) {
        viewModelScope.launch {
            repository.syncMessageGaps(roomId)

            val failedMessages = repository.getFailedMessages(roomId)

            failedMessages.forEachIndexed { index, message ->
                launch {
                    delay((1L shl index.coerceAtMost(5)) * 1000)
                    retryMessage(roomId, message)
                }
            }
        }
    }

    fun deleteChatroom(roomId: String, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                // 1. Mark as deleted in Firestore
                Firebase.firestore.collection(CHATROOMS_COLLECTION)
                    .document(roomId)
                    .update(
                        mapOf(
                            "isDeleted" to true,
                            "lastUpdated" to FieldValue.serverTimestamp()
                        )
                    )
                    .await()

                repository.clearRoomCache(roomId)
                if (_uiState.value is ChatUiState.Success &&
                    (_uiState.value as ChatUiState.Success).roomId == roomId
                ) {
                    _uiState.value = ChatUiState.Loading
                }
                _refreshChatList.value = true

                onComplete()

            } catch (e: Exception) {
                Log.e("ChatViewModel", "Delete error", e)
                _uiState.value = ChatUiState.Error("Delete failed: ${e.message}")
            }
        }
    }
    private val _refreshChatList = MutableStateFlow(false)
    val refreshChatList: StateFlow<Boolean> = _refreshChatList
    private suspend fun sendDeletionNotification(
        roomId: String,
        senderId: String,
        recipientId: String
    ) {
        try {
            val recipientToken = repository.getFcmToken(recipientId) ?: return
            val senderName = Firebase.firestore.collection("users")
                .document(senderId)
                .get()
                .await()
                .getString("name") ?: senderId

            val payload = mapOf(
                "to" to recipientToken,
                "notification" to mapOf(
                    "title" to "Chat deleted",
                    "body" to "$senderName deleted the chat",
                    "click_action" to "FLUTTER_NOTIFICATION_CLICK"
                ),
                "data" to mapOf(
                    "type" to "chat_deleted",
                    "roomId" to roomId,
                    "senderId" to senderId
                )
            )
            Firebase.firestore.collection("notification_requests")
                .add(payload)
                .await()
        } catch (e: Exception) {
            Log.e("ChatViewModel", "Error sending deletion notification", e)
        }
    }
    fun deleteChatroomForUser(
        roomId: String,
        userId: String,
        onComplete: () -> Unit = {}
    ) {
        viewModelScope.launch {
            try {
                Firebase.firestore.collection(CHATROOMS_COLLECTION)
                    .document(roomId)
                    .update(
                        mapOf(
                            "participants" to FieldValue.arrayRemove(userId),
                            "lastUpdated" to FieldValue.serverTimestamp()
                        )
                    )
                    .await()

                deleteUserMessages(roomId, userId)
                repository.clearRoomCache(roomId)
                repository.clearMessagesForUserInRoom(
                    roomId,
                    userId
                )
                onComplete()

            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error leaving chatroom", e)
                _uiState.value = ChatUiState.Error("Failed to leave chatroom: ${e.message}")
            }
        }
    }

    fun addTempMessage(message: ChatMessage) {
        _tempMessages.add(message.copy(isTemp = true))
        updateMessages(message.copy(isTemp = true))
    }

    fun updateTempMessage(
        messageId: String,
        fileUrl: String? = null,
        status: MessageStatus,
        isTemp: Boolean = false,
        uploadProgress: Float? = null
    ) {
        _tempMessages.replaceAll { msg ->
            if (msg.id == messageId) msg.copy(
                fileUrl = fileUrl,
                status = status,
                uploadProgress = uploadProgress
            ) else msg
        }

        _uiState.update { current ->
            if (current is ChatUiState.Success) {
                current.copy(messages = current.messages.map {
                    if (it.id == messageId) it.copy(
                        fileUrl = fileUrl,
                        status = status,
                        uploadProgress = uploadProgress
                    ) else it
                })
            } else current
        }
    }
    
    fun removeTempMessage(messageId: String) {
        _tempMessages.removeAll { it.id == messageId }
    }
    
    private suspend fun deleteUserMessages(roomId: String, userId: String) {
        val messagesSnapshot = Firebase.firestore.collection(CHATROOMS_COLLECTION)
            .document(roomId)
            .collection("messages")
            .whereEqualTo("senderId", userId)
            .get()
            .await()

        if (messagesSnapshot.isEmpty) return

        val batch = Firebase.firestore.batch()
        for (doc in messagesSnapshot.documents) {
            batch.delete(doc.reference)
        }
        batch.commit().await()
    }
    
}

sealed class ChatUiState {
    object Loading : ChatUiState()

    data class Success(
        val messages: List<ChatMessage>,
        val currentUserId: String,
        val roomId: String,
        val roomName: String,
        val typingUserId: String? = null,
        val isOffline: Boolean = false,
        val isOtherUserTyping: Boolean = false,
        val pendingMessages: List<ChatMessage> = emptyList()
    ) : ChatUiState()

    data class Error(val message: String) : ChatUiState()
}

data class CloudinaryUploadResponse(
    val secureUrl: String,
    val thumbnailUrl: String? = null
)