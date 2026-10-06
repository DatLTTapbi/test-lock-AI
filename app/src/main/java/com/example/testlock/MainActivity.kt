package com.example.testlock

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.testlock.service.LockScreenService
import com.example.testlock.ui.theme.TestlockTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.coroutines.resume
import kotlin.math.sqrt

object VoiceLockState {
    var referenceFrames: List<FloatArray>? = null
    var targetKeyword: String? = null
}

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Handle audio permission result
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            TestlockTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    LockScreenTestScreen(
                        modifier = Modifier.padding(innerPadding),
                        onCheckPermission = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(
                                    this
                                )
                            ) {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName")
                                )
                                startActivity(intent)
                            }
                        },
                        onStartService = {
                            val intent = Intent(this, LockScreenService::class.java)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                startForegroundService(intent)
                            } else {
                                startService(intent)
                            }
                        },
                        onStopService = {
                            val intent = Intent(this, LockScreenService::class.java)
                            stopService(intent)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun LockScreenTestScreen(
    modifier: Modifier = Modifier,
    onCheckPermission: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    var showVoiceSetup by remember { mutableStateOf(false) }
    var showVoiceVerify by remember { mutableStateOf(false) }
    var showKeywordSetup by remember { mutableStateOf(false) }
    var showKeywordVerify by remember { mutableStateOf(false) }

    when {
        showVoiceSetup -> {
            VoiceLockSetupScreen(onDismiss = { showVoiceSetup = false })
        }

        showVoiceVerify -> {
            VoiceVerificationScreen(onDismiss = { showVoiceVerify = false })
        }

        showKeywordSetup -> {
            SpeechKeywordSetupScreen(onDismiss = { showKeywordSetup = false })
        }

        showKeywordVerify -> {
            SpeechKeywordVerifyScreen(onDismiss = { showKeywordVerify = false })
        }

        else -> {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "AI Voice Lock Dashboard",
                    fontSize = 24.sp,
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                Button(
                    onClick = { onCheckPermission() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Text("1. Cấp Quyền Overlay")
                }

                Button(
                    onClick = { onStartService() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("2. Bắt đầu Foreground Service")
                }

                Button(
                    onClick = { onStopService() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("3. Dừng Service")
                }

                Button(
                    onClick = { showVoiceSetup = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("4. Thiết lập Khóa Giọng Nói (Vector DTW)")
                }

                Button(
                    onClick = { showVoiceVerify = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                ) {
                    Text("5. Xác thực Giọng Nói (Vector DTW)")
                }

                Button(
                    onClick = { showKeywordSetup = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Text("6. Đặt Mật Khẩu Bằng Giọng Nói (Keyword Setup)")
                }

                Button(
                    onClick = { showKeywordVerify = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                ) {
                    Text("7. Xác Thực Từ Khóa (SpeechRecognizer)")
                }
            }
        }
    }
}

@Composable
fun SpeechKeywordSetupScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var setupState by remember { mutableStateOf("Idle") }
    var instructionText by remember { mutableStateOf("Nhấn nút và nói từ khóa mật khẩu của bạn (ví dụ: 'mở khóa', 'hello')") }
    var registeredKeyword by remember { mutableStateOf(VoiceLockState.targetKeyword ?: "") }
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Đặt Mật Khẩu Bằng Giọng Nói",
            fontSize = 22.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Text(
            text = instructionText,
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (registeredKeyword.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Text(
                    text = "Mật khẩu hiện tại: \"$registeredKeyword\"",
                    fontSize = 16.sp,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Button(
            onClick = {
                if (setupState == "Idle") {
                    setupState = "Listening"
                    instructionText = "Đang lắng nghe ..."

                    coroutineScope.launch(Dispatchers.IO) {
                        try {
                            val speechResult = recognizeSpeech(context)
                            withContext(Dispatchers.Main) {
                                setupState = "Idle"
                                if (speechResult.startsWith("Lỗi") || speechResult.contains("Không nhận diện")) {
                                    instructionText =
                                        "Không nhận diện được: $speechResult. Thử lại!"
                                } else {
                                    VoiceLockState.targetKeyword = speechResult
                                    registeredKeyword = speechResult
                                    instructionText = "Thành công! Đã ghi nhớ mật khẩu."
                                }
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                setupState = "Idle"
                                instructionText = "Lỗi: ${e.localizedMessage}"
                            }
                        }
                    }
                }
            },
            enabled = setupState == "Idle",
            modifier = Modifier.size(140.dp, 56.dp)
        ) {
            Text(text = if (setupState == "Listening") "Đang nghe..." else "Nói Mật Khẩu")
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = { onDismiss() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Text("Xong / Quay lại")
        }
    }
}

@Composable
fun SpeechKeywordVerifyScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var verifyState by remember { mutableStateOf("Idle") }
    var instructionText by remember { mutableStateOf("Nhấn nút và nói từ khóa để xác thực") }
    var recognizedText by remember { mutableStateOf("") }
    var matchResultText by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    val targetKw = VoiceLockState.targetKeyword

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Xác Thực Từ Khóa (SpeechRecognizer)",
            fontSize = 22.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (targetKw.isNullOrEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Text(
                    text = "Chưa đặt mật khẩu từ khóa! Vui lòng hoàn thành bước 6 trước.",
                    fontSize = 16.sp,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        } else {
            Text(
                text = "Mật khẩu đã đặt: \"$targetKw\"",
                fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 12.dp),
                color = MaterialTheme.colorScheme.primary
            )
        }

        Text(
            text = instructionText,
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (recognizedText.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Bạn đã nói: \"$recognizedText\"",
                        fontSize = 16.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        text = matchResultText,
                        fontSize = 16.sp,
                        color = if (matchResultText.contains("THÀNH CÔNG")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Button(
            onClick = {
                if (targetKw.isNullOrEmpty()) {
                    instructionText = "Vui lòng đặt mật khẩu ở bước 6 trước!"
                    return@Button
                }

                if (verifyState == "Idle") {
                    verifyState = "Listening"
                    instructionText = "Đang lắng nghe xác thực..."
                    recognizedText = ""
                    matchResultText = ""

                    coroutineScope.launch(Dispatchers.IO) {
                        try {
                            val speechResult = recognizeSpeech(context)
                            val cleanedRecognized = speechResult.trim().lowercase()
                            val cleanedTarget = targetKw.trim().lowercase()
                            val isMatch = cleanedRecognized.contains(cleanedTarget)

                            withContext(Dispatchers.Main) {
                                verifyState = "Idle"
                                recognizedText = speechResult
                                matchResultText = if (isMatch) {
                                    "✅ XÁC THỰC THÀNH CÔNG!\nKhớp với từ khóa \"$targetKw\""
                                } else {
                                    "❌ XÁC THỰC THẤT BẠI!\nKhông khớp (nhận diện được \"$speechResult\")"
                                }
                                instructionText = "Hoàn tất xác thực!"
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                verifyState = "Idle"
                                instructionText = "Lỗi: ${e.localizedMessage}"
                            }
                        }
                    }
                }
            },
            enabled = verifyState == "Idle" && !targetKw.isNullOrEmpty(),
            modifier = Modifier.size(140.dp, 56.dp)
        ) {
            Text(text = if (verifyState == "Listening") "Đang nghe..." else "Xác Thực Ngay")
        }

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedButton(
            onClick = { onDismiss() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Text("Quay lại")
        }
    }
}

@Composable
fun VoiceLockSetupScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var recordingState by remember { mutableStateOf("Idle") }
    var progressText by remember { mutableStateOf("Nhấn nút Record để ghi âm khẩu lệnh đăng ký (tối đa 4s)") }
    var recognizedText by remember { mutableStateOf("") }
    var isSuccess by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Voice Lock Setup (Step 1)",
            fontSize = 22.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Text(
            text = progressText,
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (recognizedText.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Text(
                    text = "Khẩu lệnh: \"$recognizedText\"",
                    fontSize = 16.sp,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Button(
            onClick = {
                if (recordingState == "Idle") {
                    recordingState = "Recording"
                    recognizedText = ""
                    progressText = "Đang ghi âm (AudioRecord 16kHz)..."

                    coroutineScope.launch(Dispatchers.IO) {
                        try {
                            val sampleRate = 16000
                            val channelConfig = AudioFormat.CHANNEL_IN_MONO
                            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
                            val minBufferSize =
                                AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

                            if (ActivityCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                val recorder = AudioRecord(
                                    MediaRecorder.AudioSource.MIC,
                                    sampleRate,
                                    channelConfig,
                                    audioFormat,
                                    maxOf(minBufferSize, 3200)
                                )

                                Log.d("AudioRecordDebug", "Requested sampleRate: $sampleRate, Actual sampleRate: ${recorder.sampleRate}, state: ${recorder.state}, minBufferSize: $minBufferSize")

                                val audioData = mutableListOf<Float>()
                                val buffer = ByteArray(512 * 2)
                                recorder.startRecording()

                                val startTime = System.currentTimeMillis()
                                while (System.currentTimeMillis() - startTime < 4000) {
                                    val readSize = recorder.read(buffer, 0, buffer.size)
                                    if (readSize > 0) {
                                        for (i in 0 until readSize step 2) {
                                            val s =
                                                ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
                                            audioData.add(s / 32768.0f)
                                        }
                                    }
                                }
                                recorder.stop()
                                recorder.release()

                                Log.d("SileroVADSetup", "AudioRecord finished: total samples = ${audioData.size}, duration = ${audioData.size * 1000L / 16000} ms")
                                if (audioData.isNotEmpty()) {
                                    val overallRms = sqrt(audioData.map { it * it }.average())
                                    Log.d("SileroVADSetup", "Overall audio RMS = $overallRms, min = ${audioData.minOrNull()}, max = ${audioData.maxOrNull()}")
                                }

                                withContext(Dispatchers.Main) {
                                    recordingState = "Processing"
                                    progressText = "Đang chạy Silero VAD kiểm tra tiếng người..."
                                }
//OrtEnvironment là môi trường/runtime context của ONNX Runtime. -> tạo môi trường + tạo session để chạy model, có thể ví OrtEnvironment là context trong Android
                                val ortEnvironment = OrtEnvironment.getEnvironment()
                                //đọc toàn bộ binary file vào RAM.
                                val vadBytes = context.assets.open("model/silero_vad_16k_op15.onnx")
                                    .readBytes()
                                //OrtSession là một phiên làm việc để chạy model Silero VAD cụ thể.
                                val vadSession = ortEnvironment.createSession(vadBytes)
                                val hasHumanSpeech =
                                    checkSpeechWithSileroVAD(vadSession, ortEnvironment, audioData)
                                vadSession.close()

                                if (!hasHumanSpeech) {
                                    withContext(Dispatchers.Main) {
                                        recordingState = "Idle"
                                        progressText =
                                            "Không phát hiện thấy giọng nói! Vui lòng thử lại."
                                    }
                                    return@launch
                                }

                                withContext(Dispatchers.Main) {
                                    progressText = "Phát hiện giọng nói! Đang nhận diện từ..."
                                }

                                val embBytes =
                                    context.assets.open("model/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx").readBytes()
                                val embSession = ortEnvironment.createSession(embBytes)
                                val embeddingFrames =
                                    extractEmbeddingFrames(embSession, ortEnvironment, audioData)
                                embSession.close()

                                VoiceLockState.referenceFrames = embeddingFrames
                                Log.d("faewfawe", "${VoiceLockState.referenceFrames}")
                                withContext(Dispatchers.Main) {
                                    recordingState = "Success"
                                    isSuccess = true
                                    progressText =
                                        "Thành công! Đã lưu mẫu chuẩn (Reference Vector)."
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    recordingState = "Idle"
                                    progressText = "Chưa cấp quyền RECORD_AUDIO!"
                                }
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                recordingState = "Idle"
                                progressText = "Lỗi: ${e.localizedMessage}"
                            }
                        }
                    }
                }
            },
            enabled = recordingState == "Idle",
            modifier = Modifier.size(120.dp),
            shape = MaterialTheme.shapes.extraLarge,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (recordingState == "Recording") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(
                text = if (recordingState == "Recording") "Recording" else "Record",
                fontSize = 16.sp
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = { onDismiss() },
            enabled = isSuccess,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Text("Continue")
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = { onDismiss() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Text("Back")
        }
    }
}

@Composable
fun VoiceVerificationScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var verifyState by remember { mutableStateOf("Idle") }
    var verifyText by remember { mutableStateOf("Nhấn nút Verify để ghi âm xác thực giọng nói") }
    var matchResult by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Voice Lock Verification (DTW)",
            fontSize = 22.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Text(
            text = verifyText,
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (matchResult.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Text(
                    text = matchResult,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Button(
            onClick = {
                if (VoiceLockState.referenceFrames == null) {
                    verifyText = "Chưa có mẫu giọng nói đăng ký! Hãy chạy bước 4 (Setup) trước."
                    return@Button
                }

                if (verifyState == "Idle") {
                    verifyState = "Recording"
                    verifyText = "Đang ghi âm xác thực (16kHz)..."
                    matchResult = ""

                    coroutineScope.launch(Dispatchers.IO) {
                        try {
                            val sampleRate = 16000
                            val channelConfig = AudioFormat.CHANNEL_IN_MONO
                            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
                            val minBufferSize =
                                AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

                            if (ActivityCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                val recorder = AudioRecord(
                                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                                    sampleRate,
                                    channelConfig,
                                    audioFormat,
                                    maxOf(minBufferSize, 3200)
                                )

                                val audioData = mutableListOf<Float>()
                                val buffer = ByteArray(512 * 2)
                                recorder.startRecording()

                                val startTime = System.currentTimeMillis()
                                while (System.currentTimeMillis() - startTime < 4000) {
                                    val readSize = recorder.read(buffer, 0, buffer.size)
                                    if (readSize > 0) {
                                        for (i in 0 until readSize step 2) {
                                            val s =
                                                ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
                                            audioData.add(s / 32768.0f)
                                        }
                                    }
                                }
                                recorder.stop()
                                recorder.release()

                                withContext(Dispatchers.Main) {
                                    verifyState = "Processing"
                                    verifyText = "Đang trích xuất đặc trưng & so khớp DTW..."
                                }

                                val ortEnvironment = OrtEnvironment.getEnvironment()
                                val embBytes =
                                    context.assets.open("model/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx").readBytes()
                                val embSession = ortEnvironment.createSession(embBytes)

                                val queryFrames =
                                    extractEmbeddingFrames(embSession, ortEnvironment, audioData)
                                embSession.close()

                                val refFrames = VoiceLockState.referenceFrames!!
                                val dtwDistance = computeDTWDistance(queryFrames, refFrames)

                                val isMatch = dtwDistance < 0.45f

                                withContext(Dispatchers.Main) {
                                    verifyState = "Idle"
                                    verifyText = "Hoàn tất xác thực!"
                                    matchResult = if (isMatch) {
                                        "✅ XÁC THỰC THÀNH CÔNG!\nKhoảng cách DTW: %.4f (Khớp giọng)".format(
                                            dtwDistance
                                        )
                                    } else {
                                        "❌ XÁC THỰC THẤT BẠI!\nKhoảng cách DTW: %.4f (Không khớp)".format(
                                            dtwDistance
                                        )
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                verifyState = "Idle"
                                verifyText = "Lỗi: ${e.localizedMessage}"
                            }
                        }
                    }
                }
            },
            enabled = verifyState == "Idle",
            modifier = Modifier.size(120.dp),
            shape = MaterialTheme.shapes.extraLarge,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (verifyState == "Recording") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
            )
        ) {
            Text(text = if (verifyState == "Recording") "Verify" else "Verify", fontSize = 16.sp)
        }

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedButton(
            onClick = { onDismiss() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Text("Back")
        }
    }
}

// 1, 2, 3. DTW and Cosine Distance implementation requested by user
// Tính khoảng cách cô-sin (Cosine Distance) giữa 2 vector đặc trưng 96 chiều.
// Công thức: 1 - Cosine Similarity. Khoảng cách càng nhỏ (gần 0) nghĩa là 2 vector đặc trưng càng giống nhau.
fun cosineDistance(bVar: FloatArray, bVar2: FloatArray): Float {
    var f12 = 0.0f
    var norm1 = 0.0f
    var norm2 = 0.0f
    val len = minOf(bVar.size, bVar2.size)
    for (i12 in 0 until len) {
        f12 += bVar[i12] * bVar2[i12]
        norm1 += bVar[i12] * bVar[i12]
        norm2 += bVar2[i12] * bVar2[i12]
    }
    val f10 = sqrt(norm1)
    val f11 = sqrt(norm2)
    if (f10 == 0.0f || f11 == 0.0f) return 1.0f
    val cosineSimilarity = f12 / (f10 * f11)
    return 1.0f - cosineSimilarity
}

// Thuật toán Dynamic Time Warping (DTW) dùng để so khớp 2 chuỗi frame đặc trưng giọng nói (query và reference)
// có độ dài thời gian (số lượng frame) khác nhau nhưng phát âm cùng một nội dung.
// DTW tìm ra đường đi tối ưu (độ lệch ít nhất) giữa 2 chuỗi và trả về khoảng cách tổng thể (distance).
fun computeDTWDistance(queryFrames: List<FloatArray>, refFrames: List<FloatArray>): Float {
    val n = queryFrames.size
    val m = refFrames.size
    if (n == 0 || m == 0) return Float.MAX_VALUE

    // Khởi tạo ma trận chi phí DTW kích thước (n+1) x (m+1), ban đầu gán giá trị vô cực (Float.MAX_VALUE)
    val dtw = Array(n + 1) { FloatArray(m + 1) { Float.MAX_VALUE } }
    dtw[0][0] = 0.0f

    // Tính toán chi phí tích lũy qua từng bước thời gian (i, j)
    for (i in 1..n) {
        for (j in 1..m) {
            // Chi phí tại bước (i, j) là khoảng cách cô-sin giữa frame thứ i của query và frame thứ j của ref
            val cost = cosineDistance(queryFrames[i - 1], refFrames[j - 1])
            // Cộng thêm chi phí tối thiểu từ 3 hướng trước đó (đường chéo, hàng trên, cột trái)
            dtw[i][j] = cost + minOf(
                dtw[i - 1][j],
                dtw[i][j - 1],
                dtw[i - 1][j - 1]
            )
        }
    }
    // Chuẩn hóa tổng khoảng cách DTW bằng tổng số lượng frame (n + m) để ra điểm trung bình
    return dtw[n][m] / (n + m)
}

suspend fun recognizeSpeech(context: Context): String =
    suspendCancellableCoroutine { continuation ->
        val mainHandler = Handler(context.mainLooper)
        mainHandler.post {
            try {
                Log.d("SpeechRecognizerDebug", "Creating SpeechRecognizer...")
                val speechRecognizer =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(
                            context
                        )
                    ) {
                        Log.d("SpeechRecognizerDebug", "Using on-device SpeechRecognizer")
                        SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    } else {
                        Log.d("SpeechRecognizerDebug", "Using standard SpeechRecognizer")
                        SpeechRecognizer.createSpeechRecognizer(context)
                    }

                var isFinished = false

                // Thiết lập timeout tối đa 5 giây cho SpeechRecognizer
                val timeoutRunnable = Runnable {
                    if (!isFinished) {
                        isFinished = true
                        Log.w("SpeechRecognizerDebug", "Timeout triggered (5s reached)! Cancelling SpeechRecognizer...")
                        try {
                            speechRecognizer.cancel()
                        } catch (e: Exception) {
                            Log.e("SpeechRecognizerDebug", "Error cancelling on timeout", e)
                        }
                        speechRecognizer.destroy()
                        if (continuation.isActive) {
                            continuation.resume("Hết thời gian (Quá 5 giây không có kết quả)")
                        }
                    }
                }
                mainHandler.postDelayed(timeoutRunnable, 10000L)

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    // 1. EXTRA_LANGUAGE_MODEL: Mô hình nhận diện tự do (free_form)
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    // 2. EXTRA_LANGUAGE: Ngôn ngữ chính nhận diện
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                    // 3. EXTRA_LANGUAGE_PREFERENCE: Ngôn ngữ ưu tiên
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US")
                    // 4. EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE: Chỉ định trả về theo ngôn ngữ ưu tiên
                    putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true)
                    // 5. EXTRA_MAX_RESULTS: Giới hạn số kết quả trả về tối đa
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                }

                speechRecognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d("SpeechRecognizerDebug", "onReadyForSpeech: Sẵn sàng nhận giọng nói")
                    }
                    override fun onBeginningOfSpeech() {
                        Log.d("SpeechRecognizerDebug", "onBeginningOfSpeech: Người dùng đã bắt đầu nói")
                    }
                    override fun onRmsChanged(rmsdB: Float) {
                        // Log.v("SpeechRecognizerDebug", "onRmsChanged: rmsdB = $rmsdB")
                    }
                    override fun onBufferReceived(buffer: ByteArray?) {
                        Log.d("SpeechRecognizerDebug", "onBufferReceived: Nhận audio buffer")
                    }
                    override fun onEndOfSpeech() {
                        Log.d("SpeechRecognizerDebug", "onEndOfSpeech: Người dùng đã dừng nói")
                    }
                    override fun onError(error: Int) {
                        if (!isFinished) {
                            isFinished = true
                            mainHandler.removeCallbacks(timeoutRunnable)
                            speechRecognizer.destroy()
                            val errorMsg = when (error) {
                                SpeechRecognizer.ERROR_NETWORK -> "Lỗi mạng (Network error)"
                                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Timeout mạng"
                                SpeechRecognizer.ERROR_AUDIO -> "Lỗi audio (Audio recording error)"
                                SpeechRecognizer.ERROR_SERVER -> "Lỗi server"
                                SpeechRecognizer.ERROR_CLIENT -> "Lỗi client"
                                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Hết thời gian (Không phát hiện giọng nói)"
                                SpeechRecognizer.ERROR_NO_MATCH -> "Không nhận diện được từ (No match)"
                                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer đang bận"
                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Không đủ quyền"
                                else -> "Lỗi nhận diện không xác định"
                            }
                            Log.e("SpeechRecognizerDebug", "onError: code = $error -> $errorMsg")
                            if (continuation.isActive) {
                                continuation.resume("$errorMsg (code: $error)")
                            }
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        if (!isFinished) {
                            isFinished = true
                            mainHandler.removeCallbacks(timeoutRunnable)
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            Log.d("SpeechRecognizerDebug", "onResults: matches = $matches")
                            speechRecognizer.destroy()
                            if (continuation.isActive) {
                                if (!matches.isNullOrEmpty()) {
                                    continuation.resume(matches[0])
                                } else {
                                    continuation.resume("Không nhận diện được từ")
                                }
                            }
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        Log.d("SpeechRecognizerDebug", "onPartialResults: $partial")
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {
                        Log.d("SpeechRecognizerDebug", "onEvent: eventType = $eventType")
                    }
                })

                Log.d("SpeechRecognizerDebug", "Calling startListening...")
                speechRecognizer.startListening(intent)
            } catch (e: Exception) {
                Log.e("SpeechRecognizerDebug", "Exception in recognizeSpeech", e)
                if (continuation.isActive) {
                    continuation.resume("Lỗi SpeechRecognizer: ${e.localizedMessage}")
                }
            }
        }
    }

// Hàm kiểm tra xem đoạn ghi âm có chứa tiếng nói con người (Human Speech) hay không bằng model Silero VAD (ONNX).
// Model nhận vào các đoạn audio nhỏ (chunks 512 mẫu), tần số mẫu (sr = 16000Hz), và state để trả về xác suất (probability) có tiếng nói.
fun checkSpeechWithSileroVAD(
    vadSession: OrtSession,
    env: OrtEnvironment,
    audioFloatList: List<Float>
): Boolean {
    if (audioFloatList.size < 512) return false

    // 1. Khởi tạo srTensor với shape [1]
    val srBuffer = LongBuffer.wrap(longArrayOf(16000L))
    val srTensor = OnnxTensor.createTensor(env, srBuffer, longArrayOf(1))

    // 2. Khởi tạo state ẩn (2 x 1 x 128) toàn số 0
    val stateArray = Array(2) { Array(1) { FloatArray(128) } }

    // 3. Khởi tạo buffer lưu 64 mẫu lịch sử
    val historyBuffer = FloatArray(64)

    val chunkSize = 512
    val totalChunks = audioFloatList.size / chunkSize
    var speechChunkCount = 0

    for (i in 0 until totalChunks) {
        val chunk512 = FloatArray(chunkSize)
        for (j in 0 until chunkSize) {
            chunk512[j] = audioFloatList[i * chunkSize + j]
        }

        // Tạo mảng 576 mẫu: 64 mẫu cũ + 512 mẫu mới
        val input576 = FloatArray(576)
        System.arraycopy(historyBuffer, 0, input576, 0, 64)
        System.arraycopy(chunk512, 0, input576, 64, 512)

        // Lưu 64 mẫu cuối của chunk này cho lần lặp sau
        System.arraycopy(chunk512, 512 - 64, historyBuffer, 0, 64)

        // Tạo input tensor shape [1, 576]
        val inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input576), longArrayOf(1, 576))

        // Chuẩn bị state tensor (2 x 1 x 128)
        val flatStateInput = FloatArray(256)
        var sIdx = 0
        for (d0 in 0 until 2) {
            for (d1 in 0 until 1) {
                for (d2 in 0 until 128) {
                    flatStateInput[sIdx++] = stateArray[d0][d1][d2]
                }
            }
        }
        val stateTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(flatStateInput), longArrayOf(2, 1, 128))

        val inputs = mapOf(
            "input" to inputTensor,
            "sr" to srTensor,
            "state" to stateTensor
        )

        try {
            val results = vadSession.run(inputs)
            val outputTensor = results[0] as OnnxTensor
            val prob = outputTensor.floatBuffer.get(0)

            // Cập nhật stateN trả về từ mô hình
            val outStateTensor = results[1] as OnnxTensor
            val outStateBuffer = outStateTensor.floatBuffer
            val flatState = FloatArray(256)
            if (outStateBuffer.remaining() >= 256) {
                outStateBuffer.get(flatState)
                var idx = 0
                for (d0 in 0 until 2) {
                    for (d1 in 0 until 1) {
                        for (d2 in 0 until 128) {
                            stateArray[d0][d1][d2] = flatState[idx++]
                        }
                    }
                }
            }

            if (prob > 0.5f) {
                Log.d("SileroVAD", "chunk $i, prob= $prob")
                speechChunkCount++
            }
            else {
                Log.d("SileroVAD", "chunk $i, prob =$prob")
            }
            results.close()
        } catch (e: Exception) {
            Log.e("SileroVAD", "Error at chunk $i", e)
        } finally {
            inputTensor.close()
            stateTensor.close()
        }
    }

    srTensor.close()

    // Ngưỡng quyết định có giọng nói
    val threshold = maxOf(2, totalChunks / 20)
    return speechChunkCount >= threshold
}

// Hàm trích xuất đặc trưng giọng nói (Speech Embedding) sử dụng model ONNX (`speech-embedding.onnx`).
// Model nhận trực tiếp mảng audio PCM và trả về chuỗi các frame đặc trưng (mỗi frame gồm 96 chiều).
fun extractEmbeddingFrames(
    embSession: OrtSession,
    env: OrtEnvironment,
    audioFloatList: List<Float>
): List<FloatArray> {
    val inputArray = arrayOf(audioFloatList.toFloatArray())
    val inputTensor = OnnxTensor.createTensor(env, inputArray)

    val inputName = embSession.inputNames.iterator().next()
    val inputs = mapOf(inputName to inputTensor)

    try {
        val results = embSession.run(inputs)
        val outputTensor = results[0] as OnnxTensor
        val outputBuffer = outputTensor.floatBuffer
        val totalElements = outputBuffer.remaining()
        val data = FloatArray(totalElements)
        outputBuffer.get(data)

        results.close()
        inputTensor.close()

        val frames = mutableListOf<FloatArray>()
        val dim = 96 // Số chiều (dimension) đặc trưng của speech-embedding.onnx là 96
        val numFrames = totalElements / dim

        for (f in 0 until maxOf(1, numFrames)) {
            val frame = FloatArray(dim)
            val start = f * dim
            val end = minOf(start + dim, totalElements)
            for (k in 0 until (end - start)) {
                frame[k] = data[start + k]
            }
            frames.add(frame)
        }
        return frames
    } catch (e: Exception) {
        inputTensor.close()
        throw e
    }
}
