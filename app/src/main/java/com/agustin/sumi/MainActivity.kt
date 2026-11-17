package com.agustin.sumi

import android.content.ActivityNotFoundException
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.agustin.sumi.core.ui.theme.SumiTheme
import com.agustin.sumi.domain.model.CbzSourceReference
import com.agustin.sumi.domain.model.LibraryEntryId
import com.agustin.sumi.domain.model.LibraryTitle
import com.agustin.sumi.feature.library.LibraryRoute
import com.agustin.sumi.feature.library.LibraryRecoveryViewModel
import com.agustin.sumi.feature.library.LibraryViewModel
import com.agustin.sumi.feature.library.OpenCbzDocument
import com.agustin.sumi.feature.library.OpenLibraryTree
import com.agustin.sumi.feature.reader.ReaderPageDraw
import com.agustin.sumi.feature.reader.ReaderRecoverySelection
import com.agustin.sumi.feature.reader.ReaderScreen
import com.agustin.sumi.feature.reader.ReaderViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var recovery: LibraryRecoveryViewModel
    private val reader by lazy {
        val sumi = application as SumiApplication
        ViewModelProvider(this, sumi.readerViewModelFactory)["main-reader", ReaderViewModel::class.java]
    }
    private val readerDocument = registerForActivityResult(OpenCbzDocument) { uri ->
        reader.finishRecoveryPicker(uri?.let { ReaderRecoverySelection.Document(CbzSourceReference(it.toString())) })
    }
    private val readerTree = registerForActivityResult(OpenLibraryTree) { tree ->
        reader.finishRecoveryPicker(tree?.let { ReaderRecoverySelection.Tree(it.source, it.offersPersistentRead) })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val sumi = application as SumiApplication
        val viewModel = ViewModelProvider(this, viewModelFactory {
            initializer {
                LibraryViewModel(
                    sumi.importIntoLibrary,
                    sumi.importLibraryBatch,
                    LibraryTitle(getString(R.string.library_untitled)),
                    sumi.libraryTrees,
                )
            }
        })[LibraryViewModel::class.java]
        recovery = ViewModelProvider(this, viewModelFactory {
            initializer {
                LibraryRecoveryViewModel(
                    sumi.libraryRepository, sumi.libraryDocumentAccess, sumi.importIntoLibrary, sumi.libraryMaintenance,
                    sumi.librarySortPreference,
                    sumi.recoverLibraryTree,
                )
            }
        })[LibraryRecoveryViewModel::class.java]
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val drawPage: ReaderPageDraw = { viewport, zoom, pan, scope ->
            sumi.readerRenderer.withFrame(viewport, zoom) { bitmap, size ->
                val left = (viewport.width - size.width) / 2f + pan.x
                val top = (viewport.height - size.height) / 2f + pan.y
                val destination = RectF(left, top, left + size.width, top + size.height)
                scope.drawIntoCanvas { canvas -> canvas.nativeCanvas.drawBitmap(bitmap, null, destination, paint) }
                true
            } ?: false
        }
        setContent {
            SumiTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
                    val libraryState = rememberSaveableStateHolder()
                    val id = selectedId
                    if (id == null) {
                        libraryState.SaveableStateProvider("library") {
                            LibraryRoute(viewModel, recovery, sumi.libraryCoverSource) { selectedId = it.value }
                        }
                    } else {
                        val returnToLibrary = {
                            recovery.refresh()
                            selectedId = null
                        }
                        BackHandler {
                            reader.close()
                            returnToLibrary()
                        }
                        ReaderScreen(
                            LibraryEntryId(id), reader, drawPage, onClose = returnToLibrary,
                            onReselectDocument = { launchReaderPicker(tree = false) },
                            onReselectTree = { launchReaderPicker(tree = true) },
                        )
                    }
                }
            }
        }
    }

    private fun launchReaderPicker(tree: Boolean) {
        if (!reader.beginRecoveryPicker(tree)) return
        try {
            if (tree) readerTree.launch(Unit) else readerDocument.launch(Unit)
        } catch (_: ActivityNotFoundException) {
            reader.finishRecoveryPicker(null)
            Toast.makeText(this, R.string.cbz_picker_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        val sumi = application as SumiApplication
        sumi.restoreCoverBudget()
        lifecycleScope.launch { sumi.libraryMaintenance.reconcile() }
        recovery.refresh()
    }
}
