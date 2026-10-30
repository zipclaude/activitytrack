/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.settings

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import it.infraconsulting.activitytrack.R
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/*
 * Full-screen camera that reads one QR code (issue #113): CameraX preview,
 * frames decoded by ZXing on a background thread; no Google services.
 * [onCode] gets the first text found, once.
 */
@Composable
fun QrScanner(onCode: (String) -> Unit, onDismiss: () -> Unit) {
	val context = LocalContext.current
	val lifecycle = LocalLifecycleOwner.current
	val deliver by rememberUpdatedState(onCode)
	val done = remember { AtomicBoolean(false) }
	val executor = remember { Executors.newSingleThreadExecutor() }
	val view = remember { PreviewView(context) }
	DisposableEffect(lifecycle) {
		val future = ProcessCameraProvider.getInstance(context)
		future.addListener({
			val provider = future.get()
			val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
			val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
			val reader = MultiFormatReader().apply { setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))) }
			analysis.setAnalyzer(executor) { image ->
				val text = image.use { decode(reader, it) }
				if (text != null && done.compareAndSet(false, true))
					ContextCompat.getMainExecutor(context).execute { deliver(text) }
			}
			provider.unbindAll()
			provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
		}, ContextCompat.getMainExecutor(context))
		onDispose {
			runCatching { future.get().unbindAll() }
			executor.shutdown()
		}
	}
	Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
		Surface(modifier = Modifier.fillMaxSize()) {
			Box(modifier = Modifier.fillMaxSize()) {
				AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
				Column(modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
					Text(stringResource(R.string.sync_scan_hint), style = MaterialTheme.typography.bodyLarge)
					TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
				}
			}
		}
	}
}

/* the luminance plane is enough for a QR code */
private fun decode(reader: MultiFormatReader, image: ImageProxy): String? {
	val plane = image.planes[0]
	val buffer = plane.buffer
	val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
	val source = PlanarYUVLuminanceSource(data, plane.rowStride, image.height, 0, 0, image.width, image.height, false)
	return try {
		reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
	} catch (_: NotFoundException) {
		null
	} finally {
		reader.reset()
	}
}
