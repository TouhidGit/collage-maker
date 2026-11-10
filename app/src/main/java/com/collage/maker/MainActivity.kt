package com.collage.maker

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    companion object {
        const val MAX_PHOTOS = 100
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var emptyState: View
    private lateinit var editorArea: View
    private lateinit var bottomPanel: View
    private lateinit var progress: ProgressBar
    private lateinit var collageView: CollageView
    private lateinit var tabs: TabLayout
    private lateinit var panels: List<View>
    private lateinit var categoryChips: ChipGroup
    private lateinit var ratioChips: ChipGroup
    private lateinit var layoutList: RecyclerView
    private lateinit var colorRow: LinearLayout
    private lateinit var spacingSlider: Slider
    private lateinit var cornerSlider: Slider
    private lateinit var photoInfo: TextView
    private lateinit var btnReplace: MaterialButton
    private lateinit var btnRemove: MaterialButton
    private lateinit var adapter: LayoutAdapter

    private val bitmaps = ArrayList<Bitmap>()
    private val states = mutableListOf<CellState>()
    private var imageOrder: List<Int> = emptyList()
    private var current: LayoutDef = Layouts.all.first()
    private var category = Layouts.CAT_ALL
    private var ratioW = 1
    private var ratioH = 1
    private var spacing = 12f
    private var corner = 0f
    private var bg = Color.WHITE
    private var seed = 1L
    private var shapeText = "LOVE"
    private var busy = false

    private val ratios = listOf(
        "1:1" to Pair(1, 1), "4:5" to Pair(4, 5), "3:4" to Pair(3, 4), "2:3" to Pair(2, 3),
        "9:16" to Pair(9, 16), "A4" to Pair(210, 297), "4:3" to Pair(4, 3), "3:2" to Pair(3, 2),
        "16:9" to Pair(16, 9)
    )

    private val colors = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFF5EFE6.toInt(), 0xFFF8D7DA.toInt(),
        0xFFE6DDF8.toInt(), 0xFFD8F3E8.toInt(), 0xFFD6EAF8.toInt(), 0xFFFFF3B0.toInt(),
        0xFFFF8A80.toInt(), 0xFF2B2B2B.toInt(), 0xFF1F2A44.toInt(), 0xFF6A4CFF.toInt()
    )

    private val pickPhotos = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) loadImages(uris, append = bitmaps.isNotEmpty())
    }

    private val pickOne = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) replaceSelected(uri)
    }

    private val requestWrite = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) doSave() else toast("Storage permission is needed to save")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        emptyState = findViewById(R.id.emptyState)
        editorArea = findViewById(R.id.editorArea)
        bottomPanel = findViewById(R.id.bottomPanel)
        progress = findViewById(R.id.progress)
        collageView = findViewById(R.id.collageView)
        tabs = findViewById(R.id.tabs)
        categoryChips = findViewById(R.id.categoryChips)
        ratioChips = findViewById(R.id.ratioChips)
        layoutList = findViewById(R.id.layoutList)
        colorRow = findViewById(R.id.colorRow)
        spacingSlider = findViewById(R.id.spacingSlider)
        cornerSlider = findViewById(R.id.cornerSlider)
        photoInfo = findViewById(R.id.photoInfo)
        btnReplace = findViewById(R.id.btnReplace)
        btnRemove = findViewById(R.id.btnRemove)
        panels = listOf<View>(
            findViewById<View>(R.id.panelLayout),
            findViewById<View>(R.id.panelRatio),
            findViewById<View>(R.id.panelStyle),
            findViewById<View>(R.id.panelPhotos)
        )

        toolbar.inflateMenu(R.menu.editor)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_shuffle -> {
                    shuffle(); true
                }
                R.id.action_save -> {
                    save(); true
                }
                R.id.action_share -> {
                    share(); true
                }
                else -> false
            }
        }

        findViewById<View>(R.id.btnChoose).setOnClickListener { launchPicker() }
        collageView.onSelectionChanged = { updatePhotoPanel() }

        setupTabs()
        setupLayouts()
        setupRatios()
        setupStyle()
        setupPhotos()
        showEditor(false)
        handleIncomingIntent(intent)
    }

    // ------------------------------------------------------------------ setup

    private fun setupTabs() {
        listOf("Layout", "Ratio", "Style", "Photos").forEach { tabs.addTab(tabs.newTab().setText(it)) }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                showPanel(tab.position)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        showPanel(0)
    }

    private fun showPanel(index: Int) {
        panels.forEachIndexed { i, v -> v.visibility = if (i == index) View.VISIBLE else View.GONE }
    }

    private fun makeChip(label: String, checked: Boolean, onClick: (Chip) -> Unit): Chip =
        Chip(this).apply {
            text = label
            isCheckable = true
            isCheckedIconVisible = false
            isChecked = checked
            setOnClickListener { onClick(this) }
        }

    private fun checkOnly(group: ChipGroup, chip: Chip) {
        for (i in 0 until group.childCount) {
            val c = group.getChildAt(i) as? Chip ?: continue
            c.isChecked = c == chip
        }
    }

    private fun setupLayouts() {
        Layouts.categories.forEach { cat ->
            categoryChips.addView(makeChip(cat, cat == category) { chip ->
                category = cat
                checkOnly(categoryChips, chip)
                refreshLayoutList()
            })
        }
        adapter = LayoutAdapter { def -> onLayoutPicked(def) }
        adapter.selectedId = current.id
        layoutList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        layoutList.adapter = adapter
        refreshLayoutList()
    }

    private fun refreshLayoutList() {
        adapter.setParams(bitmaps.size.coerceAtLeast(1), ratioW, ratioH, shapeText)
        adapter.setItems(Layouts.all.filter { category == Layouts.CAT_ALL || it.category == category })
    }

    private fun setupRatios() {
        ratios.forEach { (label, r) ->
            ratioChips.addView(makeChip(label, r.first == ratioW && r.second == ratioH) { chip ->
                ratioW = r.first
                ratioH = r.second
                checkOnly(ratioChips, chip)
                refreshLayoutList()
                rebuild(false)
            })
        }
    }

    private fun setupStyle() {
        spacingSlider.value = spacing
        cornerSlider.value = corner
        spacingSlider.addOnChangeListener { _, value, _ ->
            spacing = value
            rebuild(false)
        }
        cornerSlider.addOnChangeListener { _, value, _ ->
            corner = value
            rebuild(false)
        }
        refreshColorRow()
    }

    private fun refreshColorRow() {
        colorRow.removeAllViews()
        val brand = ContextCompat.getColor(this, R.color.brand)
        for (c in colors) {
            val v = View(this)
            val lp = LinearLayout.LayoutParams(dp(38), dp(38))
            lp.setMargins(dp(5), dp(4), dp(5), dp(4))
            v.layoutParams = lp
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c)
                if (c == bg) setStroke(dp(3), brand) else setStroke(dp(1), 0xFFCCCCCC.toInt())
            }
            v.setOnClickListener {
                bg = c
                collageView.bgColor = c
                collageView.invalidate()
                refreshColorRow()
            }
            colorRow.addView(v)
        }
    }

    private fun setupPhotos() {
        findViewById<View>(R.id.btnAdd).setOnClickListener { launchPicker() }
        btnReplace.setOnClickListener {
            if (collageView.selected >= 0) {
                pickOne.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }
        btnRemove.setOnClickListener { removeSelected() }
        findViewById<View>(R.id.btnClear).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Start a new collage?")
                .setMessage("All photos will be removed from the current collage.")
                .setPositiveButton("Start over") { _, _ -> clearAll() }
                .setNegativeButton("Cancel", null)
                .show()
        }
        updatePhotoPanel()
    }

    private fun updatePhotoPanel() {
        val hasSel = collageView.selected >= 0
        btnReplace.isEnabled = hasSel
        btnRemove.isEnabled = hasSel
        photoInfo.text = getString(R.string.photo_help, bitmaps.size)
    }

    // ---------------------------------------------------------------- actions

    private fun launchPicker() {
        if (bitmaps.size >= MAX_PHOTOS) {
            toast("Maximum $MAX_PHOTOS photos")
            return
        }
        pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun onLayoutPicked(def: LayoutDef) {
        val wasSame = def.id == current.id
        current = def
        adapter.select(def.id)
        if (!wasSame) rebuild(true)
        if (def.id == "shape_text") askText()
    }

    private fun askText() {
        val input = EditText(this).apply {
            setText(shapeText)
            setSelection(shapeText.length)
            hint = "e.g. LOVE, 50, MOM"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 3
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(22), dp(8), dp(22), 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Text or number for the collage")
            .setMessage("Photos will fill the letters. Use a new line for a second row.")
            .setView(box)
            .setPositiveButton("Apply") { _, _ ->
                shapeText = input.text.toString().trim().ifEmpty { "LOVE" }.take(24)
                refreshLayoutList()
                rebuild(true)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun shuffle() {
        if (bitmaps.isEmpty()) return
        seed = Random.nextLong()
        imageOrder = bitmaps.indices.shuffled()
        rebuild(true)
    }

    private fun removeSelected() {
        val sel = collageView.selected
        val st = states.getOrNull(sel) ?: return
        if (bitmaps.isEmpty()) return
        val idx = st.imageIndex.mod(bitmaps.size)
        bitmaps.removeAt(idx)
        imageOrder = emptyList()
        if (bitmaps.isEmpty()) {
            clearAll()
            return
        }
        refreshLayoutList()
        rebuild(true)
    }

    private fun replaceSelected(uri: Uri) {
        val st = states.getOrNull(collageView.selected) ?: return
        if (bitmaps.isEmpty()) return
        val idx = st.imageIndex.mod(bitmaps.size)
        val maxSide = maxSideFor(bitmaps.size)
        setBusy(true)
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { ImageLoader.load(this@MainActivity, uri, maxSide) }
            setBusy(false)
            if (bmp == null) {
                toast("Couldn't open that image")
                return@launch
            }
            if (idx < bitmaps.size) bitmaps[idx] = bmp
            st.scale = 1f
            st.dx = 0f
            st.dy = 0f
            collageView.invalidate()
        }
    }

    private fun clearAll() {
        bitmaps.clear()
        states.clear()
        imageOrder = emptyList()
        collageView.selected = -1
        collageView.collage = null
        showEditor(false)
    }

    private fun maxSideFor(total: Int): Int =
        (3200f / sqrt(total.coerceAtLeast(1).toFloat())).toInt().coerceIn(400, 1600)

    private fun loadImages(uris: List<Uri>, append: Boolean) {
        if (busy) return
        val room = MAX_PHOTOS - (if (append) bitmaps.size else 0)
        val list = uris.take(room.coerceAtLeast(0))
        if (list.size < uris.size) toast("Only $MAX_PHOTOS photos can be used")
        if (list.isEmpty()) return
        val total = (if (append) bitmaps.size else 0) + list.size
        val maxSide = maxSideFor(total)
        setBusy(true)
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                list.mapNotNull { ImageLoader.load(this@MainActivity, it, maxSide) }
            }
            setBusy(false)
            if (loaded.isEmpty()) {
                toast("Couldn't open those images")
                return@launch
            }
            if (loaded.size < list.size) toast("${list.size - loaded.size} image(s) couldn't be opened")
            if (!append) bitmaps.clear()
            bitmaps.addAll(loaded)
            imageOrder = emptyList()
            showEditor(true)
            refreshLayoutList()
            rebuild(true)
        }
    }

    private fun handleIncomingIntent(i: Intent?) {
        if (i == null) return
        val uris = ArrayList<Uri>()
        when (i.action) {
            Intent.ACTION_SEND -> {
                IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris.add(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris.addAll(it) }
            }
        }
        if (uris.isNotEmpty()) loadImages(uris, append = false)
    }

    // ------------------------------------------------------------- rendering

    private fun rebuild(reset: Boolean) {
        if (bitmaps.isEmpty()) return
        val size = LayoutEngine.designSize(ratioW, ratioH)
        val layout = LayoutEngine.prepare(current, bitmaps.size, size.first, size.second, spacing, corner, seed, shapeText)
        if (reset || states.size != layout.cells.size) {
            val n = bitmaps.size
            if (imageOrder.size != n) imageOrder = (0 until n).toList()
            states.clear()
            for (i in layout.cells.indices) states.add(CellState(imageOrder[i % n]))
            collageView.selected = -1
        }
        collageView.images = bitmaps
        collageView.states = states
        collageView.bgColor = bg
        collageView.collage = layout
        updatePhotoPanel()
    }

    private fun showEditor(show: Boolean) {
        emptyState.visibility = if (show) View.GONE else View.VISIBLE
        editorArea.visibility = if (show) View.VISIBLE else View.GONE
        bottomPanel.visibility = if (show) View.VISIBLE else View.GONE
        toolbar.menu.setGroupVisible(R.id.group_editor, show)
    }

    private fun setBusy(b: Boolean) {
        busy = b
        progress.visibility = if (b) View.VISIBLE else View.GONE
    }

    // --------------------------------------------------------- save / share

    private fun save() {
        if (bitmaps.isEmpty() || busy) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestWrite.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        doSave()
    }

    private fun doSave() {
        val layout = collageView.collage ?: return
        val snapshot = states.map { it.copy() }
        val imgs = ArrayList(bitmaps)
        val color = bg
        setBusy(true)
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.Default) {
                try {
                    val bmp = Exporter.render(layout, imgs, snapshot, color)
                    val u = Exporter.saveToGallery(this@MainActivity, bmp)
                    bmp.recycle()
                    u
                } catch (e: Throwable) {
                    null
                }
            }
            setBusy(false)
            toast(if (uri != null) "Saved to Pictures/CollageMaker" else "Sorry, saving failed")
        }
    }

    private fun share() {
        if (bitmaps.isEmpty() || busy) return
        val layout = collageView.collage ?: return
        val snapshot = states.map { it.copy() }
        val imgs = ArrayList(bitmaps)
        val color = bg
        setBusy(true)
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.Default) {
                try {
                    val bmp = Exporter.render(layout, imgs, snapshot, color, 2400)
                    val u = Exporter.shareUri(this@MainActivity, bmp)
                    bmp.recycle()
                    u
                } catch (e: Throwable) {
                    null
                }
            }
            setBusy(false)
            if (uri == null) {
                toast("Sorry, sharing failed")
                return@launch
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("collage", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share collage"))
        }
    }

    // ---------------------------------------------------------------- utils

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
