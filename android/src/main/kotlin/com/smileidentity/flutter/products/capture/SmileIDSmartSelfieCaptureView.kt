package com.smileidentity.flutter.products.capture

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smileidentity.R
import com.smileidentity.SmileID
import com.smileidentity.SmileIDOptIn
import com.smileidentity.compose.SmartSelfieEnrollmentEnhanced
import com.smileidentity.compose.components.ImageCaptureConfirmationDialog
import com.smileidentity.compose.selfie.SelfieCaptureScreen
import com.smileidentity.compose.selfie.SmartSelfieInstructionsScreen
import com.smileidentity.compose.theme.colorScheme
import com.smileidentity.compose.theme.typography
import com.smileidentity.flutter.results.SmartSelfieCaptureResult
import com.smileidentity.flutter.utils.SelfieCaptureResultAdapter
import com.smileidentity.flutter.utils.toSmileSensitivity
import com.smileidentity.flutter.views.SmileIDViewFactory
import com.smileidentity.flutter.views.SmileSelfieComposablePlatformView
import com.smileidentity.metadata.LocalMetadataProvider
import com.smileidentity.models.SmileSensitivity
import com.smileidentity.util.randomJobId
import com.smileidentity.util.randomUserId
import com.smileidentity.viewmodel.SelfieUiState
import com.smileidentity.viewmodel.SelfieViewModel
import com.smileidentity.viewmodel.viewModelFactory
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.platform.PlatformViewFactory
import java.io.File
import kotlinx.coroutines.delay

