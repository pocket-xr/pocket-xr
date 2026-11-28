package com.hayeco.flipspaces.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hayeco.flipspaces.R
import com.hayeco.flipspaces.core.spatial.Wallpaper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 1.6.37 (Crr66: "Can the space have a wallpaper? So you have a virtual room"): the Space settings' Wallpaper card. A row
 * of tiles, each the scene's own picture painted by the same code that paints the glasses' (so what you choose is what you
 * get), the room first. Your picture opens the phone's photo picker; once one is saved its tile shows it. Under the tiles:
 * the brightness, and for a picture that is not a panorama, how wide it is. Every change reaches a running Space at once.
 */
@Composable
fun WallpaperCard(
    settings: Wallpaper.Settings,
    /** Your picture's preview, or null while none is saved. Changes when another is chosen. */
    picture: Bitmap?,
    /** Your picture is a 360 panorama. */
    panorama: Boolean,
    onChange: (Wallpaper.Settings) -> Unit,
    onPicture: (android.net.Uri) -> Unit,
) {
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(onPicture) }
    fun choosePicture() = pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.wallpaper_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.wallpaper_help), style = MaterialTheme.typography.bodySmall)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Wallpaper.Scene.SHOWN.forEach { scene ->
                    SceneTile(scene, settings.scene == scene, picture) {
                        if (scene == Wallpaper.Scene.PICTURE && picture == null) choosePicture()
                        else onChange(settings.copy(scene = scene))
                    }
                }
            }
            AnimatedVisibility(settings.on) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.wallpaper_brightness, settings.brightness), style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = settings.brightness.toFloat(),
                        onValueChange = { onChange(settings.copy(brightness = Wallpaper.brightness((it / 5f).roundToInt() * 5))) },
                        // Smooth to drag (no tick marks); the value is rounded to 5 %.
                        valueRange = Wallpaper.MIN_BRIGHTNESS.toFloat()..Wallpaper.MAX_BRIGHTNESS.toFloat(),
                    )
                }
            }
            AnimatedVisibility(settings.scene == Wallpaper.Scene.PICTURE) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (!panorama && picture != null) {
                        Text(stringResource(R.string.wallpaper_picture_width, settings.pictureDeg), style = MaterialTheme.typography.bodyMedium)
                        Slider(
                            value = settings.pictureDeg.toFloat(),
                            onValueChange = { onChange(settings.copy(pictureDeg = Wallpaper.pictureDeg((it / 5f).roundToInt() * 5))) },
                            valueRange = Wallpaper.MIN_PICTURE_DEG.toFloat()..Wallpaper.MAX_PICTURE_DEG.toFloat(),
                        )
                    }
                    Text(stringResource(R.string.wallpaper_picture_help), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = ::choosePicture) {
                        Text(stringResource(if (picture == null) R.string.wallpaper_choose else R.string.wallpaper_change))
                    }
                }
            }
        }
    }
}

/** The tiles' shape: 2 to 1, like the scenes' own pictures. */
private val TILE_W = 128.dp
private val TILE_H = 64.dp

@Composable
private fun SceneTile(scene: Wallpaper.Scene, selected: Boolean, picture: Bitmap?, onClick: () -> Unit) {
    val name = stringResource(sceneName(scene))
    val chosen = stringResource(R.string.wallpaper_chosen, name)
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .width(TILE_W)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = if (selected) chosen else name },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .size(TILE_W, TILE_H)
                .clip(shape)
                .background(Color.Black)
                .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape),
        ) {
            when {
                scene.painted -> ScenePreview(scene)
                scene == Wallpaper.Scene.PICTURE && picture != null ->
                    Image(picture.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                scene == Wallpaper.Scene.PICTURE -> Text("+", Modifier.align(Alignment.Center), style = MaterialTheme.typography.headlineMedium, color = Color.White.copy(alpha = 0.8f))
                // The room: what the glasses show with nothing behind the windows. A faint window outline on black.
                else -> Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(56.dp, 32.dp)
                        .border(1.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(4.dp)),
                )
            }
            if (selected) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                ) {
                    Text("✓", Modifier.align(Alignment.Center), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
        Text(
            name,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/**
 * The part of the scene ahead (the middle half of its picture), painted off the main thread at the tile's size and kept
 * for as long as the app runs, with a soft fade in.
 */
@Composable
private fun ScenePreview(scene: Wallpaper.Scene) {
    val density = LocalContext.current.resources.displayMetrics.density
    val bmp by produceState<Bitmap?>(PREVIEWS[scene], scene) {
        if (value == null) value = withContext(Dispatchers.Default) { preview(scene, (TILE_W.value * density).roundToInt(), (TILE_H.value * density).roundToInt()) }
    }
    val b = bmp
    if (b == null) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0E1420), Color(0xFF05070C)))))
    } else {
        Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

private val PREVIEWS = java.util.concurrent.ConcurrentHashMap<Wallpaper.Scene, Bitmap>()

/**
 * [scene] as the tile shows it: the whole sphere painted four tiles wide, the middle quarter of its width cut out (ahead,
 * 90 degrees wide) and 45 degrees of its height, from 35 above the horizon to 10 below, where each scene's sky is.
 * Brightened, as the phone's screen in daylight is far brighter than the glasses' dark-adapted view; the scenes
 * themselves stay as they are.
 */
private fun preview(scene: Wallpaper.Scene, w: Int, h: Int): Bitmap {
    PREVIEWS[scene]?.let { return it }
    val fw = w * 4
    // The sphere's 180 degrees of height four tiles tall too, so the tile's 90 by 45 degrees keep their shape.
    val fh = h * 4
    val full = Wallpaper.Painter.argb(scene, fw, fh)
    val out = IntArray(w * h)
    for (j in 0 until h) for (i in 0 until w) {
        val p = full[(j + fh * 55 / 180) * fw + i + fw * 3 / 8]
        out[j * w + i] = (0xFF shl 24) or (lift(p shr 16 and 255) shl 16) or (lift(p shr 8 and 255) shl 8) or lift(p and 255)
    }
    return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888).also { PREVIEWS[scene] = it }
}

/** The preview's lift: dark values brought up (a gentle curve), bright ones kept. */
private fun lift(c: Int): Int = (255f * Math.pow(c / 255.0, 0.62).toFloat()).roundToInt().coerceIn(0, 255)

private fun sceneName(scene: Wallpaper.Scene): Int = when (scene) {
    Wallpaper.Scene.NONE -> R.string.wallpaper_none
    Wallpaper.Scene.NIGHT_SKY -> R.string.wallpaper_night_sky
    Wallpaper.Scene.DUSK -> R.string.wallpaper_dusk
    Wallpaper.Scene.AURORA -> R.string.wallpaper_aurora
    Wallpaper.Scene.STUDIO -> R.string.wallpaper_studio
    Wallpaper.Scene.PICTURE -> R.string.wallpaper_picture
}
