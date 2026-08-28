package com.wsvdmeer.pwncompanion.presentation.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wsvdmeer.pwncompanion.models.CaptureEntry
import com.wsvdmeer.pwncompanion.models.GpsData
import com.wsvdmeer.pwncompanion.presentation.theme.TerminalMono
import com.wsvdmeer.pwncompanion.utils.TileMapLoader
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan

// ── Web-Mercator normalized coords (0..1), independent of zoom ────────────────
private fun lonToNx(lon: Double) = (lon + 180.0) / 360.0
private fun latToNy(lat: Double): Double {
    val r = Math.toRadians(lat)
    return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0
}

private class Marker(val nx: Double, val ny: Double, val cap: CaptureEntry)

/** Status-priority colour for a set of captures: cracked > crackable > partial > other. */
private fun statusColor(caps: List<CaptureEntry>): Color = when {
    caps.any { it.isCracked }   -> Color(0x3D, 0xFF, 0x6E)
    caps.any { it.isCrackable } -> Color(0xBB, 0xFF, 0x44)
    caps.any { it.isPartial }   -> Color(0xFF, 0xA5, 0x33)
    else                        -> Color(0x26, 0xAA, 0x55)
}

/**
 * Continuous slippy-map renderer: a real OSM tile pyramid drawn under a live GPU pan/zoom
 * transform, with deeper tiles streaming in seamlessly as you zoom. Tiles are drawn at full
 * resolution (no pixel shader / no cell grid) so streets stay sharp and readable, and each
 * capture is a crisp, labelled pin at its exact lat/lon — clear locations at a glance, like a
 * lite Google Maps.
 *
 * Requires API 33+ (the caller falls back to the coarse grid renderer on older devices).
 */