internal class SmileIDSmartSelfieCaptureView private constructor(
    context: Context,
    viewId: Int,
    messenger: BinaryMessenger,
    args: Map<String, Any?>,
) : SmileSelfieComposablePlatformView(context, VIEW_TYPE_ID, viewId, messenger, args) {
    companion object {
        const val VIEW_TYPE_ID = "SmileIDSmartSelfieCaptureView"

        // Tiny 1x1 transparent PNG, base64 encoded, reused for mock selfie and liveness frames
        private val MOCK_LIVENESS_IMAGE: ByteArray by lazy {
            Base64.decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR4nGMAAQAABQABDQottAAAAABJRU5ErkJggg==",
                Base64.DEFAULT,
            )
        }

        fun createFactory(messenger: BinaryMessenger): PlatformViewFactory =
            SmileIDViewFactory(messenger = messenger) { context, args, messenger, viewId ->
                SmileIDSmartSelfieCaptureView(
                    context = context,
                    viewId = viewId,
                    messenger = messenger,
                    args = args,
                )
            }
    }

    @OptIn(SmileIDOptIn::class)
    @Composable
    override fun Content(args: Map<String, Any?>) {
        val smileSensitivity = (args["smileSensitivity"] as? String).toSmileSensitivity()
        val showConfirmationDialog = args["showConfirmationDialog"] as? Boolean ?: true
        val showInstructions = args["showInstructions"] as? Boolean ?: true
        val showAttribution = args["showAttribution"] as? Boolean ?: true
        val allowAgentMode = args["allowAgentMode"] as? Boolean ?: true
        val useStrictMode = args["useStrictMode"] as? Boolean ?: false
        val enableSandboxManualButton = args["enableSandboxManualCapture"] as? Boolean ?: false
        var acknowledgedInstructions by rememberSaveable { mutableStateOf(false) }
        val userId = randomUserId()
        val jobId = randomJobId()
        val isSandbox = SmileID.useSandbox
        var showSandboxManualButton by rememberSaveable { mutableStateOf(false) }
        var sandboxButtonEnabled by rememberSaveable { mutableStateOf(true) }
        val context = LocalContext.current

        // In sandbox, reveal a manual fallback button after a short delay.
        LaunchedEffect(isSandbox, enableSandboxManualButton) {
            if (isSandbox && enableSandboxManualButton) {
                delay(4000)
                showSandboxManualButton = true
            } else {
                showSandboxManualButton = false
            }
        }

        LocalMetadataProvider.MetadataProvider {
            MaterialTheme(colorScheme = SmileID.colorScheme, typography = SmileID.typography) {
                Surface {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (useStrictMode) {
                            // Enhanced mode doesn't support confirmation dialog
                            SmileID.SmartSelfieEnrollmentEnhanced(
                                userId = userId,
                                showAttribution = showAttribution,
                                showInstructions = showInstructions,
                                skipApiSubmission = true,
                                onResult = { res -> handleResult(res) },
                            )
                        } else {
                            // Custom implementation for regular mode with confirmation dialog support
                            val viewModel: SelfieViewModel = viewModel(
                                factory = viewModelFactory {
                                    SelfieViewModel(
                                        isEnroll = true,
                                        userId = userId,
                                        jobId = jobId,
                                        allowNewEnroll = true,
                                        allowAgentMode = allowAgentMode,
                                        skipApiSubmission = true,
                                        metadata = mutableListOf(),
                                        smileSensitivity = smileSensitivity,
                                    )
                                },
                            )

                            val uiState = viewModel.uiState.collectAsStateWithLifecycle().value

                            when {
                                showInstructions && !acknowledgedInstructions ->
                                    SmartSelfieInstructionsScreen(
                                        showAttribution = showAttribution,
                                    ) {
                                        acknowledgedInstructions = true
                                    }

                                uiState.processingState != null -> HandleProcessingState(viewModel)
                                uiState.selfieToConfirm != null ->
                                    HandleSelfieConfirmation(
                                        showConfirmationDialog,
                                        uiState,
                                        viewModel,
                                    )

                                else -> RenderSelfieCaptureScreen(
                                    userId,
                                    jobId,
                                    allowAgentMode,
                                    smileSensitivity,
                                    viewModel,
                                )
                            }
                        }
                        if (isSandbox && enableSandboxManualButton && showSandboxManualButton && acknowledgedInstructions) {
                            Button(
                                onClick = {
                                    sandboxButtonEnabled = false
                                    sendSandboxSuccess(context)
                                },
                                enabled = sandboxButtonEnabled,
                                shape = CircleShape,
                                border = BorderStroke(1.dp, SmileID.colorScheme.primary),
                                colors =
                                    ButtonDefaults.buttonColors(
                                        containerColor = SmileID.colorScheme.primary.copy(alpha = 0.8f),
                                        contentColor = SmileID.colorScheme.onPrimary,
                                    ),
                                contentPadding = ButtonDefaults.ContentPadding,
                                modifier =
                                    Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 24.dp)
                                        .size(72.dp)
                                        .semantics { contentDescription = "smile_selfie_camera_capture" }
                                        .testTag("sandbox_manual_selfie_capture"),
                            ) {
                                // Document fallback button is a plain capture circle, so no text/icon here
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun RenderSelfieCaptureScreen(
        userId: String,
        jobId: String,
        allowAgentMode: Boolean,
        smileSensitivity: SmileSensitivity,
        viewModel: SelfieViewModel,
    ) {
        Box(
            modifier = Modifier
                .background(color = Color.White)
                .windowInsetsPadding(WindowInsets.statusBars)
                .consumeWindowInsets(WindowInsets.statusBars)
                .fillMaxSize(),
        ) {
            SelfieCaptureScreen(
                userId = userId,
                jobId = jobId,
                allowAgentMode = allowAgentMode,
                allowNewEnroll = true,
                skipApiSubmission = true,
                viewModel = viewModel,
                smileSensitivity = smileSensitivity,
            )
        }
    }

    @Composable
    private fun HandleSelfieConfirmation(
        showConfirmation: Boolean,
        uiState: SelfieUiState,
        viewModel: SelfieViewModel,
    ) {
        if (showConfirmation) {
            ImageCaptureConfirmationDialog(
                titleText = stringResource(R.string.si_smart_selfie_confirmation_dialog_title),
                subtitleText = stringResource(
                    R.string.si_smart_selfie_confirmation_dialog_subtitle,
                ),
                painter = BitmapPainter(
                    BitmapFactory
                        .decodeFile(uiState.selfieToConfirm!!.absolutePath)
                        .asImageBitmap(),
                ),
                confirmButtonText = stringResource(
                    R.string.si_smart_selfie_confirmation_dialog_confirm_button,
                ),
                onConfirm = { viewModel.submitJob() },
                retakeButtonText = stringResource(
                    R.string.si_smart_selfie_confirmation_dialog_retake_button,
                ),
                onRetake = viewModel::onSelfieRejected,
                scaleFactor = 1.25f,
            )
        } else {
            viewModel.submitJob()
        }
    }

    @Composable
    private fun HandleProcessingState(viewModel: SelfieViewModel) {
        viewModel.onFinished { res -> handleResult(res) }
    }

    /**
     * Sandbox-only helper to unblock flows when auto-capture stalls. Generates a temp selfie file
     * and returns a minimal result payload to Flutter. This intentionally avoids touching the
     * native selfie pipeline.
     */
    private fun sendSandboxSuccess(context: Context) {
        try {
            val selfieFile =
                File.createTempFile("sandbox_selfie_", ".jpg", context.cacheDir).apply {
                    writeBytes(MOCK_LIVENESS_IMAGE)
                }
            val livenessFiles =
                List(3) { index ->
                    File.createTempFile("sandbox_liveness_${index}_", ".jpg", context.cacheDir).apply {
                        writeBytes(MOCK_LIVENESS_IMAGE)
                    }
                }
            val result =
                SmartSelfieCaptureResult(
                    selfieFile = selfieFile,
                    livenessFiles = livenessFiles,
                    apiResponse = null,
                    didSubmitBiometricKycJob = false,
                )
            val moshi =
                SmileID.moshi
                    .newBuilder()
                    .add(SelfieCaptureResultAdapter.FACTORY)
                    .build()
            val json =
                moshi
                    .adapter(SmartSelfieCaptureResult::class.java)
                    .toJson(result)
            onSuccessJson(json)
        } catch (e: Exception) {
            onError(e)
        }
    }
}
