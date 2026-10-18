package screens.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.panpf.zoomimage.ZoomImage
import com.github.panpf.zoomimage.compose.rememberZoomState
import film_simulator.shared.generated.resources.Res
import film_simulator.shared.generated.resources.ic_reset_image_24
import film_simulator.shared.generated.resources.ic_settings_24
import film_simulator.shared.generated.resources.ic_visibility_24
import io.github.yahiaangelo.filmsimulator.frame.FrameColors
import io.github.yahiaangelo.filmsimulator.frame.FrameStyle
import io.github.yahiaangelo.filmsimulator.frame.InkColors
import io.github.yahiaangelo.filmsimulator.frame.PenFont
import io.github.yahiaangelo.filmsimulator.view.CenteredSettingsSlider
import io.github.yahiaangelo.filmsimulator.view.SettingsSlider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource

// Palette from the "Puszka" mockup.
private val Bg = Color(0xFF14241F)
private val Tray = Color(0xFF1D332C)
private val Accent = Color(0xFFF2C230)
private val OnBg = Color(0xFFF0EDE2)
private val Muted = Color(0xFF9DB3A8)
private val Line = Color(0xFF335246)
private val OnAccent = Color(0xFF1A1400)

enum class EditorTool(val label: String) { LOOK("Look"), ADJUST("Dostosuj"), FRAME("Ramka"), CAPTION("Podpis") }

class EditorActions(
    val onImport: () -> Unit,
    val onSettings: () -> Unit,
    val onReset: () -> Unit,
    val vm: HomeScreenModel,
)

@Composable
fun EditorLayout(state: HomeUiState, actions: EditorActions, modifier: Modifier = Modifier) {
    var tool by remember { mutableStateOf(EditorTool.LOOK) }
    val vm = actions.vm
    val hasPhoto = state.activeSlot >= 0

    Column(modifier = modifier.fillMaxSize().background(Bg)) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Film Simulator", color = OnBg, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            IconButton(onClick = actions.onReset) {
                Icon(painterResource(Res.drawable.ic_reset_image_24), "Resetuj", tint = OnBg)
            }
            IconButton(onClick = actions.onSettings) {
                Icon(painterResource(Res.drawable.ic_settings_24), "Ustawienia", tint = OnBg)
            }
            Spacer(Modifier.width(4.dp))
            Pill(
                text = "Zapisz",
                selected = true,
                onClick = { if (hasPhoto) vm.showExportSheet(true) },
            )
        }

        // Preview
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val bytes = state.previewImage
            if (bytes == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Brak zdjęć", color = Muted, fontSize = 16.sp)
                    Spacer(Modifier.height(12.dp))
                    Pill("Importuj zdjęcia", true, actions.onImport)
                }
            } else {
                var painter by remember { mutableStateOf<BitmapPainter?>(null) }
                LaunchedEffect(bytes) {
                    painter = withContext(Dispatchers.Default) { BitmapPainter(bytes.decodeToImageBitmap()) }
                }
                val zoomState = rememberZoomState()
                painter?.let { p ->
                    ZoomImage(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                        zoomState = zoomState,
                        painter = p,
                        contentDescription = null,
                        scrollBar = null,
                    )
                }
                IconButton(
                    onClick = { vm.showOriginalImage(state.showAdjustments) },
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                ) {
                    Icon(
                        painterResource(Res.drawable.ic_visibility_24), "Pokaż oryginał",
                        tint = if (state.showAdjustments) OnBg else Accent,
                    )
                }
            }
        }

        // Filmstrip + copy/paste
        if (state.slots.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(start = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    state.slots.forEach { slot ->
                        SlotThumb(slot, slot.id == state.activeSlot) { vm.selectSlot(slot.id) }
                    }
                    Box(
                        modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp))
                            .border(1.5.dp, Line, RoundedCornerShape(8.dp))
                            .clickable(onClick = actions.onImport),
                        contentAlignment = Alignment.Center,
                    ) { Text("+", color = Muted, fontSize = 24.sp) }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Pill("Kopiuj", false, vm::copySettings)
                if (state.hasClipboard) {
                    Pill("Wklej", false) { vm.pasteSettings(false) }
                    if (state.slots.size > 1) Pill("Wklej do wszystkich", false) { vm.pasteSettings(true) }
                }
                Pill("Usuń zdjęcie", false, vm::removeActiveSlot)
            }
        }

        // Tool tabs
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).background(Tray),
        ) {
            EditorTool.entries.forEach { t ->
                Box(
                    modifier = Modifier.weight(1f).height(48.dp).clickable { tool = t },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        t.label,
                        color = if (t == tool) Accent else OnBg,
                        fontSize = 16.sp,
                        fontWeight = if (t == tool) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }

        // Tray
        Column(
            modifier = Modifier.fillMaxWidth().height(200.dp).background(Tray)
                .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (!hasPhoto) {
                Text("Zaimportuj zdjęcia, aby zacząć.", color = Muted)
            } else when (tool) {
                EditorTool.LOOK -> LookTray(state, vm)
                EditorTool.ADJUST -> AdjustTray(state)
                EditorTool.FRAME -> FrameTray(state, vm)
                EditorTool.CAPTION -> CaptionTray(state, vm)
            }
        }
    }

    if (state.exportSheetVisible) ExportSheet(state, vm)
}

@Composable
private fun SlotThumb(slot: SlotInfo, active: Boolean, onClick: () -> Unit) {
    var painter by remember(slot.thumb) { mutableStateOf<BitmapPainter?>(null) }
    LaunchedEffect(slot.thumb) {
        val t = slot.thumb ?: return@LaunchedEffect
        painter = withContext(Dispatchers.Default) { BitmapPainter(t.decodeToImageBitmap()) }
    }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier.size(52.dp).clip(shape)
            .border(if (active) 2.5.dp else 1.dp, if (active) Accent else Line, shape)
            .background(Tray).clickable(onClick = onClick),
    ) {
        painter?.let {
            Image(it, null, modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        }
        if (slot.edited) {
            Box(Modifier.align(Alignment.BottomStart).padding(4.dp).size(8.dp).clip(CircleShape).background(Accent))
        }
    }
}

