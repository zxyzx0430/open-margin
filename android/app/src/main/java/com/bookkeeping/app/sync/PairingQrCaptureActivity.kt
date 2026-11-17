package com.bookkeeping.app.sync

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.os.Bundle
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.bookkeeping.app.R
import com.bookkeeping.app.i18n.tr
import com.journeyapps.barcodescanner.CaptureManager
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.ViewfinderView

/** ZXing capture activity with the app's camera-style pairing overlay. */
class PairingQrCaptureActivity : ComponentActivity() {
    private lateinit var barcodeScanner: DecoratedBarcodeView
    private var captureManager: CaptureManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setContentView(R.layout.activity_pairing_qr_capture)
        barcodeScanner = findViewById(R.id.zxing_barcode_scanner)
        barcodeScanner.statusView?.visibility = View.GONE
        findViewById<ComposeView>(R.id.pairing_qr_controls).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                PairingQrCaptureControls(
                    onClose = ::finish,
                    onTorchChange = { enabled ->
                        if (enabled) barcodeScanner.setTorchOn() else barcodeScanner.setTorchOff()
                    },
                )
            }
        }

        captureManager = CaptureManager(this, barcodeScanner).also { manager ->
            manager.initializeFromIntent(intent, savedInstanceState)
            manager.decode()
        }
    }

    override fun onResume() {
        super.onResume()
        captureManager?.onResume()
    }

    override fun onPause() {
        super.onPause()
        captureManager?.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        captureManager?.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        captureManager?.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Android")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        captureManager?.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        barcodeScanner.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)
}

@Composable
private fun PairingQrCaptureControls(
    onClose: () -> Unit,
    onTorchChange: (Boolean) -> Unit,
) {
    var torchEnabled by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 16.dp, top = 8.dp)
                .size(48.dp)
                .clip(CircleShape)
                .background(ComposeColor.Black.copy(alpha = 0.48f)),
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = tr("关闭"),
                tint = ComposeColor.White,
            )
        }

        IconButton(
            onClick = {
                torchEnabled = !torchEnabled
                onTorchChange(torchEnabled)
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(end = 16.dp, top = 8.dp)
                .size(48.dp)
                .clip(CircleShape)
                .background(ComposeColor.Black.copy(alpha = 0.48f)),
        ) {
            Icon(
                imageVector = if (torchEnabled) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                contentDescription = tr(if (torchEnabled) "关闭手电筒" else "打开手电筒"),
                tint = ComposeColor.White,
            )
        }

        Text(
            text = "将电脑上的二维码放入框内",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 40.dp),
            color = ComposeColor.White,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}

/** Viewfinder mask and corner guides aligned to ZXing's actual decode frame. */
class PairingQrViewfinderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewfinderView(context, attrs) {
    private val cornerLength = 28f * resources.displayMetrics.density
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        strokeCap = Paint.Cap.SQUARE
        strokeJoin = Paint.Join.ROUND
    }

    init {
        setLaserVisibility(false)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        refreshSizes()
        val frame = framingRect ?: return
        val left = frame.left.toFloat()
        val top = frame.top.toFloat()
        val right = frame.right.toFloat()
        val bottom = frame.bottom.toFloat()
        val length = minOf(cornerLength, frame.width() / 3f, frame.height() / 3f)

        canvas.drawLine(left, top, left + length, top, cornerPaint)
        canvas.drawLine(left, top, left, top + length, cornerPaint)
        canvas.drawLine(right, top, right - length, top, cornerPaint)
        canvas.drawLine(right, top, right, top + length, cornerPaint)
        canvas.drawLine(left, bottom, left + length, bottom, cornerPaint)
        canvas.drawLine(left, bottom, left, bottom - length, cornerPaint)
        canvas.drawLine(right, bottom, right - length, bottom, cornerPaint)
        canvas.drawLine(right, bottom, right, bottom - length, cornerPaint)
    }
}
