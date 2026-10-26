package com.example

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import com.example.manager.AppTheme
import com.example.ui.screens.BrowserScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SplashScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.BrowserViewModel

private const val TAG = "MainActivity"

enum class NavigationRoute {
    SPLASH, BROWSER, SETTINGS
}

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: BrowserViewModel
    private var mFilePathCallback: ValueCallback<Array<Uri>>? = null
    private var mCameraPhotoUri: Uri? = null

    // Register file-picking callback for WebView media and document uploads
    private val mFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (mFilePathCallback != null) {
            val rawResults: Array<Uri>? = if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data
                if (data != null && (data.data != null || data.clipData != null)) {
                    val dataString = data.dataString
                    val clipData = data.clipData
                    if (clipData != null) {
                        Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                    } else if (dataString != null) {
                        arrayOf(Uri.parse(dataString))
                    } else {
                        data.data?.let { arrayOf(it) }
                    }
                } else {
                    mCameraPhotoUri?.let { arrayOf(it) }
                }
            } else {
                null
            }
            val safeResults = rawResults
                ?.filter { uri ->
                    com.example.manager.SecurityGuardEngine.isSafeFileChooserResultUri(
                        context = this,
                        uri = uri,
                        allowedCameraUri = mCameraPhotoUri
                    )
                }
                ?.takeIf { it.isNotEmpty() }
                ?.toTypedArray()
            mFilePathCallback?.onReceiveValue(safeResults)
            mFilePathCallback = null
            mCameraPhotoUri = null
        }
    }

    private fun createTempImageFileUri(): Uri? {
        return try {
            val baseDir = externalCacheDir ?: cacheDir
            // Sesuaikan langsung dengan path="camera" di res/xml/file_paths.xml
            val relativeCameraDir = java.io.File(baseDir, "camera").apply {
                if (!exists()) mkdirs()
            }
            val imageFile = java.io.File.createTempFile(
                "captured_image_",
                ".jpg",
                relativeCameraDir
            )
            androidx.core.content.FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                imageFile
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create temp image file", e)
            null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.example.manager.FrameMetricsTelemetrySync.syncDisplayFrameState(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Install AdBlock ServiceWorker interceptor before WebView content starts
        com.example.manager.AdBlockServiceWorker.install()

        // Construct standard Android ViewModel
        viewModel = ViewModelProvider(this)[BrowserViewModel::class.java]

        // Start TabPersistenceService to track if the application is swiped away/killed from recent task manager
        try {
            startService(android.content.Intent(this, com.example.manager.TabPersistenceService::class.java))
        } catch (e: SecurityException) {
            com.example.manager.SecurityLogger.logPermissionDenied("MainActivity", "TabPersistenceService")
        } catch (e: Exception) {
            com.example.manager.SecurityLogger.logServiceError("MainActivity", "TabPersistenceService", e)
        }

        setContent {
            // Read settings theme choices dynamically
            val currentTheme by viewModel.appTheme.collectAsState()
            val allowScreenRecording by viewModel.allowScreenRecording.collectAsState()
            val systemDark = isSystemInDarkTheme()
            
            LaunchedEffect(allowScreenRecording) {
                if (allowScreenRecording) {
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            val isDarkTheme = when (currentTheme) {
                AppTheme.SYSTEM -> systemDark
                AppTheme.LIGHT -> false
                AppTheme.DARK -> true
            }

            MyApplicationTheme(darkTheme = isDarkTheme) {
                Scaffold(modifier = Modifier.fillMaxSize()) { _ ->
                    // Custom state based navigation for bulletproof WebView lifecycle management
                    var currentRoute by remember { mutableStateOf(NavigationRoute.SPLASH) }

                    when (currentRoute) {
                        NavigationRoute.SPLASH -> {
                            SplashScreen(
                                onTimeout = { currentRoute = NavigationRoute.BROWSER }
                            )
                        }
                        NavigationRoute.BROWSER, NavigationRoute.SETTINGS -> {
                            androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
                                BrowserScreen(
                                    viewModel = viewModel,
                                    onFileChooser = { filePathCallback, fileChooserParams ->
                                        handleWebViewFileChooser(filePathCallback, fileChooserParams)
                                    },
                                    onNavigateToSettings = { currentRoute = NavigationRoute.SETTINGS }
                                )
                                
                                if (currentRoute == NavigationRoute.SETTINGS) {
                                    SettingsScreen(
                                        viewModel = viewModel,
                                        onBack = { currentRoute = NavigationRoute.BROWSER }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        viewModel.setPipMode(isInPictureInPictureMode)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (viewModel.isFullscreen.value) {
            // Optional: check if API >= 26
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                try {
                    enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().build())
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to enter PIP onUserLeaveHint: ${e.message}")
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (!viewModel.backgroundPlaybackEnabled.value && !viewModel.isPipMode.value) {
            viewModel.setAppPaused(true)
        }
    }

    override fun onStop() {
        super.onStop()
        if (!viewModel.backgroundPlaybackEnabled.value && !viewModel.isPipMode.value) {
            viewModel.setAppPaused(true)
        }
    }

    override fun onResume() {
        super.onResume()
        com.example.manager.FrameMetricsTelemetrySync.syncDisplayFrameState(this)
        viewModel.setAppPaused(false)
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::viewModel.isInitialized &&
            (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
             level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        ) {
            viewModel.requestHibernateInactiveTabs()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (::viewModel.isInitialized) {
            viewModel.requestHibernateInactiveTabs()
        }
    }

    /**
     * Launch official files and media content pickers on behalf of WebView intents securely
     */
    private fun handleWebViewFileChooser(
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: WebChromeClient.FileChooserParams?
    ) {
        // Cancel previous pending callback if any
        mFilePathCallback?.onReceiveValue(null)
        mFilePathCallback = filePathCallback

        try {
            val acceptTypes = fileChooserParams?.acceptTypes ?: emptyArray()
            val isMultiple = fileChooserParams?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE

            val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                if (isMultiple) {
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                if (acceptTypes.size > 1) {
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, acceptTypes)
                } else if (acceptTypes.isNotEmpty() && acceptTypes[0].isNotEmpty()) {
                    type = acceptTypes[0]
                } else {
                    type = "*/*"
                }
            }

            val cameraIntents = mutableListOf<Intent>()
            val photoUri = createTempImageFileUri()
            if (photoUri != null) {
                mCameraPhotoUri = photoUri
                val captureIntent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    putExtra(android.provider.MediaStore.EXTRA_OUTPUT, photoUri)
                    addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
                cameraIntents.add(captureIntent)
            }

            val chooserIntent = Intent(Intent.ACTION_CHOOSER).apply {
                putExtra(Intent.EXTRA_INTENT, contentIntent)
                putExtra(Intent.EXTRA_TITLE, "Upload File")
                if (cameraIntents.isNotEmpty()) {
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, cameraIntents.toTypedArray())
                }
            }

            mFilePickerLauncher.launch(chooserIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch device file picker activity manager.", e)
            mFilePathCallback?.onReceiveValue(null)
            mFilePathCallback = null
            mCameraPhotoUri = null
        }
    }

    override fun onDestroy() {
        // Clear cookies only when the Activity is genuinely finishing (not on rotation / config change)
        try {
            if (isFinishing && !isChangingConfigurations) {
                val settingsManager = com.example.manager.SettingsManager(this)
                if (settingsManager.clearCookiesOnExit) {
                    android.webkit.CookieManager.getInstance().removeAllCookies { success ->
                        if (!success) {
                            com.example.manager.SecurityLogger.logCookieClearFailed(
                                "MainActivity",
                                Exception("CookieManager.removeAllCookies failed")
                            )
                        }
                    }
                    android.webkit.CookieManager.getInstance().flush() // Ensure written to disk
                    android.webkit.WebStorage.getInstance().deleteAllData() // Clear cache/storage too for safety
                }
            }
        } catch (e: Exception) {
            com.example.manager.SecurityLogger.logSecurityEvent(
                type = com.example.manager.SecurityLogger.EventType.COOKIE_CLEAR_FAILED,
                component = "MainActivity",
                message = "Failed to clear cookies on exit",
                exception = e
            )
        }
        
        // Safe releases of callbacks on lifecycle disposal
        mFilePathCallback?.onReceiveValue(null)
        mFilePathCallback = null
        super.onDestroy()
    }
}
