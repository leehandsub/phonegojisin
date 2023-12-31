package com.ssafy.phonesin.common.websocket

import android.os.AsyncTask
import android.os.Build
import android.os.Handler
import android.util.Log
import android.util.Pair
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.neovisionaries.ws.client.ThreadType
import com.neovisionaries.ws.client.WebSocket
import com.neovisionaries.ws.client.WebSocketException
import com.neovisionaries.ws.client.WebSocketFactory
import com.neovisionaries.ws.client.WebSocketFrame
import com.neovisionaries.ws.client.WebSocketListener
import com.neovisionaries.ws.client.WebSocketState
import com.ssafy.phonesin.common.CustomSdpObserver
import com.ssafy.phonesin.common.openvidu.RemoteParticipant
import com.ssafy.phonesin.common.openvidu.Session
import com.ssafy.phonesin.common.utils.createRemoteParticipantVideo
import com.ssafy.phonesin.common.JsonConstants
import com.ssafy.phonesin.common.JsonConstants.REMOTE_CONTAINER_VIEW
import com.ssafy.phonesin.common.JsonConstants.REMOTE_VIDEO_VIEW
import org.json.JSONException
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection.IceServer
import org.webrtc.PeerConnection.SignalingState
import org.webrtc.SessionDescription
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.security.KeyManagementException
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Arrays
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class CustomWebSocket(private val session: Session, private val activity: AppCompatActivity) :
    AsyncTask<AppCompatActivity?, Void?, Void?>(), WebSocketListener {
    private val TAG = "CustomWebSocketListener"
    private val PING_MESSAGE_INTERVAL = 5
    private val trustManagers = arrayOf<TrustManager>(object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate?> {
            return arrayOfNulls(0)
        }

        @Throws(CertificateException::class)
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            Log.i(TAG, ": authType: $authType")
        }

        @Throws(CertificateException::class)
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            Log.i(TAG, ": authType: $authType")
        }
    })
    private val RPC_ID = AtomicInteger(0)
    private val ID_PING = AtomicInteger(-1)
    private val ID_JOINROOM = AtomicInteger(-1)
    private val ID_LEAVEROOM = AtomicInteger(-1)
    private val ID_PUBLISHVIDEO = AtomicInteger(-1)
    private val IDS_PREPARERECEIVEVIDEO: MutableMap<Int, Pair<String?, String>> =
        ConcurrentHashMap()
    private val IDS_RECEIVEVIDEO: MutableMap<Int, String?> = ConcurrentHashMap()
    private val IDS_ONICECANDIDATE = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())
    private var mediaServer: String? = null
    private lateinit var websocket: WebSocket
    private var websocketCancelled = false

    @Throws(Exception::class)
    override fun onTextMessage(websocket: WebSocket, text: String) {
        Log.i(TAG, "Text Message $text")
        val json = JSONObject(text)
        if (json.has(JsonConstants.RESULT)) {
            handleServerResponse(json)
        } else if (json.has(JsonConstants.ERROR)) {
            handleServerError(json)
        } else {
            handleServerEvent(json)
        }
    }

    @Throws(JSONException::class)
    private fun handleServerResponse(json: JSONObject) {
        val rpcId = json.getInt(JsonConstants.ID)
        val result = JSONObject(json.getString(JsonConstants.RESULT))
        if (result.has("value") && result.getString("value") == "pong") {
            // Response to ping
            Log.i(TAG, "pong")
        } else if (rpcId == ID_JOINROOM.get()) {
            // Response to joinRoom
            val localParticipant = session.localParticipant
            val localConnectionId = result.getString(JsonConstants.ID)
            localParticipant!!.connectionId = localConnectionId
            mediaServer = result.getString(JsonConstants.MEDIA_SERVER)
            if (result.has(JsonConstants.ICE_SERVERS)) {
                val jsonIceServers = result.getJSONArray(JsonConstants.ICE_SERVERS)
                val iceServers: MutableList<IceServer?> = ArrayList<IceServer?>()
                for (i in 0 until jsonIceServers.length()) {
                    val jsonIceServer = jsonIceServers.getJSONObject(i)
                    val urls: MutableList<String?> = ArrayList<String?>()
                    if (jsonIceServer.has("urls")) {
                        val jsonUrls = jsonIceServer.getJSONArray("urls")
                        for (j in 0 until jsonUrls.length()) {
                            urls.add(jsonUrls.getString(j))
                        }
                    }
                    if (jsonIceServer.has("url")) {
                        urls.add(jsonIceServer.getString("url"))
                    }
                    var iceServerBuilder: IceServer.Builder
                    iceServerBuilder = try {
                        IceServer.builder(urls)
                    } catch (e: IllegalArgumentException) {
                        continue
                    }
                    if (jsonIceServer.has("username")) {
                        iceServerBuilder.setUsername(jsonIceServer.getString("username"))
                    }
                    if (jsonIceServer.has("credential")) {
                        iceServerBuilder.setPassword(jsonIceServer.getString("credential"))
                    }
                    iceServers.add(iceServerBuilder.createIceServer())
                }
                session.setIceServers(iceServers)
            }
            val localPeerConnection = session.createLocalPeerConnection()
            localParticipant.peerConnection = localPeerConnection
            val sdpConstraints = MediaConstraints()
            sdpConstraints.mandatory.add(
                MediaConstraints.KeyValuePair(
                    "offerToReceiveAudio",
                    "false"
                )
            )
            sdpConstraints.mandatory.add(
                MediaConstraints.KeyValuePair(
                    "offerToReceiveVideo",
                    "false"
                )
            )
            session.createOfferForPublishing(sdpConstraints)
            if (result.getJSONArray(JsonConstants.VALUE).length() > 0) {
                // There were users already connected to the session
                addRemoteParticipantsAlreadyInRoom(result)
            }
        } else if (rpcId == ID_LEAVEROOM.get()) {
            // Response to leaveRoom
            if (websocket!!.isOpen) {
                websocket!!.disconnect()
            }
        } else if (rpcId == ID_PUBLISHVIDEO.get()) {
            // Response to publishVideo
            val localParticipant = session.localParticipant
            val remoteSdpAnswer =
                SessionDescription(SessionDescription.Type.ANSWER, result.getString("sdpAnswer"))
            localParticipant!!.peerConnection!!.setRemoteDescription(
                CustomSdpObserver("publishVideo_setRemoteDescription"),
                remoteSdpAnswer
            )
        } else if (IDS_PREPARERECEIVEVIDEO.containsKey(rpcId)) {
            // Response to prepareReceiveVideoFrom
            val participantAndStream = IDS_PREPARERECEIVEVIDEO.remove(rpcId)!!
            val remoteParticipant = session.getRemoteParticipant(participantAndStream.first!!)
            val streamId = participantAndStream.second
            val remoteSdpOffer =
                SessionDescription(SessionDescription.Type.OFFER, result.getString("sdpOffer"))
            remoteParticipant!!.peerConnection!!.setRemoteDescription(object :
                CustomSdpObserver("prepareReceiveVideoFrom_setRemoteDescription") {
                override fun onSetSuccess() {
                    super.onSetSuccess()
                    subscriptionInitiatedFromServer(remoteParticipant, streamId)
                }
            }, remoteSdpOffer)
        } else if (IDS_RECEIVEVIDEO.containsKey(rpcId)) {
            // Response to receiveVideoFrom
            val id = IDS_RECEIVEVIDEO.remove(rpcId)
            if ("kurento" == mediaServer) {
                val sessionDescription = SessionDescription(
                    SessionDescription.Type.ANSWER,
                    result.getString("sdpAnswer")
                )
                session.getRemoteParticipant(id!!)!!.peerConnection!!.setRemoteDescription(
                    CustomSdpObserver("remoteSetRemoteDesc"),
                    sessionDescription
                )
            }
        } else if (IDS_ONICECANDIDATE.contains(rpcId)) {
            // Response to onIceCandidate
            IDS_ONICECANDIDATE.remove(rpcId)
        } else {
            Log.e(TAG, "Unrecognized server response: $result")
        }
    }

    @Throws(JSONException::class)
    private fun handleServerError(json: JSONObject) {
        val error = JSONObject(json.getString(JsonConstants.ERROR))
        val errorCode = error.getInt("code")
        val errorMessage = error.getString("message")
        Log.e(TAG, "Server error code $errorCode: $errorMessage")
    }

    fun joinRoom() {
        val joinRoomParams: MutableMap<String, String> = HashMap()
        joinRoomParams[JsonConstants.METADATA] =
            "{\"clientData\": \"" + session.localParticipant!!.participantName + "\"}"
        joinRoomParams["secret"] = ""
        joinRoomParams["session"] = session.id
        joinRoomParams["platform"] = "Android " + Build.VERSION.SDK_INT
        joinRoomParams["token"] = session.token
        joinRoomParams["sdkVersion"] = "2.28.0"
        ID_JOINROOM.set(this.sendJson(JsonConstants.JOINROOM_METHOD, joinRoomParams))
    }

    fun leaveRoom() {
        ID_LEAVEROOM.set(this.sendJson(JsonConstants.LEAVEROOM_METHOD))
    }

    fun publishVideo(sessionDescription: SessionDescription) {
        val publishVideoParams: MutableMap<String, String> = HashMap()
        publishVideoParams["audioActive"] = "true"
        publishVideoParams["videoActive"] = "true"
        publishVideoParams["doLoopback"] = "false"
        publishVideoParams["frameRate"] = "30"
        publishVideoParams["hasAudio"] = "true"
        publishVideoParams["hasVideo"] = "true"
        publishVideoParams["typeOfVideo"] = "CAMERA"
        publishVideoParams["videoDimensions"] = "{\"width\":320, \"height\":240}"
        publishVideoParams["sdpOffer"] = sessionDescription.description
        ID_PUBLISHVIDEO.set(this.sendJson(JsonConstants.PUBLISHVIDEO_METHOD, publishVideoParams))
    }

    fun prepareReceiveVideoFrom(remoteParticipant: RemoteParticipant?, streamId: String) {
        val prepareReceiveVideoFromParams: MutableMap<String, String> = HashMap()
        prepareReceiveVideoFromParams["sender"] = streamId
        prepareReceiveVideoFromParams["reconnect"] = "false"
        IDS_PREPARERECEIVEVIDEO[this.sendJson(
            JsonConstants.PREPARERECEIVEVIDEO_METHOD,
            prepareReceiveVideoFromParams
        )] =
            Pair(
                remoteParticipant!!.connectionId, streamId
            )
    }

    fun receiveVideoFrom(
        sessionDescription: SessionDescription,
        remoteParticipant: RemoteParticipant?,
        streamId: String
    ) {
        val receiveVideoFromParams: MutableMap<String, String> = HashMap()
        receiveVideoFromParams["sender"] = streamId
        if ("kurento" == mediaServer) {
            receiveVideoFromParams["sdpOffer"] = sessionDescription.description
        } else {
            receiveVideoFromParams["sdpAnswer"] = sessionDescription.description
        }
        IDS_RECEIVEVIDEO[this.sendJson(JsonConstants.RECEIVEVIDEO_METHOD, receiveVideoFromParams)] =
            remoteParticipant!!.connectionId
    }

    fun onIceCandidate(iceCandidate: IceCandidate, endpointName: String?) {
        val onIceCandidateParams: MutableMap<String, String> = HashMap()
        if (endpointName != null) {
            onIceCandidateParams["endpointName"] = endpointName
        }
        onIceCandidateParams["candidate"] = iceCandidate.sdp
        onIceCandidateParams["sdpMid"] = iceCandidate.sdpMid
        onIceCandidateParams["sdpMLineIndex"] = Integer.toString(iceCandidate.sdpMLineIndex)
        IDS_ONICECANDIDATE.add(
            this.sendJson(
                JsonConstants.ONICECANDIDATE_METHOD,
                onIceCandidateParams
            )
        )
    }

    @Throws(JSONException::class)
    private fun handleServerEvent(json: JSONObject) {
        if (!json.has(JsonConstants.METHOD)) {
            Log.e(
                TAG, "Server event lacks a field '" + JsonConstants.METHOD + "'; JSON: "
                        + json.toString()
            )
            return
        }
        val method = json.getString(JsonConstants.METHOD)
        if (!json.has(JsonConstants.PARAMS)) {
            Log.e(
                TAG, "Server event '" + method + "' lacks a field '" + JsonConstants.PARAMS
                        + "'; JSON: " + json.toString()
            )
            return
        }
        val params = JSONObject(json.getString(JsonConstants.PARAMS))
        when (method) {
            JsonConstants.ICE_CANDIDATE -> iceCandidateEvent(params)
            JsonConstants.PARTICIPANT_JOINED -> participantJoinedEvent(params)
            JsonConstants.PARTICIPANT_PUBLISHED -> participantPublishedEvent(params)
            JsonConstants.PARTICIPANT_LEFT -> participantLeftEvent(params)
            else -> throw JSONException("Unknown server event '$method'")
        }
    }

    fun sendJson(method: String?): Int {
        return this.sendJson(method, HashMap())
    }

    @Synchronized
    fun sendJson(method: String?, params: Map<String, String>): Int {
        val id = RPC_ID.get()
        val jsonObject = JSONObject()
        try {
            val paramsJson = JSONObject()
            for ((key, value) in params) {
                paramsJson.put(key, value)
            }
            jsonObject.put("jsonrpc", JsonConstants.JSON_RPCVERSION)
            jsonObject.put("method", method)
            jsonObject.put("id", id)
            jsonObject.put("params", paramsJson)
        } catch (e: JSONException) {
            Log.e(TAG, "JSONException raised on sendJson", e)
            return -1
        }
        websocket!!.sendText(jsonObject.toString())
        RPC_ID.incrementAndGet()
        return id
    }

    @Throws(JSONException::class)
    private fun addRemoteParticipantsAlreadyInRoom(result: JSONObject) {
        for (i in 0 until result.getJSONArray(JsonConstants.VALUE).length()) {
            val participantJson = result.getJSONArray(JsonConstants.VALUE).getJSONObject(i)
            val remoteParticipant = newRemoteParticipantAux(participantJson)
            try {
                val streams = participantJson.getJSONArray("streams")
                for (j in 0 until streams.length()) {
                    val stream = streams.getJSONObject(0)
                    val streamId = stream.getString("id")
                    subscribe(remoteParticipant, streamId)
                }
            } catch (e: Exception) {
                //Sometimes when we enter in room the other participants have no stream
                //We catch that in this way the iteration of participants doesn't stop
                Log.e(TAG, "Error in addRemoteParticipantsAlreadyInRoom: " + e.localizedMessage)
            }
        }
    }

    @Throws(JSONException::class)
    private fun iceCandidateEvent(params: JSONObject) {
        val iceCandidate = IceCandidate(
            params.getString("sdpMid"),
            params.getInt("sdpMLineIndex"),
            params.getString("candidate")
        )
        val connectionId = params.getString("senderConnectionId")
        val isRemote = session.localParticipant!!.connectionId != connectionId
        val participant =
            if (isRemote) session.getRemoteParticipant(connectionId) else session.localParticipant
        val pc = participant!!.peerConnection
        when (pc!!.signalingState()) {
            SignalingState.CLOSED -> Log.e(
                "saveIceCandidate error",
                "PeerConnection object is closed"
            )

            SignalingState.STABLE -> if (pc.remoteDescription != null) {
                participant.peerConnection!!.addIceCandidate(iceCandidate)
            } else {
                participant.iceCandidateList.add(iceCandidate)
            }

            else -> participant.iceCandidateList.add(iceCandidate)
        }
    }

    @Throws(JSONException::class)
    private fun participantJoinedEvent(params: JSONObject) {
        newRemoteParticipantAux(params)
    }

    @Throws(JSONException::class)
    private fun participantPublishedEvent(params: JSONObject) {
        val remoteParticipantId = params.getString(JsonConstants.ID)
        val remoteParticipant = session.getRemoteParticipant(remoteParticipantId)
        val streamId = params.getJSONArray("streams").getJSONObject(0).getString("id")
        subscribe(remoteParticipant, streamId)
    }

    @Throws(JSONException::class)
    private fun participantLeftEvent(params: JSONObject) {
        val remoteParticipant = session.removeRemoteParticipant(params.getString("connectionId"))
        remoteParticipant!!.dispose()
        val mainHandler = Handler(activity.mainLooper)
        val myRunnable = Runnable {
            session.removeView(
                remoteParticipant.view
            )
        }
        mainHandler.post(myRunnable)
    }

    @Throws(JSONException::class)
    private fun newRemoteParticipantAux(participantJson: JSONObject): RemoteParticipant {
        val connectionId = participantJson.getString(JsonConstants.ID)
        var participantName: String? = ""
        if (participantJson.getString(JsonConstants.METADATA) != null) {
            val jsonStringified = participantJson.getString(JsonConstants.METADATA)
            try {
                val json = JSONObject(jsonStringified)
                val clientData = json.getString("clientData")
                if (clientData != null) {
                    participantName = clientData
                }
            } catch (e: JSONException) {
                participantName = jsonStringified
            }
        }
        val remoteParticipant = RemoteParticipant(connectionId, participantName, session)
        activity.createRemoteParticipantVideo(remoteParticipant,REMOTE_VIDEO_VIEW, REMOTE_CONTAINER_VIEW)
        session.createRemotePeerConnection(remoteParticipant.connectionId!!)
        return remoteParticipant
    }

    private fun subscribe(remoteParticipant: RemoteParticipant?, streamId: String) {
        if ("kurento" == mediaServer) {
            subscriptionInitiatedFromClient(remoteParticipant, streamId)
        } else {
            prepareReceiveVideoFrom(remoteParticipant, streamId)
        }
    }

    private fun subscriptionInitiatedFromClient(
        remoteParticipant: RemoteParticipant?,
        streamId: String
    ) {
        val sdpConstraints = MediaConstraints()
        sdpConstraints.mandatory.add(MediaConstraints.KeyValuePair("offerToReceiveAudio", "true"))
        sdpConstraints.mandatory.add(MediaConstraints.KeyValuePair("offerToReceiveVideo", "true"))
        remoteParticipant!!.peerConnection!!.createOffer(object :
            CustomSdpObserver("remote offer sdp") {
            override fun onCreateSuccess(sdp: SessionDescription) {
                super.onCreateSuccess(sdp)
                remoteParticipant.peerConnection!!.setLocalDescription(object :
                    CustomSdpObserver("remoteSetLocalDesc") {
                    override fun onSetSuccess() {
                        super.onSetSuccess()
                        receiveVideoFrom(sdp, remoteParticipant, streamId)
                    }
                }, sdp)
            }
        }, sdpConstraints)
    }

    private fun subscriptionInitiatedFromServer(
        remoteParticipant: RemoteParticipant?,
        streamId: String
    ) {
        val sdpConstraints = MediaConstraints()
        sdpConstraints.mandatory.add(MediaConstraints.KeyValuePair("offerToReceiveAudio", "true"))
        sdpConstraints.mandatory.add(MediaConstraints.KeyValuePair("offerToReceiveVideo", "true"))
        session.createAnswerForSubscribing(remoteParticipant!!, streamId, sdpConstraints)
    }

    fun setWebsocketCancelled(websocketCancelled: Boolean) {
        this.websocketCancelled = websocketCancelled
    }

    fun disconnect() {
        websocket!!.disconnect()
    }

    @Throws(Exception::class)
    override fun onStateChanged(websocket: WebSocket, newState: WebSocketState) {
        Log.i(TAG, "State changed: " + newState.name)
    }

    @Throws(Exception::class)
    override fun onConnected(ws: WebSocket, headers: Map<String, List<String>>) {
        Log.i(TAG, "Connected")
        pingMessageHandler()
        joinRoom()
    }

    @Throws(Exception::class)
    override fun onConnectError(websocket: WebSocket, cause: WebSocketException) {
        Log.e(TAG, "Connect error: $cause")
    }

    @Throws(Exception::class)
    override fun onDisconnected(
        websocket: WebSocket,
        serverCloseFrame: WebSocketFrame,
        clientCloseFrame: WebSocketFrame,
        closedByServer: Boolean
    ) {
        Log.e(
            TAG,
            "Disconnected " + serverCloseFrame.closeReason + " " + clientCloseFrame.closeReason + " " + closedByServer
        )
    }

    @Throws(Exception::class)
    override fun onFrame(websocket: WebSocket, frame: WebSocketFrame) {
        Log.i(TAG, "Frame")
    }

    @Throws(Exception::class)
    override fun onContinuationFrame(websocket: WebSocket, frame: WebSocketFrame) {
        Log.i(TAG, "Continuation Frame")
    }

    @Throws(Exception::class)
    override fun onTextFrame(websocket: WebSocket, frame: WebSocketFrame) {
        Log.i(TAG, "Text Frame")
    }

    @Throws(Exception::class)
    override fun onBinaryFrame(websocket: WebSocket, frame: WebSocketFrame) {
        Log.i(TAG, "Binary Frame")
    }

    @Throws(Exception::class)
    override fun onCloseFrame(websocket: WebSocket, frame: WebSocketFrame) {
        Log.i(TAG, "Close Frame")
    }

    @Throws(Exception::class)
    override fun onPingFrame(websocket: WebSocket, frame: WebSocketFrame) {
        Log.i(TAG, "Ping Frame")
    }

    @Throws(Exception::class)
    override fun onPongFrame(websocket: WebSocket, frame: WebSocketFrame) {
        Log.i(TAG, "Pong Frame")
    }

    @Throws(Exception::class)
    override fun onTextMessage(websocket: WebSocket, data: ByteArray) {
    }

    @Throws(Exception::class)
    override fun onBinaryMessage(websocket: WebSocket, binary: ByteArray) {
        Log.i(TAG, "Binary Message")
    }

    @Throws(Exception::class)
    override fun onSendingFrame(websocket: WebSocket, frame: WebSocketFrame) {
        Log.i(TAG, "Sending Frame")
    }

    @Throws(Exception::class)
    override fun onFrameSent(websocket: WebSocket, frame: WebSocketFrame) {
        Log.i(TAG, "Frame sent")
    }

    @Throws(Exception::class)
    override fun onFrameUnsent(websocket: WebSocket, frame: WebSocketFrame) {
        Log.i(TAG, "Frame unsent")
    }

    @Throws(Exception::class)
    override fun onThreadCreated(websocket: WebSocket, threadType: ThreadType, thread: Thread) {
        Log.i(TAG, "Thread created")
    }

    @Throws(Exception::class)
    override fun onThreadStarted(websocket: WebSocket, threadType: ThreadType, thread: Thread) {
        Log.i(TAG, "Thread started")
    }

    @Throws(Exception::class)
    override fun onThreadStopping(websocket: WebSocket, threadType: ThreadType, thread: Thread) {
        Log.i(TAG, "Thread stopping")
    }

    @Throws(Exception::class)
    override fun onError(websocket: WebSocket, cause: WebSocketException) {
        Log.e(TAG, "Error!")
    }

    @Throws(Exception::class)
    override fun onFrameError(
        websocket: WebSocket,
        cause: WebSocketException,
        frame: WebSocketFrame
    ) {
        Log.e(TAG, "Frame error!")
    }

    @Throws(Exception::class)
    override fun onMessageError(
        websocket: WebSocket,
        cause: WebSocketException,
        frames: List<WebSocketFrame>
    ) {
        Log.e(TAG, "Message error! $cause")
    }

    @Throws(Exception::class)
    override fun onMessageDecompressionError(
        websocket: WebSocket, cause: WebSocketException,
        compressed: ByteArray
    ) {
        Log.e(TAG, "Message decompression error!")
    }

    @Throws(Exception::class)
    override fun onTextMessageError(
        websocket: WebSocket,
        cause: WebSocketException,
        data: ByteArray
    ) {
        Log.e(TAG, "Text message error! $cause")
    }

    @Throws(Exception::class)
    override fun onSendError(
        websocket: WebSocket,
        cause: WebSocketException,
        frame: WebSocketFrame
    ) {
        Log.e(TAG, "Send error! $cause")
    }

    @Throws(Exception::class)
    override fun onUnexpectedError(websocket: WebSocket, cause: WebSocketException) {
        Log.e(TAG, "Unexpected error! $cause")
    }

    @Throws(Exception::class)
    override fun handleCallbackError(websocket: WebSocket, cause: Throwable) {
        Log.e(TAG, "Handle callback error! $cause")
    }

    @Throws(Exception::class)
    override fun onSendingHandshake(
        websocket: WebSocket,
        requestLine: String,
        headers: List<Array<String>>
    ) {
        Log.i(TAG, "Sending Handshake! Hello!")
    }

    private fun pingMessageHandler() {
        val initialDelay = 0L
        val executor = ScheduledThreadPoolExecutor(1)
        executor.scheduleWithFixedDelay({
            val pingParams: MutableMap<String, String> =
                HashMap()
            if (ID_PING.get() == -1) {
                // First ping call
                pingParams["interval"] = "5000"
            }
            ID_PING.set(sendJson(JsonConstants.PING_METHOD, pingParams))
        }, initialDelay, PING_MESSAGE_INTERVAL.toLong(), TimeUnit.SECONDS)
    }

    private val webSocketAddress: String
        private get() {
            val wsUri: String
            return try {
                val url = URI(session.token)
                wsUri = if (url.port > -1) {
                    url.scheme + "://" + url.host + ":" + url.port + "/openvidu"
                } else {
                    url.scheme + "://" + url.host + "/openvidu"
                }
                wsUri
            } catch (e: URISyntaxException) {
                Log.e(TAG, "Wrong URL", e)
                e.printStackTrace()
                ""
            }
        }

    override fun doInBackground(vararg p0: AppCompatActivity?): Void? {
        try {
            val factory = WebSocketFactory()

            //Returns a SSLContext object that implements the specified secure socket protocol
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustManagers, SecureRandom())
            factory.sslContext = sslContext

            // Set the flag which indicates whether the hostname in the server's certificate should be verified or not.
            factory.verifyHostname = false

            // Connecting the websocket to OpenVidu URL
            websocket = factory.createSocket(webSocketAddress)
            websocket.addListener(this)
            websocket.connect()
        } catch (e: KeyManagementException) {
            Log.e("WebSocket error", e.message!!)
            val mainHandler = Handler(activity.mainLooper)
            val myRunnable = Runnable {
                val toast = Toast.makeText(activity, e.message, Toast.LENGTH_LONG)
                toast.show()
                activity.finish()
            }
            mainHandler.post(myRunnable)
            websocketCancelled = true
        } catch (e: NoSuchAlgorithmException) {
            Log.e("WebSocket error", e.message!!)
            val mainHandler = Handler(activity.mainLooper)
            val myRunnable = Runnable {
                val toast = Toast.makeText(activity, e.message, Toast.LENGTH_LONG)
                toast.show()
                activity.finish()
            }
            mainHandler.post(myRunnable)
            websocketCancelled = true
        } catch (e: IOException) {
            Log.e("WebSocket error", e.message!!)
            val mainHandler = Handler(activity.mainLooper)
            val myRunnable = Runnable {
                val toast = Toast.makeText(activity, e.message, Toast.LENGTH_LONG)
                toast.show()
                activity.finish()
            }
            mainHandler.post(myRunnable)
            websocketCancelled = true
        } catch (e: WebSocketException) {
            Log.e("WebSocket error", e.message!!)
            val mainHandler = Handler(activity.mainLooper)
            val myRunnable = Runnable {
                val toast = Toast.makeText(activity, e.message, Toast.LENGTH_LONG)
                toast.show()
//                activity.leaveSession()
                activity.finish()
            }
            mainHandler.post(myRunnable)
            websocketCancelled = true
        }
        return null
    }

    override fun onProgressUpdate(vararg values: Void?) {
        Log.i(TAG, "PROGRESS " + Arrays.toString(values))
    }

}