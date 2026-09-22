package app.beyoureyes.monitor.feature.peers

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.PersistableBundle
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.work.WorkManager
import app.beyoureyes.monitor.MonitoringPhase
import app.beyoureyes.monitor.MonitoringRuntimeState
import app.beyoureyes.monitor.R
import app.beyoureyes.monitor.appContainer
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun PeerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = context.appContainer.peerAlerts
    val state by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    val monitoring by MonitoringRuntimeState.status.collectAsState()
    var relay by remember { mutableStateOf("https://ntfy.sh") }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var showCode by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    var storageError by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var pendingPair by remember { mutableStateOf<PeerPairing?>(null) }
    val startReceiver = {
        ContextCompat.startForegroundService(context, Intent(context, PeerReceiveService::class.java))
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startReceiver() else permissionDenied = true
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) scanning = true else permissionDenied = true
    }
    BackHandler(onBack = onBack)
    if (!state.ready) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(24.dp)) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.community_back)) }
                Text(stringResource(R.string.status_loading))
            }
        }
        return
    }
    if (scanning) {
        PeerQrScanner(onCode = {
            scanning = false
            runCatching { PeerPairing.parse(it) }.onSuccess { pendingPair = it }.onFailure { error = true }
        }, onCancel = { scanning = false })
        return
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.community_back)) }
            Text(stringResource(R.string.peer_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.peer_explain))
            Text(stringResource(R.string.peer_limits), style = MaterialTheme.typography.bodySmall)
            if (state.pairing == null) {
                OutlinedTextField(value = relay, onValueChange = { relay = it }, singleLine = true,
                    label = { Text(stringResource(R.string.peer_relay)) }, modifier = Modifier.fillMaxWidth())
                Button(onClick = {
                    runCatching { PeerPairing.create(relay) }.onSuccess { pendingPair = it }.onFailure { error = true }
                }, modifier = Modifier.testTag("peer_create")) { Text(stringResource(R.string.peer_create)) }
                Text(stringResource(R.string.peer_join_help))
                Button(onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanning = true
                    else cameraPermission.launch(Manifest.permission.CAMERA)
                }, enabled = monitoring.phase == MonitoringPhase.STOPPED) { Text(stringResource(R.string.peer_scan)) }
                if (monitoring.phase != MonitoringPhase.STOPPED) Text(stringResource(R.string.peer_stop_camera))
                OutlinedTextField(value = code, onValueChange = { code = it.take(2048) },
                    label = { Text(stringResource(R.string.peer_code)) }, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("peer_code"))
                Button(onClick = {
                    runCatching { PeerPairing.parse(code.trim()) }.onSuccess { pendingPair = it }.onFailure { error = true }
                }, enabled = code.isNotBlank(), modifier = Modifier.testTag("peer_join")) { Text(stringResource(R.string.peer_join)) }
            } else {
                val pair = checkNotNull(state.pairing)
                Text(stringResource(R.string.peer_send_help))
                Text(pair.relay, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.peer_paired), modifier = Modifier.testTag("peer_paired"))
                TextButton(onClick = { showCode = true }) { Text(stringResource(R.string.peer_show_code)) }
                Text(stringResource(when (state.connection) {
                    PeerConnection.OFF -> R.string.peer_off
                    PeerConnection.CONNECTING -> R.string.peer_connecting
                    PeerConnection.LISTENING -> R.string.peer_receiving
                    PeerConnection.RETRYING -> R.string.peer_retrying
                }), modifier = Modifier.testTag("peer_connection"))
                Button(onClick = {
                    if (state.connection != PeerConnection.OFF) context.stopService(Intent(context, PeerReceiveService::class.java))
                    else if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else startReceiver()
                }, modifier = Modifier.testTag("peer_receive")) {
                    Text(stringResource(if (state.connection == PeerConnection.OFF) R.string.peer_start else R.string.peer_stop))
                }
                Text(stringResource(R.string.peer_battery), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }) {
                    Text(stringResource(R.string.peer_battery_settings))
                }
                Button(onClick = { scope.launch(Dispatchers.IO) { PeerAlertSender.test(context) } }, modifier = Modifier.testTag("peer_test")) { Text(stringResource(R.string.peer_test)) }
                when (state.send) {
                    PeerSendState.NONE -> Unit
                    PeerSendState.QUEUED -> Text(stringResource(R.string.peer_queued))
                    PeerSendState.SENT -> Text(stringResource(R.string.peer_sent))
                    PeerSendState.FAILED -> Text(stringResource(R.string.peer_failed))
                }
                TextButton(onClick = {
                    context.stopService(Intent(context, PeerReceiveService::class.java))
                    WorkManager.getInstance(context).cancelAllWorkByTag("paired-alerts")
                    saving = true
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { store.pair(null) } }
                            .onSuccess { showCode = false; storageError = false }
                            .onFailure { storageError = true }
                        saving = false
                    }
                }, enabled = !saving) { Text(stringResource(R.string.peer_unpair)) }
            }
            if (error) Text(stringResource(R.string.peer_invalid), color = MaterialTheme.colorScheme.error)
            if (permissionDenied) Text(stringResource(R.string.peer_permission), color = MaterialTheme.colorScheme.error)
            if (storageError) Text(stringResource(R.string.error_notification_save_failed), color = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.peer_inbox), style = MaterialTheme.typography.titleMedium)
            if (state.inbox.isEmpty()) Text(stringResource(R.string.peer_empty))
            val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(ContextCompat.getContextForLanguage(context).resources.configuration.locales[0]).withZone(ZoneId.systemDefault())
            state.inbox.forEach { alert ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("peer_received")) {
                    Text(alert.text(ContextCompat.getContextForLanguage(context)))
                    Text(formatter.format(Instant.ofEpochMilli(alert.at)), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    pendingPair?.let { pair ->
        AlertDialog(onDismissRequest = { if (!saving) pendingPair = null }, title = { Text(stringResource(R.string.peer_confirm)) },
            text = { Column { Text(pair.relay); Text(stringResource(R.string.peer_confirm_detail)) } },
            confirmButton = { TextButton(onClick = {
                saving = true
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { store.pair(pair) } }.onSuccess {
                        pendingPair = null; code = ""; error = false; storageError = false; showCode = true
                    }.onFailure { pendingPair = null; storageError = true }
                    saving = false
                }
            }, enabled = !saving, modifier = Modifier.testTag("peer_confirm")) { Text(stringResource(R.string.peer_join)) } },
            dismissButton = { TextButton(onClick = { pendingPair = null }, enabled = !saving) { Text(stringResource(R.string.peer_cancel)) } })
    }
    if (showCode && state.pairing != null) {
        val pair = checkNotNull(state.pairing)
        val bitmap = remember(pair) { pairingBitmap(pair.code()) }
        AlertDialog(onDismissRequest = { showCode = false }, title = { Text(stringResource(R.string.peer_show_code)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Image(bitmap.asImageBitmap(), stringResource(R.string.peer_qr_private), Modifier.size(260.dp))
                Text(stringResource(R.string.peer_qr_private))
            } }, confirmButton = { TextButton(onClick = { showCode = false }) { Text(stringResource(R.string.peer_done)) } },
            dismissButton = { TextButton(onClick = {
                val clip = ClipData.newPlainText(context.getString(R.string.peer_code), pair.code())
                if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
            }) { Text(stringResource(R.string.peer_copy)) } })
    }
}

