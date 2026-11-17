package com.agustin.sumi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.agustin.sumi.core.ui.theme.SumiTheme
import com.agustin.sumi.domain.model.LibraryTitle
import com.agustin.sumi.feature.library.LibraryRoute
import com.agustin.sumi.feature.library.LibraryRecoveryViewModel
import com.agustin.sumi.feature.library.LibraryViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var recovery: LibraryRecoveryViewModel

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
        setContent {
            SumiTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LibraryRoute(viewModel, recovery, sumi.libraryCoverSource)
                }
            }
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
