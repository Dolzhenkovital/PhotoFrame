package com.smartphonekey.photoframe.settings

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.smartphonekey.photoframe.PhotoFrameApp
import com.smartphonekey.photoframe.R
import com.smartphonekey.photoframe.gphotos.GPhotosSyncManager
import com.smartphonekey.photoframe.gphotos.GoogleAuth
import com.smartphonekey.photoframe.gphotos.PickerUris
import com.smartphonekey.photoframe.gphotos.QrCode
import com.smartphonekey.photoframe.source.local.PhotoScanner
import kotlin.math.max

class SettingsActivity : AppCompatActivity() {

    private val app: PhotoFrameApp get() = application as PhotoFrameApp

    private lateinit var googleAuth: GoogleAuth
    private var syncDialog: AlertDialog? = null

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) onFolderPicked(uri)
        }

    private val cacheSizeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == Prefs.KEY_CACHE_SIZE) applyNewCacheCap()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        googleAuth = GoogleAuth(
            activity = this,
            onToken = { token ->
                app.gphotosSync.begin(token, targetDimension())
                showSyncDialog()
            },
            onError = { message ->
                // null = the user backed out of consent — stay silent.
                if (message != null) {
                    Toast.makeText(
                        this, getString(R.string.gp_error_generic, message), Toast.LENGTH_LONG
                    ).show()
                }
            },
        )
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
        // Sync may still be running from a previous visit — reattach.
        if (app.gphotosSync.isActive) showSyncDialog()
    }

    override fun onResume() {
        super.onResume()
        PreferenceManager.getDefaultSharedPreferences(this)
            .registerOnSharedPreferenceChangeListener(cacheSizeListener)
    }

    override fun onPause() {
        PreferenceManager.getDefaultSharedPreferences(this)
            .unregisterOnSharedPreferenceChangeListener(cacheSizeListener)
        super.onPause()
    }

    override fun onDestroy() {
        app.gphotosSync.detach()
        syncDialog?.dismiss()
        syncDialog = null
        super.onDestroy()
    }

    // --- Local folder source -------------------------------------------------

    fun launchFolderPicker() {
        pickFolder.launch(null)
    }

    private fun onFolderPicked(uri: Uri) {
        // Keep read access across reboots — this is the whole permission
        // story of the SAF source (local-photos skill).
        contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        app.prefs.folderUri = uri.toString()
        currentFragment()?.updateFolderSummary()
        rescan()
    }

    fun rescan() {
        val folder = app.prefs.folderUri
        if (folder == null) {
            Toast.makeText(this, R.string.pref_folder_summary_none, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, R.string.scanning, Toast.LENGTH_SHORT).show()
        val appContext = applicationContext
        app.ioExecutor.execute {
            val known = app.index.loadAll().associateBy { it.uri }
            val items = PhotoScanner(appContext.contentResolver)
                .scan(Uri.parse(folder), known)
            app.index.replaceAll(items)
            runOnUiThread {
                Toast.makeText(
                    appContext,
                    getString(R.string.scan_done, items.size),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // --- Google Photos source ------------------------------------------------

    fun addFromGooglePhotos() {
        if (app.gphotosSync.isActive) {
            showSyncDialog()
            return
        }
        if (!GoogleAuth.isPlayServicesAvailable(this)) {
            Toast.makeText(this, R.string.gp_error_no_gms, Toast.LENGTH_LONG).show()
            return
        }
        googleAuth.requestAccess()
    }

    fun clearGooglePhotosCache() {
        AlertDialog.Builder(this)
            .setTitle(R.string.pref_gp_clear_title)
            .setMessage(R.string.gp_clear_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val appContext = applicationContext
                app.ioExecutor.execute {
                    app.gphotosCache.clearAll()
                    runOnUiThread {
                        Toast.makeText(appContext, R.string.gp_cleared, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyNewCacheCap() {
        val appContext = applicationContext
        app.ioExecutor.execute {
            val tooSmall = app.gphotosCache.evictToCap(app.prefs.cacheSizeBytes)
            if (tooSmall) {
                runOnUiThread {
                    Toast.makeText(
                        appContext, R.string.gp_cache_too_small, Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /** Screen-fitting download size: enough pixels, never 12MP originals. */
    private fun targetDimension(): Int {
        val metrics = resources.displayMetrics
        return max(metrics.widthPixels, metrics.heightPixels).coerceIn(1280, 2048)
    }

    // --- Sync dialog ---------------------------------------------------------

    private fun showSyncDialog() {
        if (syncDialog?.isShowing == true) return
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_gphotos, null)
        val status = view.findViewById<TextView>(R.id.gp_status)
        val qr = view.findViewById<ImageView>(R.id.gp_qr)
        val progress = view.findViewById<ProgressBar>(R.id.gp_progress)
        val openLocal = view.findViewById<Button>(R.id.gp_open_local)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.cat_gphotos)
            .setView(view)
            .setNegativeButton(R.string.gp_cancel) { _, _ -> app.gphotosSync.cancel() }
            .setOnDismissListener {
                app.gphotosSync.detach()
                syncDialog = null
            }
            .create()
        syncDialog = dialog
        dialog.show()

        app.gphotosSync.attach { state ->
            if (syncDialog !== dialog) return@attach
            when (state) {
                is GPhotosSyncManager.State.Idle -> dialog.dismiss()

                is GPhotosSyncManager.State.Connecting -> {
                    status.setText(R.string.gp_state_connecting)
                    progress.isIndeterminate = true
                    progress.visibility = android.view.View.VISIBLE
                    qr.visibility = android.view.View.GONE
                    openLocal.visibility = android.view.View.GONE
                }

                is GPhotosSyncManager.State.WaitingForPick -> {
                    status.setText(R.string.gp_state_waiting)
                    progress.visibility = android.view.View.GONE
                    openLocal.visibility = android.view.View.VISIBLE
                    openLocal.setOnClickListener { openPickerLocally(state.pickerUri) }
                    app.ioExecutor.execute {
                        val bitmap = QrCode.encode(state.pickerUri, QR_SIZE_PX)
                        runOnUiThread {
                            if (syncDialog === dialog && bitmap != null) {
                                qr.setImageBitmap(bitmap)
                                qr.visibility = android.view.View.VISIBLE
                            }
                        }
                    }
                }

                is GPhotosSyncManager.State.Downloading -> {
                    status.text = getString(R.string.gp_state_downloading, state.done, state.total)
                    qr.visibility = android.view.View.GONE
                    openLocal.visibility = android.view.View.GONE
                    progress.visibility = android.view.View.VISIBLE
                    progress.isIndeterminate = state.total == 0
                    progress.max = maxOf(state.total, 1)
                    progress.progress = state.done
                }

                is GPhotosSyncManager.State.Finished -> {
                    val done = getString(R.string.gp_done, state.added)
                    status.text = if (state.capTooSmall) {
                        done + "\n" + getString(R.string.gp_cache_too_small)
                    } else {
                        done
                    }
                    progress.visibility = android.view.View.GONE
                    qr.visibility = android.view.View.GONE
                    openLocal.visibility = android.view.View.GONE
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                        ?.setText(R.string.gp_close)
                }

                is GPhotosSyncManager.State.Failed -> {
                    status.text = if (state.timedOut) {
                        getString(R.string.gp_timeout)
                    } else {
                        getString(R.string.gp_error_generic, state.detail ?: "?")
                    }
                    progress.visibility = android.view.View.GONE
                    qr.visibility = android.view.View.GONE
                    openLocal.visibility = android.view.View.GONE
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                        ?.setText(R.string.gp_close)
                }
            }
        }
    }

    private fun openPickerLocally(pickerUri: String) {
        // Parsing already rejects a non-Google pickerUri, but this is the
        // point where an arbitrary URI would become a launched intent, so it
        // is re-checked rather than assumed.
        if (!PickerUris.isTrustedPickerUri(pickerUri)) {
            Toast.makeText(this, R.string.gp_bad_picker_uri, Toast.LENGTH_LONG).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pickerUri)))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.gp_no_browser, Toast.LENGTH_LONG).show()
        }
    }

    private fun currentFragment(): SettingsFragment? =
        supportFragmentManager.findFragmentById(R.id.settings_container) as? SettingsFragment

    class SettingsFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            findPreference<Preference>(KEY_PICK_FOLDER)?.setOnPreferenceClickListener {
                (requireActivity() as SettingsActivity).launchFolderPicker()
                true
            }
            findPreference<Preference>(KEY_RESCAN)?.setOnPreferenceClickListener {
                (requireActivity() as SettingsActivity).rescan()
                true
            }
            findPreference<Preference>(KEY_GP_ADD)?.setOnPreferenceClickListener {
                (requireActivity() as SettingsActivity).addFromGooglePhotos()
                true
            }
            findPreference<Preference>(KEY_GP_CLEAR)?.setOnPreferenceClickListener {
                (requireActivity() as SettingsActivity).clearGooglePhotosCache()
                true
            }
            updateFolderSummary()
        }

        fun updateFolderSummary() {
            val prefs = (requireActivity().application as PhotoFrameApp).prefs
            val summary = prefs.folderUri?.let { readableFolderName(it) }
                ?: getString(R.string.pref_folder_summary_none)
            findPreference<Preference>(KEY_PICK_FOLDER)?.summary = summary
        }

        private fun readableFolderName(treeUri: String): String = try {
            // "primary:DCIM/Frame" → "DCIM/Frame" — good enough for a summary.
            val docId = DocumentsContract.getTreeDocumentId(Uri.parse(treeUri))
            docId.substringAfter(':').ifEmpty { docId }
        } catch (e: Exception) {
            treeUri
        }

        companion object {
            private const val KEY_PICK_FOLDER = "pick_folder"
            private const val KEY_RESCAN = "rescan"
            private const val KEY_GP_ADD = "gp_add"
            private const val KEY_GP_CLEAR = "gp_clear"
        }
    }

    companion object {
        private const val QR_SIZE_PX = 512
    }
}