internal fun pairingBitmap(code: String): Bitmap {
    val matrix = QRCodeWriter().encode(code, BarcodeFormat.QR_CODE, 512, 512)
    return Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply {
        val pixels = IntArray(512 * 512) { i -> if (matrix[i % 512, i / 512]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
        setPixels(pixels, 0, 512, 0, 0, 512, 512)
    }
}

@Composable
private fun PeerQrScanner(onCode: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val controller = remember { LifecycleCameraController(context).apply {
        cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
    } }
    DisposableEffect(controller, lifecycle) {
        var found = false
        controller.setImageAnalysisAnalyzer(executor) { image ->
            try {
                if (!found) {
                    val plane = image.planes[0]; val buffer = plane.buffer
                    val bytes = ByteArray(image.width * image.height)
                    for (row in 0 until image.height) for (column in 0 until image.width) {
                        bytes[row * image.width + column] = buffer.get(row * plane.rowStride + column * plane.pixelStride)
                    }
                    val source = PlanarYUVLuminanceSource(bytes, image.width, image.height, 0, 0, image.width, image.height, false)
                    val text = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
                    if (text.startsWith("beyoureye-pair:1:")) {
                        found = true
                        ContextCompat.getMainExecutor(context).execute { onCode(text) }
                    }
                }
            } catch (_: Exception) { /* No QR in this frame. */ }
            finally { image.close() }
        }
        controller.bindToLifecycle(lifecycle)
        onDispose { controller.clearImageAnalysisAnalyzer(); controller.unbind(); executor.shutdown() }
    }
    BackHandler(onBack = onCancel)
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text(stringResource(R.string.peer_scan_help))
            AndroidView(factory = { PreviewView(it).apply { this.controller = controller } }, modifier = Modifier.fillMaxWidth().height(420.dp))
            TextButton(onClick = onCancel) { Text(stringResource(R.string.peer_cancel)) }
        }
    }
}
