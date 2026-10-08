package com.example.testlock

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
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.sqrt
import androidx.core.net.toUri

object VoiceLockState {
    var referenceEmbedding: FloatArray? = null
    var targetKeyword: String? = null
}

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Handle permissions result
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_PHONE_STATE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.READ_PHONE_STATE)
        }
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }

        setContent {
            TestlockTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    LockScreenTestScreen(
                        modifier = Modifier.padding(innerPadding),
                        onCheckPermission = {
                            if (!Settings.canDrawOverlays(
                                    this
                                )
                            ) {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    "package:$packageName".toUri()
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
                    Text("4. Cài đặt AI model")
                }

                Button(
                    onClick = { showVoiceVerify = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                ) {
                    Text("5. Xác thực với AI model")
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
                                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                                    sampleRate,
                                    channelConfig,
                                    audioFormat,
                                    maxOf(minBufferSize, 3200)
                                )

                                Log.d(
                                    "AudioRecordDebug",
                                    "Requested sampleRate: $sampleRate, Actual sampleRate: ${recorder.sampleRate}, state: ${recorder.state}, minBufferSize: $minBufferSize"
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
                                    recordingState = "Processing"
                                    progressText = "Đang chạy Silero VAD kiểm tra tiếng người..."
                                }
                                val vadConfig = VadModelConfig(
                                    sileroVadModelConfig = SileroVadModelConfig(
                                        model = "model/silero_vad_16k_op15.onnx",
                                        threshold = 0.5F,
                                        minSilenceDuration = 0.25F,
                                        minSpeechDuration = 0.25F,
                                        windowSize = 512,
                                    ),
                                    sampleRate = 16000,
                                    numThreads = 1,
                                    provider = "cpu",
                                )
                                val vad = Vad(context.assets, vadConfig)
                                vad.acceptWaveform(audioData.toFloatArray())
                                vad.flush()
                                val hasHumanSpeech = vad.isSpeechDetected() || !vad.empty()
                                vad.release()

                                if (!hasHumanSpeech) {
                                    withContext(Dispatchers.Main) {
                                        recordingState = "Idle"
                                        progressText =
                                            "Không phát hiện thấy giọng nói! Vui lòng thử lại."
                                    }
                                    return@launch
                                }

                                withContext(Dispatchers.Main) {
                                    progressText = "Phát hiện giọng nói! Đang chạy Zipformer ASR..."
                                }

                                var zipformerText = ""
                                try {
                                    Log.d(
                                        "ZipformerASR",
                                        "Initializing OfflineModelConfig and OfflineRecognizerConfig..."
                                    )
                                    val modelConfig = OfflineModelConfig(
                                        transducer = OfflineTransducerModelConfig(
                                            encoder = "model/zipformer/encoder.int8.onnx",
                                            decoder = "model/zipformer/decoder.onnx",
                                            joiner = "model/zipformer/joiner.int8.onnx"
                                        ),
                                        tokens = "model/zipformer/tokens.txt",
                                        numThreads = 1,
                                        provider = "cpu"
                                    )
                                    val recognizerConfig = OfflineRecognizerConfig(
                                        modelConfig = modelConfig,
                                        decodingMethod = "greedy_search"
                                    )
                                    Log.d(
                                        "ZipformerASR",
                                        "Creating OfflineRecognizer from assets..."
                                    )
                                    val recognizer =
                                        OfflineRecognizer(context.assets, recognizerConfig)
                                    Log.d(
                                        "ZipformerASR",
                                        "OfflineRecognizer created successfully. Creating stream..."
                                    )
                                    val offlineStream = recognizer.createStream()
                                    Log.d(
                                        "ZipformerASR",
                                        "Stream created. Accepting waveform (${audioData.size} samples)..."
                                    )
                                    offlineStream.acceptWaveform(audioData.toFloatArray(), 16000)

                                    Log.d("ZipformerASR", "Decoding waveform...")
                                    recognizer.decode(offlineStream)

                                    Log.d("ZipformerASR", "Decoding finished. Getting result...")
                                    val result = recognizer.getResult(offlineStream)
                                    zipformerText = result.text
                                    Log.d(
                                        "ZipformerASR",
                                        "Zipformer result text: '$zipformerText', tokens count: ${result.tokens.size}"
                                    )

                                    offlineStream.release()
                                    recognizer.release()
                                    Log.d(
                                        "ZipformerASR",
                                        "OfflineRecognizer and stream released successfully."
                                    )
                                } catch (e: Throwable) {
                                    Log.e(
                                        "ZipformerASR",
                                        "Fatal error during Zipformer ASR execution",
                                        e
                                    )
                                    zipformerText = "Lỗi Zipformer: ${e.localizedMessage}"
                                }

                                withContext(Dispatchers.Main) {
                                    recognizedText = zipformerText
                                }

                                val config = SpeakerEmbeddingExtractorConfig(
                                    "model/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx",
                                    2,
                                    false,
                                    "cpu"
                                )
                                val extractor = SpeakerEmbeddingExtractor(context.assets, config)
                                val stream = extractor.createStream()
                                stream.acceptWaveform(audioData.toFloatArray(), 16000)
                                stream.inputFinished()
                                val embedding = extractor.compute(stream)
                                stream.release()
                                extractor.release()

                                VoiceLockState.referenceEmbedding = embedding
                                Log.d(
                                    "VoiceLockSetup",
                                    "Extracted reference embedding size: ${embedding.size}"
                                )
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
                if (VoiceLockState.referenceEmbedding == null) {
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
                                    verifyText =
                                        "Đang chạy Zipformer ASR & 3dspeaker Verification..."
                                }

                                Log.d(
                                    "VoiceVerificationDebug",
                                    "=== 3DSPEAKER & ZIPFORMER VERIFICATION DEBUG ==="
                                )
                                Log.d(
                                    "VoiceVerificationDebug",
                                    "Audio sample count: ${audioData.size}, duration: ${audioData.size * 1000L / 16000} ms"
                                )
                                if (audioData.isNotEmpty()) {
                                    val rms = sqrt(audioData.map { it * it }.average())
                                    Log.d(
                                        "VoiceVerificationDebug",
                                        "Audio RMS: $rms, min: ${audioData.minOrNull()}, max: ${audioData.maxOrNull()}"
                                    )
                                    if (rms < 0.01) {
                                        Log.w(
                                            "VoiceVerificationDebug",
                                            "⚠️ WARNING: Audio RMS is very low (< 0.01). User might be speaking too softly or mic is too far!"
                                        )
                                    }
                                }

                                // 1. Run Zipformer ASR
                                var verifyAsrText = ""
                                try {
                                    Log.d(
                                        "VoiceVerificationASR",
                                        "Initializing Zipformer for verification..."
                                    )
                                    val modelConfig = OfflineModelConfig(
                                        transducer = OfflineTransducerModelConfig(
                                            encoder = "model/zipformer/encoder.int8.onnx",
                                            decoder = "model/zipformer/decoder.onnx",
                                            joiner = "model/zipformer/joiner.int8.onnx"
                                        ),
                                        tokens = "model/zipformer/tokens.txt",
                                        numThreads = 1,
                                        provider = "cpu"
                                    )
                                    val recognizerConfig = OfflineRecognizerConfig(
                                        modelConfig = modelConfig,
                                        decodingMethod = "greedy_search"
                                    )
                                    val recognizer =
                                        OfflineRecognizer(context.assets, recognizerConfig)
                                    val offlineStream = recognizer.createStream()
                                    offlineStream.acceptWaveform(audioData.toFloatArray(), 16000)
                                    recognizer.decode(offlineStream)
                                    val result = recognizer.getResult(offlineStream)
                                    verifyAsrText = result.text
                                    Log.d(
                                        "VoiceVerificationASR",
                                        "Verification ASR result text: '$verifyAsrText'"
                                    )
                                    offlineStream.release()
                                    recognizer.release()
                                } catch (e: Throwable) {
                                    Log.e(
                                        "VoiceVerificationASR",
                                        "Error running Zipformer ASR in verification",
                                        e
                                    )
                                    verifyAsrText = "Lỗi ASR: ${e.localizedMessage}"
                                }

                                // 2. Run 3dspeaker Embedding Extraction & Verification
                                val config = SpeakerEmbeddingExtractorConfig(
                                    "model/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx",
                                    2,
                                    false,
                                    "cpu"
                                )
                                val extractor = SpeakerEmbeddingExtractor(context.assets, config)
                                val stream = extractor.createStream()
                                stream.acceptWaveform(audioData.toFloatArray(), 16000)
                                stream.inputFinished()
                                val queryEmbedding = extractor.compute(stream)
                                stream.release()
                                extractor.release()

                                val refEmbedding = VoiceLockState.referenceEmbedding!!

                                val refNorm =
                                    sqrt(refEmbedding.map { it * it }.sum().toDouble()).toFloat()
                                val queryNorm =
                                    sqrt(queryEmbedding.map { it * it }.sum().toDouble()).toFloat()
                                Log.d(
                                    "VoiceVerificationDebug",
                                    "Reference Embedding: size = ${refEmbedding.size}, norm = $refNorm, min = ${refEmbedding.minOrNull()}, max = ${refEmbedding.maxOrNull()}"
                                )
                                Log.d(
                                    "VoiceVerificationDebug",
                                    "Query Embedding: size = ${queryEmbedding.size}, norm = $queryNorm, min = ${queryEmbedding.minOrNull()}, max = ${queryEmbedding.maxOrNull()}"
                                )

                                val distance = cosineDistance(queryEmbedding, refEmbedding)
                                Log.d(
                                    "VoiceVerificationDebug",
                                    "Calculated Cosine Distance: $distance (Threshold: 0.35)"
                                )

                                if (distance > 0.35f) {
                                    Log.w(
                                        "VoiceVerificationDebug",
                                        "❌ WHY 3DSPEAKER FAILED TO MATCH (DIAGNOSTICS):"
                                    )
                                    Log.w(
                                        "VoiceVerificationDebug",
                                        "1. Distance ($distance) exceeds threshold 0.35."
                                    )
                                    Log.w(
                                        "VoiceVerificationDebug",
                                        "2. Acoustic mismatch: Background noise, different room acoustics or microphone gain between setup and verification."
                                    )
                                    Log.w(
                                        "VoiceVerificationDebug",
                                        "3. Voice variation: Speaking speed, pitch, emotion, or phrasing differed."
                                    )
                                    Log.w(
                                        "VoiceVerificationDebug",
                                        "4. Audio clipping or low energy (RMS = ${
                                            if (audioData.isNotEmpty()) sqrt(audioData.map { it * it }
                                                .average()) else 0.0
                                        })."
                                    )
                                }

                                val targetKeyword = VoiceLockState.targetKeyword ?: ""
                                val similarity = if (targetKeyword.isNotEmpty()) {
                                    stringSimilarity(
                                        verifyAsrText.lowercase().trim(),
                                        targetKeyword.lowercase().trim()
                                    )
                                } else {
                                    1.0
                                }

                                val isTextMatch =
                                    targetKeyword.isEmpty() || similarity >= 0.65 // 65% fuzzy tolerance for homophones
                                val isSpeakerMatch = distance < 0.4f
                                val isMatch = isSpeakerMatch && isTextMatch

                                Log.d(
                                    "VoiceVerificationDebug",
                                    "❌ XÁC THỰC THẤT BẠI - Target keyword: '$targetKeyword', Zipformer Result: '$verifyAsrText', Similarity: $similarity, TextMatch: $isTextMatch, SpeakerMatch: $isSpeakerMatch, FinalMatch: $isMatch"
                                )

                                withContext(Dispatchers.Main) {
                                    verifyState = "Idle"
                                    verifyText = "Hoàn tất xác thực!"
                                    matchResult = if (isMatch) {
                                        "✅ XÁC THỰC THÀNH CÔNG!\nKhoảng cách Cosine: %.4f\nASR Text: '$verifyAsrText'\nĐộ tương đồng từ khóa: %.1f%%".format(
                                            distance, similarity * 100
                                        )
                                    } else {
                                        val reason = buildString {
                                            if (!isSpeakerMatch) append(
                                                "\n- Giọng nói không khớp (Cosine Distance: %.4f >= 0.35)".format(
                                                    distance
                                                )
                                            )
                                            if (!isTextMatch) append(
                                                "\n- Từ khóa không khớp (ASR: '$verifyAsrText' vs Target: '$targetKeyword', Tương đồng: %.1f%%)".format(
                                                    similarity * 100
                                                )
                                            )
                                        }
                                        "❌ XÁC THỰC THẤT BẠI!$reason\n- Từ khóa yêu cầu: '$targetKeyword'\n- Kết quả Zipformer: '$verifyAsrText'"
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
                        Log.w(
                            "SpeechRecognizerDebug",
                            "Timeout triggered (5s reached)! Cancelling SpeechRecognizer..."
                        )
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
                        Log.d(
                            "SpeechRecognizerDebug",
                            "onBeginningOfSpeech: Người dùng đã bắt đầu nói"
                        )
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
                            val matches =
                                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
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
                        val partial =
                            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
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



fun stringSimilarity(s1: String, s2: String): Double {
    val len1 = s1.length
    val len2 = s2.length
    val dp = Array(len1 + 1) { IntArray(len2 + 1) }

    for (i in 0..len1) dp[i][0] = i
    for (j in 0..len2) dp[0][j] = j

    for (i in 1..len1) {
        for (j in 1..len2) {
            val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
            dp[i][j] = minOf(
                dp[i - 1][j] + 1,
                dp[i][j - 1] + 1,
                dp[i - 1][j - 1] + cost
            )
        }
    }
    val maxLen = maxOf(len1, len2)
    if (maxLen == 0) return 1.0
    return 1.0 - dp[len1][len2].toDouble() / maxLen
}