@Composable
private fun LookTray(state: HomeUiState, vm: HomeScreenModel) {
    Pill(
        text = state.selectedFilm?.name ?: "Wybierz film",
        selected = state.selectedFilm != null,
        onClick = vm::showFilmLutsBottomSheet,
    )
    if (state.selectedFilm != null) {
        SettingsSlider(
            name = "LUT Intensity",
            value = state.imageAdjustments.lutIntensity,
            onValueChange = vm::adjustLutIntensity,
            range = 0f..200f,
            steps = 200,
        )
    }
}

@Composable
private fun AdjustTray(state: HomeUiState) {
    // Handlers come from state (wired in HomeScreen).
    val a = state.imageAdjustments
    CenteredSettingsSlider(name = "Exposure", value = a.exposure, steps = 1, range = -20f..20f, onValueChange = state.onExposureChange)
    CenteredSettingsSlider(name = "Temperature", value = a.temperature, steps = 1, range = -20f..20f, onValueChange = state.onTemperatureChange)
    CenteredSettingsSlider(name = "Contrast", value = a.contrast, steps = 1, range = -20f..20f, onValueChange = state.onContrastChange)
    CenteredSettingsSlider(name = "Shadows", value = a.shadows, steps = 1, range = -20f..20f, onValueChange = state.onShadowsChange)
    CenteredSettingsSlider(name = "Highlights", value = a.highlights, steps = 1, range = -20f..20f, onValueChange = state.onHighlightsChange)
    CenteredSettingsSlider(name = "Saturation", value = a.saturation, steps = 1, range = -20f..20f, onValueChange = state.onSaturationChange)
    SettingsSlider(name = "Grain", value = a.grain, steps = 20, range = 0f..10f, onValueChange = state.onGrainChange)
    SettingsSlider(name = "Chromatic Aberration", value = a.chromaticAberration, steps = 10, range = 0f..10f, onValueChange = state.onChromaticAberrationChange)
}

@Composable
private fun FrameTray(state: HomeUiState, vm: HomeScreenModel) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FrameStyle.entries.forEach { s ->
            Pill(s.label, state.frame.style == s) { vm.setFrameStyle(s) }
        }
    }
    if (state.frame.style != FrameStyle.NONE) {
        Spacer(Modifier.height(8.dp))
        Text("Grubość", color = Muted, fontSize = 14.sp)
        Slider(
            value = state.frame.thickness,
            onValueChange = vm::setFrameThickness,
            valueRange = 0.5f..2f,
            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Line),
        )
        if (state.frame.style != FrameStyle.FILM) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Kolor", color = Muted, fontSize = 14.sp)
                FrameColors.forEach { c -> Swatch(c, state.frame.color == c) { vm.setFrameColor(c) } }
            }
        }
    }
}

@Composable
private fun CaptionTray(state: HomeUiState, vm: HomeScreenModel) {
    OutlinedTextField(
        value = state.frame.caption,
        onValueChange = vm::setCaption,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text("Podpis, np. Złota godzina") },
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Atrament", color = Muted, fontSize = 14.sp)
        InkColors.forEach { c -> Swatch(c, state.frame.inkColor == c) { vm.setInkColor(c) } }
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PenFont.entries.forEach { f -> Pill(f.label, state.frame.penFont == f) { vm.setPenFont(f) } }
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Data na zdjęciu", color = OnBg)
        Switch(checked = state.frame.dateStamp, onCheckedChange = vm::setDateStamp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportSheet(state: HomeUiState, vm: HomeScreenModel) {
    var quality by remember { mutableStateOf(state.exportQuality.toFloat().coerceIn(60f, 100f)) }
    var size by remember { mutableStateOf(0) } // 0 = oryginał, 1 = 2048, 2 = 1080
    var all by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = { vm.showExportSheet(false) }, containerColor = Bg) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Eksport", color = OnBg, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Jakość JPG", color = Muted)
                Text("${quality.toInt()}%", color = Accent, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = quality, onValueChange = { quality = it }, valueRange = 60f..100f,
                colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Line),
            )
            Text("Rozmiar", color = Muted)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Oryginał", size == 0) { size = 0 }
                Pill("2048 px", size == 1) { size = 1 }
                Pill("1080 px", size == 2) { size = 2 }
            }
            if (state.slots.size > 1) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Wszystkie zdjęcia (${state.slots.size})", color = OnBg)
                    Switch(checked = all, onCheckedChange = { all = it })
                }
            }
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Pill(
                    text = if (all) "Zapisz ${state.slots.size} zdjęcia" else "Zapisz w galerii",
                    selected = true,
                    onClick = {
                        vm.exportImage(
                            ExportOptions(
                                quality = quality.toInt(),
                                maxDimension = when (size) { 1 -> 2048; 2 -> 1080; else -> null },
                                allPhotos = all,
                            ),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun Swatch(color: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(32.dp).clip(CircleShape).background(Color(color))
            .border(if (selected) 3.dp else 1.dp, if (selected) Accent else Line, CircleShape)
            .clickable(onClick = onClick),
    )
}

@Composable
private fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = Modifier.height(44.dp).clip(shape)
            .background(if (selected) Accent else Color.Transparent)
            .border(BorderStroke(1.5.dp, if (selected) Accent else Line), shape)
            .clickable(onClick = onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (selected) OnAccent else OnBg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}