@Composable
internal fun SlippyPixelMap(
    points: List<CaptureEntry>,
    current: GpsData?,
    onCatch: (List<CaptureEntry>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val geo = remember(points) { points.filter { it.latitude != null && it.longitude != null } }
    val markers = remember(geo) { geo.map { Marker(lonToNx(it.longitude!!), latToNy(it.latitude!!), it) } }
    val you = current?.takeIf { it.isValid() }?.let { Marker(lonToNx(it.longitude), latToNy(it.latitude), geo.firstOrNull() ?: CaptureEntry()) }

    if (markers.isEmpty()) {
        Text("  no geolocated captures yet", color = dim, fontSize = 11.sp, fontFamily = TerminalMono, modifier = modifier)
        return
    }

    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val markerR = with(density) { 5.dp.toPx() }       // filled pin radius (screen-space, fixed)

    Column(modifier = modifier) {
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f)) {
            val wPx = constraints.maxWidth.toFloat()
            val hPx = constraints.maxHeight.toFloat()

            // View state: normalized centre + DISCRETE integer zoom. Pan is continuous; a pinch
            // accumulates until it crosses a level threshold, then steps zoom by ±1. Init fits all captures.
            var centerX by remember(geo) { mutableStateOf(0.5) }
            var centerY by remember(geo) { mutableStateOf(0.5) }
            var zoom by remember(geo) { mutableStateOf(4f) }        // always an integer value
            var pinchAccum by remember(geo) { mutableStateOf(1f) }  // pinch scale since the last step
            var inited by remember(geo) { mutableStateOf(false) }
            var initCx by remember(geo) { mutableStateOf(0.5) }
            var initCy by remember(geo) { mutableStateOf(0.5) }
            var initZoom by remember(geo) { mutableStateOf(4f) }

            LaunchedEffect(geo, wPx, hPx) {
                if (inited || wPx <= 0f) return@LaunchedEffect
                val nxs = markers.map { it.nx } + (you?.let { listOf(it.nx) } ?: emptyList())
                val nys = markers.map { it.ny } + (you?.let { listOf(it.ny) } ?: emptyList())
                val spanN = maxOf(nxs.max() - nxs.min(), nys.max() - nys.min(), 3e-5)
                val fit = log2(minOf(wPx, hPx) / (256.0 * spanN * 1.35)).roundToInt().coerceIn(3, 18).toFloat()
                centerX = (nxs.min() + nxs.max()) / 2; centerY = (nys.min() + nys.max()) / 2; zoom = fit
                initCx = centerX; initCy = centerY; initZoom = zoom
                inited = true
            }

            // Loaded tiles (level/x/y → bitmap) + in-flight guard. Persist across zoom so lower levels
            // stay cached as an underlay while finer tiles stream in (seamless, no blanks).
            val tiles = remember(geo) { mutableStateMapOf<String, Bitmap>() }
            val inflight = remember(geo) { mutableSetOf<String>() }

            LaunchedEffect(geo, wPx, hPx) {
                snapshotFlow {
                    val z = zoom.toDouble()
                    val iz = floor(z).toInt().coerceIn(3, 19)
                    val pxPerN = 2.0.pow(z) * 256.0
                    val n = 1 shl iz
                    val nL = centerX - (wPx / 2) / pxPerN; val nR = centerX + (wPx / 2) / pxPerN
                    val nT = centerY - (hPx / 2) / pxPerN; val nB = centerY + (hPx / 2) / pxPerN
                    val txMin = floor(nL * n).toInt().coerceAtLeast(0); val txMax = floor(nR * n).toInt().coerceAtMost(n - 1)
                    val tyMin = floor(nT * n).toInt().coerceAtLeast(0); val tyMax = floor(nB * n).toInt().coerceAtMost(n - 1)
                    val keys = HashSet<String>()
                    for (tx in txMin..txMax) for (ty in tyMin..tyMax) keys.add("$iz/$tx/$ty")
                    keys
                }.collectLatest { keys ->
                    keys.forEach { k ->
                        if (!tiles.containsKey(k) && inflight.add(k)) {
                            launch {
                                val p = k.split("/")
                                val b = TileMapLoader.tile(context, p[0].toInt(), p[1].toInt(), p[2].toInt())
                                if (b != null) tiles[k] = b
                                inflight.remove(k)
                            }
                        }
                    }
                }
            }

            val pxPerN = 2.0.pow(zoom.toDouble()) * 256.0

            // Project a normalized (nx, ny) point to its current screen position.
            fun project(nx: Double, ny: Double) =
                Offset(
                    (wPx / 2f + (nx - centerX) * pxPerN).toFloat(),
                    (hPx / 2f + (ny - centerY) * pxPerN).toFloat(),
                )

            // Tile layer — full-resolution, no pixel effect. FilterQuality.High keeps underlay
            // tiles smooth while finer levels stream in.
            Canvas(Modifier.matchParentSize()) {
                drawRect(Color(0xFF02060A))
                if (!inited) return@Canvas
                // Tiles: coarsest first so finer levels land on top (seamless multi-level look).
                tiles.entries.sortedBy { it.key.substringBefore('/').toInt() }.forEach { (k, bmp) ->
                    val p = k.split("/"); val lvl = p[0].toInt(); val tx = p[1].toInt(); val ty = p[2].toInt()
                    val n = 1 shl lvl
                    val sz = pxPerN / n
                    val sx = wPx / 2 + (tx.toDouble() / n - centerX) * pxPerN
                    val sy = hPx / 2 + (ty.toDouble() / n - centerY) * pxPerN
                    if (sx + sz < 0 || sy + sz < 0 || sx > wPx || sy > hPx) return@forEach
                    val d = ceil(sz).toInt() + 1
                    drawImage(
                        image = bmp.asImageBitmap(),
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(bmp.width, bmp.height),
                        dstOffset = IntOffset(sx.roundToInt(), sy.roundToInt()),
                        dstSize = IntSize(d, d),
                        filterQuality = FilterQuality.High,
                    )
                }
            }

            // Markers + gestures on top — crisp pins at exact projected positions.
            Canvas(
                Modifier.matchParentSize()
                    .pointerInput(geo) {
                        detectTransformGestures { centroid, pan, gz, _ ->
                            val ppn = 2.0.pow(zoom.toDouble()) * 256.0
                            var cx = centerX - pan.x / ppn
                            var cy = centerY - pan.y / ppn
                            pinchAccum *= gz
                            var nz = zoom
                            while (pinchAccum >= 1.5f && nz < 19f) { nz += 1f; pinchAccum /= 2f }
                            while (pinchAccum <= 0.6667f && nz > 3f) { nz -= 1f; pinchAccum *= 2f }
                            if (nz != zoom) {
                                val nUx = cx + (centroid.x - wPx / 2) / ppn
                                val nUy = cy + (centroid.y - hPx / 2) / ppn
                                val ppn2 = 2.0.pow(nz.toDouble()) * 256.0
                                cx = nUx - (centroid.x - wPx / 2) / ppn2
                                cy = nUy - (centroid.y - hPx / 2) / ppn2
                            }
                            centerX = cx.coerceIn(0.0, 1.0)
                            centerY = cy.coerceIn(0.0, 1.0)
                            zoom = nz
                        }
                    }
                    .pointerInput(geo) {
                        detectTapGestures(
                            onDoubleTap = { centerX = initCx; centerY = initCy; zoom = initZoom },
                            onTap = { pos ->
                                // Return every capture whose pin sits within a generous tap radius.
                                val ppn = 2.0.pow(zoom.toDouble()) * 256.0
                                val thr = markerR * 3f
                                val hit = markers.filter {
                                    val sx = wPx / 2 + (it.nx - centerX) * ppn
                                    val sy = hPx / 2 + (it.ny - centerY) * ppn
                                    hypot(sx - pos.x, sy - pos.y) < thr
                                }.map { it.cap }
                                if (hit.isNotEmpty()) onCatch(hit)
                            },
                        )
                    }
            ) {
                if (!inited) return@Canvas

                // Draw a small dark rounded badge + monospace label centred above a screen point.
                fun DrawScope.label(text: String, x: Float, topY: Float, tint: Color) {
                    val layout = textMeasurer.measure(
                        text,
                        TextStyle(color = Color.White, fontSize = 9.sp, fontFamily = TerminalMono),
                    )
                    val w = layout.size.width.toFloat()
                    val h = layout.size.height.toFloat()
                    val pad = 4f
                    val cx = x.coerceIn(w / 2 + pad, wPx - w / 2 - pad)
                    val badgeTop = (topY - h - pad * 2).coerceAtLeast(0f)
                    drawRoundRect(
                        Color(0xCC000000),
                        topLeft = Offset(cx - w / 2 - pad, badgeTop),
                        size = Size(w + pad * 2, h + pad * 2),
                        cornerRadius = CornerRadius(4f, 4f),
                    )
                    drawText(layout, topLeft = Offset(cx - w / 2, badgeTop + pad))
                    if (tint != Color.Unspecified) {
                        drawRect(tint, topLeft = Offset(cx - w / 2 - pad, badgeTop + h + pad * 2), size = Size(w + pad * 2, 2f))
                    }
                }

                // Screen positions for every capture pin.
                val positions = markers.map { m -> m to project(m.nx, m.ny) }
                // Greedy cluster: pins that overlap on screen share one marker (with a count badge).
                val clusters = ArrayList<Pair<Offset, MutableList<CaptureEntry>>>()
                for ((m, p) in positions) {
                    var placed = false
                    for (c in clusters) {
                        if (hypot(c.first.x - p.x, c.first.y - p.y) < markerR * 2.4f) {
                            c.second.add(m.cap); placed = true; break
                        }
                    }
                    if (!placed) clusters.add(p to mutableListOf(m.cap))
                }

                clusters.forEach { (p, caps) ->
                    if (p.x < -markerR * 2 || p.y < -markerR * 2 || p.x > wPx + markerR * 2 || p.y > hPx + markerR * 2) return@forEach
                    val color = statusColor(caps)
                    val cnt = caps.size
                    // Soft glow so the pin reads against any tile.
                    drawCircle(color.copy(alpha = 0.22f), radius = markerR * 2.4f, center = p)
                    // Filled pin + crisp white ring.
                    drawCircle(color, radius = markerR, center = p)
                    drawCircle(Color.White, radius = markerR, center = p, style = Stroke(width = 1.6f))
                    // White centre dot on clusters — indicates more than one catch here.
                    if (cnt > 1) drawCircle(Color.White.copy(alpha = 0.9f), radius = markerR * 0.42f, center = p)
                    // SSID label (or a count for clusters) above the pin.
                    val labelText = if (cnt == 1) caps.first().ssid.let { if (it.length > 18) it.take(17) + "…" else it } else "$cnt captures"
                    label(labelText, p.x, p.y - markerR * 2.2f, color)
                }

                // You: GPS crosshair — white ring + bright centre dot (distinct from catches).
                you?.let { m ->
                    val p = project(m.nx, m.ny)
                    if (p.x >= -markerR * 2 && p.y >= -markerR * 2 && p.x <= wPx + markerR * 2 && p.y <= hPx + markerR * 2) {
                        drawCircle(Color(0xFF, 0xA5, 0x33, 0x30), radius = markerR * 2.6f, center = p)
                        drawCircle(Color(0xFF, 0xA5, 0x33), radius = markerR * 0.75f, center = p)
                        drawCircle(Color.White, radius = markerR * 0.75f, center = p, style = Stroke(width = 1.6f))
                        drawCircle(Color.White, radius = markerR * 0.30f, center = p)
                        label("you", p.x, p.y - markerR * 1.9f, Color(0xFF, 0xA5, 0x33))
                    }
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            "drag to pan · pinch to zoom · tap a capture · double-tap to reset",
            color = dim.copy(alpha = 0.6f), fontSize = 9.sp, fontFamily = TerminalMono,
        )
    }
}
