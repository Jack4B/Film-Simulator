package screens.home

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.plusmobileapps.konnectivity.Konnectivity
import io.github.vinceglb.filekit.core.PlatformFile
import io.github.vinceglb.filekit.core.extension
import io.github.yahiaangelo.filmsimulator.FavoriteLut
import io.github.yahiaangelo.filmsimulator.FilmLut
import io.github.yahiaangelo.filmsimulator.data.source.FilmRepository
import io.github.yahiaangelo.filmsimulator.data.source.SettingsRepository
import io.github.yahiaangelo.filmsimulator.data.source.toFavoriteLut
import io.github.yahiaangelo.filmsimulator.frame.FrameRenderer
import io.github.yahiaangelo.filmsimulator.frame.FrameSpec
import io.github.yahiaangelo.filmsimulator.frame.FrameStyle
import io.github.yahiaangelo.filmsimulator.image.ImageAdjustments
import io.github.yahiaangelo.filmsimulator.image.SkiaImageProcessor
import io.github.yahiaangelo.filmsimulator.lut.LutDownloadManager
import io.github.yahiaangelo.filmsimulator.screens.settings.DefaultPickerType
import io.github.yahiaangelo.filmsimulator.util.AppContext
import io.github.yahiaangelo.filmsimulator.util.convertImageToJpeg
import io.github.yahiaangelo.filmsimulator.util.fixImageOrientation
import io.github.yahiaangelo.filmsimulator.util.supportedImageExtensions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.dsl.module
import util.EDITED_IMAGE_FILE_NAME
import util.IMAGE_FILE_NAME
import util.ORIGINAL_IMAGE_FILE_PREFIX
import util.THUMBNAILS_DIR
import util.createDirectory
import util.readImageFile
import util.saveImageFile
import util.saveImageToGallery
import kotlin.coroutines.cancellation.CancellationException

val homeScreenModule = module {
    factory { HomeScreenModel(get(), get(), get()) }
}

/** Debounce window before re-rendering the preview after a slider change. */
private const val PREVIEW_DEBOUNCE_MS = 25L

/**
 * Source formats that the Skia pipeline can't decode at full quality on its own —
 * they need to be transcoded to JPEG first via the platform converter. For RAW
 * formats this also ensures we read the embedded full-size preview rather than
 * the tiny thumbnail BitmapFactory returns by default (Pixel DNGs etc.).
 */
private val formatsNeedingConversion = setOf(
    "heic", "heif",
    "dng", "raw", "cr2", "nef", "orf", "arw", "raf", "pef", "sr2", "rw2",
)

/** JPEG quality used for the preview pipeline — lower than export to keep encode fast. */
private const val PREVIEW_QUALITY = 80

/** Thumbnail entry for the filmstrip at the bottom of the editor. */
data class SlotInfo(val id: Int, val thumb: ByteArray?, val edited: Boolean)

/** Options chosen on the export sheet. */
data class ExportOptions(
    val quality: Int = 90,
    /** Longest edge in px, or null for the original resolution. */
    val maxDimension: Int? = null,
    val allPhotos: Boolean = false,
)

/**
 * UiState for the Main Screen
 */
data class HomeUiState(
    val previewImage: ByteArray? = null,
    val previewToken: Long = 0L,
    val selectedFilm: FilmLut? = null,
    val isLoading: Boolean = false,
    val loadingMessage: String = "",
    /**
     * 0..1 progress for the loading dialog. When null, the dialog falls back
     * to its indeterminate spinner; when set, it renders a linear progress
     * bar driven by this value. Only the export path populates this — other
     * loading states (refresh, LUT load) leave it null.
     */
    val loadingProgress: Float? = null,
    val showBottomSheet: BottomSheetState = BottomSheetState.HIDDEN,
    val defaultPickerType: DefaultPickerType = DefaultPickerType.IMAGES,
    val filmLuts: List<FilmLut> = emptyList(),
    val favoriteLuts: List<FavoriteLut> = emptyList(),
    val userMessage: String? = null,
    val showAdjustments: Boolean = true,
    val imageAdjustments: ImageAdjustments = ImageAdjustments(),
    val onRefresh: () -> Unit = {},
    val onImageChooseClick: () -> Unit = {},
    val onFilmBoxClick: () -> Unit = {},
    val onDismissRequest: () -> Unit = {},
    val onItemClick: (film: FilmLut) -> Unit = {},
    val onVisibilityClick: (Boolean) -> Unit = {},
    val onImageResetClick: () -> Unit = {},
    val onSettingsClick: () -> Unit = {},
    val onImageExportClick: () -> Unit = {},
    val snackbarMessageShown: () -> Unit = {},
    val onAddFavoriteClick: (FilmLut) -> Unit = {},
    val onRemoveFavoriteClick: (FilmLut) -> Unit = {},
    // Individual adjustment handlers
    val onContrastChange: (Float) -> Unit = {},
    val onShadowsChange: (Float) -> Unit = {},
    val onHighlightsChange: (Float) -> Unit = {},
    val onSaturationChange: (Float) -> Unit = {},
    val onTemperatureChange: (Float) -> Unit = {},
    val onExposureChange: (Float) -> Unit = {},
    val onGrainChange: (Float) -> Unit = {},
    val onChromaticAberrationChange: (Float) -> Unit = {},
    val onLutIntensityChange: (Float) -> Unit = {},
    val showDownloadDialog: Boolean = false,
    val showDownloadProgress: Boolean = false,
    val downloadProgress: Pair<Int, Int> = 0 to 0,
    val onDownloadLutsConfirm: () -> Unit = {},
    val onDownloadLutsDismiss: () -> Unit = {},
    val filmThumbnails: Map<String, String> = emptyMap(),
    val frame: FrameSpec = FrameSpec(),
    val slots: List<SlotInfo> = emptyList(),
    val activeSlot: Int = -1,
    val hasClipboard: Boolean = false,
    val exportSheetVisible: Boolean = false,
    val exportQuality: Int = 90,
)

enum class BottomSheetState {
    COLLAPSED, EXPANDED, HIDDEN
}

/**
 * ViewModel for the Main Screen.
 *
 * Preview pipeline: the screen model holds the decoded source image bytes and the
 * currently selected LUT bytes. Every change to either (or to [ImageAdjustments])
 * triggers a debounced re-render through [SkiaImageProcessor] at a preview-friendly
 * resolution; the resulting JPEG bytes are pushed into [HomeUiState.previewImage]
 * for Coil to display. Export reuses the same processor at full resolution.
 */
data class HomeScreenModel(
    val repository: FilmRepository,
    val settingsRepository: SettingsRepository,
    val lutDownloadManager: LutDownloadManager,
) : ScreenModel {

    private val _uiState: MutableStateFlow<HomeUiState> = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState
    private val konnectivity = Konnectivity()

    private val processor = SkiaImageProcessor()
    private val previewTrigger: MutableStateFlow<PreviewRequest?> = MutableStateFlow(null)
    private var previewVersion = 0L
    private var sourceBytes: ByteArray? = null
    private var currentLutBytes: ByteArray? = null
    private var currentAdjustments: ImageAdjustments = ImageAdjustments()
    private var currentThumbnailJob: Job? = null
    /** Filename in app cache holding the user's unmodified original picked image, with original extension. */
    private var originalImageFileName: String? = null

    private class PhotoSlot(
        val id: Int,
        val source: ByteArray,
        val originalName: String?,
        var adjustments: ImageAdjustments = ImageAdjustments(),
        var film: FilmLut? = null,
        var lutBytes: ByteArray? = null,
        var frame: FrameSpec = FrameSpec(),
        var thumb: ByteArray? = null,
    )

    private class Clip(
        val adjustments: ImageAdjustments,
        val film: FilmLut?,
        val lutBytes: ByteArray?,
        val frame: FrameSpec,
    )

    private val slots = mutableListOf<PhotoSlot>()
    private var activeSlotId = -1
    private var nextSlotId = 0
    private var clipboard: Clip? = null
    private var currentFrame: FrameSpec = FrameSpec()
    private var lastBase: Pair<PreviewRequest, ByteArray>? = null

    private data class PreviewRequest(
        val sourceKey: Int,
        val lutKey: Int,
        val adjustments: ImageAdjustments,
        val showOriginal: Boolean,
        val frame: FrameSpec,
    )

    init {
        refresh()
        addSettingsListeners()
        screenModelScope.launch {
            lutDownloadManager.uiState.collect { downloadState ->
                updateUiState {
                    it.copy(
                        showDownloadDialog = downloadState.showDownloadDialog,
                        showDownloadProgress = downloadState.showDownloadProgress,
                        downloadProgress = downloadState.downloadProgress,
                    )
                }
            }
        }
        startPreviewLoop()
    }


    private fun updateUiState(update: (HomeUiState) -> HomeUiState) {
        _uiState.value = update(_uiState.value)
    }


    fun refresh() {
        screenModelScope.launch {
            try {
                updateUiState { it.copy(isLoading = true, loadingMessage = "Refreshing data...") }
                if (konnectivity.isConnected) {
                    repository.refresh()
                }
                val newFilmList = repository.getFilmsStream().first()
                val newFavoriteList = repository.getFavoriteFilmsStream().first()
                updateUiState {
                    it.copy(
                        filmLuts = newFilmList,
                        favoriteLuts = newFavoriteList,
                        defaultPickerType = settingsRepository.getSettings().defaultPicker,
                        userMessage = "Data refreshed successfully.",
                    )
                }
            } catch (e: Exception) {
                updateUiState { it.copy(userMessage = "Error refreshing data: ${e.message}") }
            } finally {
                updateUiState { it.copy(isLoading = false) }
            }
        }
    }


    fun dismissDownloadDialog() {
        lutDownloadManager.dismissDownloadDialog()
    }

    fun confirmDownloadLuts() {
        lutDownloadManager.confirmDownloadLuts(screenModelScope) { success, message ->
            message?.let {
                updateUiState { it.copy(userMessage = message) }
            }
        }
    }

    fun adjustContrast(value: Float) = updateImageAdjustment { it.copy(contrast = value) }
    fun adjustShadows(value: Float) = updateImageAdjustment { it.copy(shadows = value) }
    fun adjustHighlights(value: Float) = updateImageAdjustment { it.copy(highlights = value) }
    fun adjustSaturation(value: Float) = updateImageAdjustment { it.copy(saturation = value) }
    fun adjustTemperature(value: Float) = updateImageAdjustment { it.copy(temperature = value) }
    fun adjustExposure(value: Float) = updateImageAdjustment { it.copy(exposure = value) }
    fun addGrain(value: Float) = updateImageAdjustment { it.copy(grain = value) }
    fun addChromaticAberration(value: Float) = updateImageAdjustment { it.copy(chromaticAberration = value) }
    fun adjustLutIntensity(value: Float) = updateImageAdjustment { it.copy(lutIntensity = value) }

    private fun updateImageAdjustment(update: (ImageAdjustments) -> ImageAdjustments) {
        currentAdjustments = update(currentAdjustments)
        updateUiState { it.copy(imageAdjustments = currentAdjustments) }
        requestPreview()
    }

    fun selectFilmLut(filmLut: FilmLut) {
        screenModelScope.launch {
            try {
                updateUiState { it.copy(isLoading = true, loadingMessage = "Loading Film LUT...") }
                val lutBytes = withContext(Dispatchers.IO) { repository.getLutBytes(filmLut) }
                if (lutBytes == null) {
                    updateUiState { it.copy(userMessage = "Error loading LUT.") }
                    return@launch
                }
                currentLutBytes = lutBytes
                updateUiState { it.copy(selectedFilm = filmLut) }
                requestPreview()
            } catch (e: Exception) {
                updateUiState { it.copy(userMessage = "Error applying LUT: ${e.message}") }
            } finally {
                updateUiState { it.copy(isLoading = false, showBottomSheet = BottomSheetState.COLLAPSED) }
            }
        }
    }


    fun generateThumbnailsForGroup(category: String) {
        currentThumbnailJob?.cancel()

        if (sourceBytes == null) return
        currentThumbnailJob = screenModelScope.launch {
            try {
                createDirectory(THUMBNAILS_DIR)

                val films = repository.getFilms(false).filter { it.category == category }
                val thumbnails = _uiState.value.filmThumbnails.toMutableMap()

                for (film in films) {
                    if (thumbnails.containsKey(film.lut_name) || !isActive) continue

                    val thumbnailPath = repository.generateLutThumbnail(film, IMAGE_FILE_NAME)
                    thumbnails[film.lut_name] = thumbnailPath
                    updateUiState { it.copy(filmThumbnails = thumbnails.toMap()) }
                }

                updateUiState { it.copy(filmThumbnails = thumbnails.toMap()) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            } finally {
                updateUiState { it.copy(isLoading = false) }
            }
        }
    }

    private fun generateFilmThumbnails() {
        if (sourceBytes == null) return
        screenModelScope.launch {
            try {
                createDirectory(THUMBNAILS_DIR)

                val favoriteFilms = repository.getFavoriteFilms()
                val favoriteLutNames = favoriteFilms.map { it.name }
                val filmsToProcess = repository.getFilms(false)
                    .filter { favoriteLutNames.contains(it.name) }

                val thumbnails = mutableMapOf<String, String>()

                for (film in filmsToProcess) {
                    val thumbnailPath = repository.generateLutThumbnail(film, IMAGE_FILE_NAME)
                    thumbnails[film.lut_name] = thumbnailPath
                }

                updateUiState { it.copy(filmThumbnails = thumbnails.toMap()) }
            } catch (e: Exception) {
                updateUiState { it.copy(userMessage = "Error generating thumbnails: ${e.message}") }
            }
        }
    }

    fun onImagePickerResult(file: PlatformFile?) {
        file?.let { onImagesPickerResult(listOf(it)) }
    }

    fun onImagesPickerResult(files: List<PlatformFile>?) {
        val picked = files.orEmpty().filter { supportedImageExtensions.contains(it.extension.lowercase()) }
        if (picked.isEmpty()) {
            if (!files.isNullOrEmpty()) updateUiState { it.copy(userMessage = "Unsupported image type.") }
            return
        }
        screenModelScope.launch {
            try {
                updateUiState {
                    it.copy(isLoading = true, loadingMessage = "Loading photos...", loadingProgress = null)
                }
                saveActiveToSlot()
                var firstNewId = -1
                for (platformFile in picked) {
                    val id = nextSlotId++
                    val ext = platformFile.extension.lowercase()
                    val originalBytes = platformFile.readBytes()
                    // Stash the raw original (with original extension) so export can read its
                    // EXIF / detect format when "original format" export is selected.
                    val originalName = "${ORIGINAL_IMAGE_FILE_PREFIX}_$id.$ext"
                    saveImageFile(originalName, originalBytes)

                    saveImageFile(IMAGE_FILE_NAME, originalBytes)
                    if (formatsNeedingConversion.contains(ext)) {
                        convertImageToJpeg(IMAGE_FILE_NAME)
                    }
                    fixImageOrientation(image = IMAGE_FILE_NAME)
                    val bytes = withContext(Dispatchers.IO) { readImageFile(IMAGE_FILE_NAME) }
                    val thumb = withContext(Dispatchers.Default) {
                        processor.process(
                            imageBytes = bytes,
                            lutBytes = null,
                            adjustments = ImageAdjustments(),
                            maxDimension = 240,
                            quality = 70,
                        )
                    }
                    slots.add(PhotoSlot(id = id, source = bytes, originalName = originalName, thumb = thumb))
                    if (firstNewId < 0) firstNewId = id
                }
                val firstLoad = _uiState.value.filmThumbnails.isEmpty()
                activateSlot(firstNewId)
                if (firstLoad) generateFilmThumbnails()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                updateUiState { it.copy(userMessage = "Error loading image: ${e.message}") }
            } finally {
                updateUiState { it.copy(isLoading = false) }
            }
        }
    }

    // ---- Slots (series of photos) -------------------------------------------------

    private fun saveActiveToSlot() {
        val slot = slots.firstOrNull { it.id == activeSlotId } ?: return
        slot.adjustments = currentAdjustments
        slot.film = _uiState.value.selectedFilm
        slot.lutBytes = currentLutBytes
        slot.frame = currentFrame
    }

    private fun publishSlots() {
        updateUiState {
            it.copy(
                slots = slots.map { s ->
                    SlotInfo(
                        id = s.id,
                        thumb = s.thumb,
                        edited = s.adjustments.hasAdjustments() || s.film != null || s.frame.isActive,
                    )
                },
                activeSlot = activeSlotId,
            )
        }
    }

    private suspend fun activateSlot(id: Int) {
        val slot = slots.firstOrNull { it.id == id } ?: return
        activeSlotId = id
        sourceBytes = slot.source
        originalImageFileName = slot.originalName
        currentLutBytes = slot.lutBytes
        currentAdjustments = slot.adjustments
        currentFrame = slot.frame
        lastBase = null
        processor.clearCache()
        withContext(Dispatchers.IO) { saveImageFile(IMAGE_FILE_NAME, slot.source) }
        updateUiState {
            it.copy(
                selectedFilm = slot.film,
                imageAdjustments = slot.adjustments,
                frame = slot.frame,
                showAdjustments = true,
            )
        }
        publishSlots()
        requestPreview()
    }

    fun selectSlot(id: Int) {
        if (id == activeSlotId) return
        screenModelScope.launch {
            saveActiveToSlot()
            activateSlot(id)
        }
    }

    fun removeActiveSlot() {
        val idx = slots.indexOfFirst { it.id == activeSlotId }
        if (idx < 0) return
        slots.removeAt(idx)
        if (slots.isEmpty()) {
            activeSlotId = -1
            sourceBytes = null
            currentLutBytes = null
            currentAdjustments = ImageAdjustments()
            currentFrame = FrameSpec()
            lastBase = null
            updateUiState {
                it.copy(
                    previewImage = null,
                    selectedFilm = null,
                    imageAdjustments = ImageAdjustments(),
                    frame = FrameSpec(),
                )
            }
            publishSlots()
        } else {
            val next = slots[minOf(idx, slots.size - 1)].id
            screenModelScope.launch { activateSlot(next) }
        }
    }

    // ---- Copy / paste of settings -------------------------------------------------

    fun copySettings() {
        clipboard = Clip(currentAdjustments, _uiState.value.selectedFilm, currentLutBytes, currentFrame)
        updateUiState { it.copy(hasClipboard = true, userMessage = "Skopiowano ustawienia.") }
    }

    fun pasteSettings(toAll: Boolean) {
        val clip = clipboard ?: return
        screenModelScope.launch {
            saveActiveToSlot()
            val targets = if (toAll) slots.toList() else slots.filter { it.id == activeSlotId }
            for (slot in targets) {
                slot.adjustments = clip.adjustments
                slot.film = clip.film
                slot.lutBytes = clip.lutBytes
                // Caption is per photo — keep it, take the rest of the frame.
                slot.frame = clip.frame.copy(caption = slot.frame.caption)
            }
            activateSlot(activeSlotId)
            updateUiState {
                it.copy(userMessage = if (toAll) "Wklejono do wszystkich zdjęć." else "Wklejono ustawienia.")
            }
        }
    }

    // ---- Frame & caption ----------------------------------------------------------

    private fun updateFrame(update: (FrameSpec) -> FrameSpec) {
        currentFrame = update(currentFrame)
        updateUiState { it.copy(frame = currentFrame) }
        requestPreview()
    }

    fun setFrameStyle(style: FrameStyle) = updateFrame { it.copy(style = style) }
    fun setFrameThickness(v: Float) = updateFrame { it.copy(thickness = v) }
    fun setFrameColor(c: Int) = updateFrame { it.copy(color = c) }
    fun setCaption(text: String) = updateFrame { it.copy(caption = text.take(60)) }
    fun setInkColor(c: Int) = updateFrame { it.copy(inkColor = c) }
    fun setPenFont(f: io.github.yahiaangelo.filmsimulator.frame.PenFont) = updateFrame { it.copy(penFont = f) }
    fun setDateStamp(on: Boolean) = updateFrame { it.copy(dateStamp = on) }

    // ---- Export sheet -------------------------------------------------------------

    fun showExportSheet(show: Boolean) {
        updateUiState {
            it.copy(
                exportSheetVisible = show,
                exportQuality = settingsRepository.getSettings().exportQuality,
            )
        }
    }

    fun showFilmLutsBottomSheet() {
        updateUiState { it.copy(showBottomSheet = BottomSheetState.EXPANDED) }
    }

    fun dismissFilmLutBottomSheet() {
        updateUiState { it.copy(showBottomSheet = BottomSheetState.HIDDEN) }
    }

    fun snackbarMessageShown() {
        updateUiState { it.copy(userMessage = null) }
    }

    fun showOriginalImage(show: Boolean) {
        updateUiState { it.copy(showAdjustments = !show) }
        requestPreview()
    }

    fun resetImage() {
        currentAdjustments = ImageAdjustments()
        currentLutBytes = null
        currentFrame = FrameSpec()
        updateUiState {
            it.copy(
                selectedFilm = null,
                imageAdjustments = ImageAdjustments(),
                frame = FrameSpec(),
            )
        }
        requestPreview()
    }

    fun exportImage(options: ExportOptions = ExportOptions(quality = settingsRepository.getSettings().exportQuality)) {
        if (sourceBytes == null) {
            updateUiState { it.copy(userMessage = "Please choose an image first.") }
            return
        }
        screenModelScope.launch {
            try {
                saveActiveToSlot()
                updateUiState { it.copy(exportSheetVisible = false) }
                val targets = if (options.allPhotos) slots.toList() else slots.filter { it.id == activeSlotId }
                val total = targets.size
                for ((index, slot) in targets.withIndex()) {
                    val prefix = if (total > 1) "(${index + 1}/$total) " else ""
                    updateUiState {
                        it.copy(
                            isLoading = true,
                            loadingMessage = prefix + "Processing image with effects...",
                            loadingProgress = 0f,
                        )
                    }
                    val processed = processor.process(
                        imageBytes = slot.source,
                        lutBytes = slot.lutBytes,
                        adjustments = slot.adjustments,
                        maxDimension = options.maxDimension,
                        quality = options.quality,
                        grainSeed = (kotlin.random.Random.nextFloat() * 1000f),
                        highQualityGrain = true,
                        onProgress = { p -> updateUiState { it.copy(loadingProgress = p) } },
                    ) ?: throw IllegalStateException("Failed to process image")

                    val exportedBytes = withContext(Dispatchers.Default) {
                        FrameRenderer.render(processed, slot.frame, options.quality)
                    }

                    withContext(Dispatchers.IO) {
                        saveImageFile(EDITED_IMAGE_FILE_NAME, exportedBytes)
                    }
                    updateUiState {
                        it.copy(loadingMessage = prefix + "Saving to gallery...", loadingProgress = null)
                    }
                    saveImageToGallery(
                        image = EDITED_IMAGE_FILE_NAME,
                        appContext = AppContext,
                        format = settingsRepository.getSettings().exportFormat,
                        originalImage = slot.originalName,
                    )
                }
                updateUiState {
                    it.copy(
                        userMessage = if (total > 1) "Zapisano $total zdjęć w galerii." else "Image exported successfully with all effects applied.",
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                updateUiState { it.copy(userMessage = "Error exporting image: ${e.message}") }
            } finally {
                updateUiState { it.copy(isLoading = false, loadingProgress = null) }
            }
        }
    }

    fun addFavoriteFilm(filmLut: FilmLut) {
        screenModelScope.launch {
            try {
                val newFavoriteLutList = repository.addFavoriteFilm(filmLut.toFavoriteLut())
                updateUiState { it.copy(favoriteLuts = newFavoriteLutList, userMessage = "Added to favorites.") }
            } catch (e: Exception) {
                updateUiState { it.copy(userMessage = "Error adding to favorites: ${e.message}") }
            }
        }
    }

    fun removeFavoriteFilm(filmLut: FilmLut) {
        screenModelScope.launch {
            try {
                val newFavoriteLutList = repository.removeFavoriteFilm(filmLut.name)
                updateUiState { it.copy(favoriteLuts = newFavoriteLutList, userMessage = "Removed from favorites.") }
            } catch (e: Exception) {
                updateUiState { it.copy(userMessage = "Error removing from favorites: ${e.message}") }
            }
        }
    }

    private fun addSettingsListeners() {
        settingsRepository.getSettings().defaultPickerListener { defaultPicker ->
            updateUiState { it.copy(defaultPickerType = defaultPicker) }
        }
    }

    private fun requestPreview() {
        val source = sourceBytes ?: return
        previewTrigger.value = PreviewRequest(
            sourceKey = source.contentHashCode(),
            lutKey = currentLutBytes?.contentHashCode() ?: 0,
            adjustments = currentAdjustments,
            showOriginal = !_uiState.value.showAdjustments,
            frame = currentFrame,
        )
    }

    @OptIn(FlowPreview::class)
    private fun startPreviewLoop() {
        screenModelScope.launch {
            previewTrigger
                .filterNotNull()
                .debounce(PREVIEW_DEBOUNCE_MS)
                .collectLatest { request ->
                    val source = sourceBytes ?: return@collectLatest
                    val baseKey = request.copy(frame = FrameSpec())
                    val cached = lastBase?.takeIf { it.first == baseKey }?.second
                    val bytes = withContext(Dispatchers.Default) {
                        val base = cached ?: run {
                            if (request.showOriginal) {
                                processor.process(
                                    imageBytes = source,
                                    lutBytes = null,
                                    adjustments = ImageAdjustments(),
                                    maxDimension = SkiaImageProcessor.PREVIEW_MAX_DIMENSION,
                                    quality = PREVIEW_QUALITY,
                                )
                            } else {
                                processor.process(
                                    imageBytes = source,
                                    lutBytes = currentLutBytes,
                                    adjustments = currentAdjustments,
                                    maxDimension = SkiaImageProcessor.PREVIEW_MAX_DIMENSION,
                                    quality = PREVIEW_QUALITY,
                                )
                            }
                        }
                        if (base == null) null
                        else {
                            lastBase = baseKey to base
                            if (request.showOriginal) base
                            else FrameRenderer.render(base, request.frame, PREVIEW_QUALITY)
                        }
                    } ?: return@collectLatest
                    val token = ++previewVersion
                    slots.firstOrNull { it.id == activeSlotId }?.thumb = bytes
                    updateUiState { it.copy(previewImage = bytes, previewToken = token) }
                    publishSlots()
                }
        }
    }
}
