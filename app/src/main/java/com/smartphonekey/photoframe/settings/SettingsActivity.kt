package com.smartphonekey.photoframe.settings

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.smartphonekey.photoframe.PhotoFrameApp
import com.smartphonekey.photoframe.R
import com.smartphonekey.photoframe.gphotos.GPhotosSyncManager
import com.smartphonekey.photoframe.gphotos.PickerUris
import com.smartphonekey.photoframe.gphotos.QrCode
import com.smartphonekey.photoframe.gphotos.oauth.LoopbackAuth
import com.smartphonekey.photoframe.source.local.MediaStoreScanner
import com.smartphonekey.photoframe.source.local.PhotoScanner

class SettingsActivity : AppCompatActivity() {

    private val app: PhotoFrameApp get() = application as PhotoFrameApp

    private var syncDialog: AlertDialog? = null
    private var syncListener: GPhotosSyncManager.Listener? = null

    /**
     * UI half of the auth callbacks only — the sync itself is started by
     * PhotoFrameApp's app-scoped onAccessToken hook, so nothing here is
     * load-bearing. Attached as a replaceable slot in onResume (a recreated
     * screen replaces the old one) and detached in onDestroy: a pending
     * browser-consent flow never retains a dead Activity.
     */
    private val authListener = object : LoopbackAuth.Listener {
        override fun onToken(accessToken: String) {
            // The app-scoped hook has already begun the sync.
            if (!isFinishing &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            ) {
                showSyncDialog()
            }
        }

        override fun onError(detail: String?, userCancelled: Boolean) {
            // Silent when the user backed out of consent themselves.
            if (userCancelled || detail == null) return
            if (isFinishing ||
                !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            ) {
                return
            }
            Toast.makeText(
                this@SettingsActivity,
                getString(R.string.gp_error_generic, detail),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) onFolderPicked(uri)
        }

    private val requestGalleryPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Result map alone is not the whole truth: on API 34+ a partial
            // grant reports "denied" for READ_MEDIA_IMAGES while the
            // selection permission IS granted — re-derive from checks.
            if (hasGalleryPermission()) {
                showBucketPicker()
            } else {
                Toast.makeText(
                    this, R.string.gallery_permission_denied, Toast.LENGTH_LONG
                ).show()
            }
        }

    private val cacheSizeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == Prefs.KEY_CACHE_SIZE) applyNewCacheCap()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    override fun onResume() {
        super.onResume()
        PreferenceManager.getDefaultSharedPreferences(this)
            .registerOnSharedPreferenceChangeListener(cacheSizeListener)
        app.gphotosAuth.attachUi(authListener)
        // Sync may still be running from a previous visit, or the auth
        // callback may have landed while we were not resumed — reattach.
        if (app.gphotosSync.isActive) showSyncDialog()
    }

    override fun onPause() {
        PreferenceManager.getDefaultSharedPreferences(this)
            .unregisterOnSharedPreferenceChangeListener(cacheSizeListener)
        super.onPause()
    }

    override fun onDestroy() {
        // Detach only our own observer: during recreation the new Activity
        // may already have attached its listener, which must survive.
        app.gphotosAuth.detachUi(authListener)
        syncListener?.let { app.gphotosSync.detach(it) }
        syncListener = null
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
        app.prefs.localSourceKind = Prefs.LocalSourceKind.SAF
        currentFragment()?.updateFolderSummary()
        rescan()
    }

    // --- Device gallery (MediaStore) fallback --------------------------------

    fun launchGalleryPicker() {
        if (hasGalleryPermission()) {
            showBucketPicker()
        } else {
            requestGalleryPermission.launch(galleryPermissions())
        }
    }

    /** The runtime permissions the current API level wants (local-photos skill). */
    private fun galleryPermissions(): Array<String> = when {
        // Requesting VISUAL_USER_SELECTED alongside lets the system offer
        // "Select photos" natively instead of the compatibility behavior.
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT >= 33 ->
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES) // modern phone path
        else ->
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE) // frame path (23–32)
    }

    private fun hasGalleryPermission(): Boolean {
        fun granted(permission: String) =
            ContextCompat.checkSelfPermission(this, permission) ==
                PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= 34 ->
                // Partial access counts: the user's selection is the source,
                // never nag for more (android-compat skill, API 34 row).
                granted(Manifest.permission.READ_MEDIA_IMAGES) ||
                    granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 ->
                granted(Manifest.permission.READ_MEDIA_IMAGES)
            else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    /** Lists MediaStore buckets and lets the user pick one (or all photos). */
    private fun showBucketPicker() {
        val appContext = applicationContext
        app.ioExecutor.execute {
            val result = try {
                MediaStoreScanner(appContext.contentResolver).listBuckets()
            } catch (e: SecurityException) {
                // Permission revoked since the last grant — say that, not
                // "no photos".
                runOnUiThread {
                    Toast.makeText(
                        appContext, R.string.gallery_permission_denied, Toast.LENGTH_LONG
                    ).show()
                }
                return@execute
            }
            val buckets = result.buckets
            runOnUiThread {
                // The gallery query can be slow on a big card; by the time it
                // lands the user may have backgrounded this screen — showing
                // a dialog on a stopped window throws BadTokenException. They
                // simply tap the preference again.
                if (isFinishing ||
                    !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                ) {
                    return@runOnUiThread
                }
                if (!result.complete) {
                    // Provider hiccup, not a small gallery — invite a retry.
                    Toast.makeText(this, R.string.scan_failed, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                if (buckets.isEmpty()) {
                    Toast.makeText(this, R.string.gallery_empty, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val labels = ArrayList<String>(buckets.size + 1)
                labels.add(getString(R.string.gallery_all_photos))
                buckets.forEach {
                    labels.add(getString(R.string.gallery_bucket_count, it.name, it.count))
                }
                AlertDialog.Builder(this)
                    .setTitle(R.string.pref_gallery_title)
                    .setItems(labels.toTypedArray()) { _, which ->
                        val bucket = if (which == 0) null else buckets[which - 1]
                        app.prefs.mediaBucketId = bucket?.id
                        app.prefs.mediaBucketName = bucket?.name
                        app.prefs.localSourceKind = Prefs.LocalSourceKind.MEDIA_STORE
                        currentFragment()?.updateFolderSummary()
                        rescan()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    fun rescan() {
        val kind = app.prefs.localSourceKind
        val folder = app.prefs.folderUri
        if (kind == Prefs.LocalSourceKind.SAF && folder == null) {
            Toast.makeText(this, R.string.pref_folder_summary_none, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, R.string.scanning, Toast.LENGTH_SHORT).show()
        val appContext = applicationContext
        val bucketId = app.prefs.mediaBucketId
        app.ioExecutor.execute {
            fun toastOnUi(text: String) = runOnUiThread {
                Toast.makeText(appContext, text, Toast.LENGTH_LONG).show()
            }

            val known = app.index.loadAll().associateBy { it.uri }
            val items = try {
                when (kind) {
                    Prefs.LocalSourceKind.SAF ->
                        PhotoScanner(appContext.contentResolver)
                            .scan(Uri.parse(folder), known)
                    Prefs.LocalSourceKind.MEDIA_STORE -> {
                        val result = MediaStoreScanner(appContext.contentResolver)
                            .scan(bucketId, known)
                        if (!result.complete) {
                            // Transient provider failure. The existing index
                            // stays — an aborted walk must not masquerade as
                            // a (nearly) empty gallery and erase photos.
                            toastOnUi(getString(R.string.scan_failed))
                            return@execute
                        }
                        result.items
                    }
                }
            } catch (e: SecurityException) {
                // Revoked storage permission: same rule, keep the index.
                toastOnUi(getString(R.string.gallery_permission_denied))
                return@execute
            }
            app.index.replaceAll(items)
            toastOnUi(getString(R.string.scan_done, items.size))
        }
    }

    // --- Google Photos source ------------------------------------------------

    fun addFromGooglePhotos() {
        if (app.gphotosSync.isActive) {
            showSyncDialog()
            return
        }
        if (!app.gphotosAuth.isConfigured) {
            Toast.makeText(this, R.string.gp_error_not_configured, Toast.LENGTH_LONG).show()
            return
        }
        if (!app.gphotosAuth.isSignedIn) {
            // First run goes through the browser — tell the user where to
            // look before the screen visibly "does nothing".
            Toast.makeText(this, R.string.gp_continue_in_browser, Toast.LENGTH_LONG).show()
        }
        app.gphotosAuth.requestAccessToken(interactive = true)
    }

    fun clearGooglePhotosCache() {
        // Clearing mid-sync would race the downloader: it keeps writing new
        // files right after the wipe, making the confirmed clear a no-op.
        if (app.gphotosSync.isActive) {
            Toast.makeText(this, R.string.gp_clear_while_sync, Toast.LENGTH_LONG).show()
            return
        }
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

    // --- Sync dialog ---------------------------------------------------------

    private fun showSyncDialog() {
        if (syncDialog?.isShowing == true) return
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_gphotos, null)
        val status = view.findViewById<TextView>(R.id.gp_status)
        val qr = view.findViewById<ImageView>(R.id.gp_qr)
        val progress = view.findViewById<ProgressBar>(R.id.gp_progress)
        val openLocal = view.findViewById<Button>(R.id.gp_open_local)

        lateinit var listener: GPhotosSyncManager.Listener
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.cat_gphotos)
            .setView(view)
            .setNegativeButton(R.string.gp_cancel) { _, _ -> app.gphotosSync.cancel() }
            .setOnDismissListener {
                app.gphotosSync.detach(listener)
                if (syncListener === listener) syncListener = null
                syncDialog = null
            }
            .create()
        syncDialog = dialog
        dialog.show()

        listener = GPhotosSyncManager.Listener { state ->
            if (syncDialog !== dialog) return@Listener
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
        syncListener = listener
        app.gphotosSync.attach(listener)
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
            findPreference<Preference>(KEY_PICK_GALLERY)?.setOnPreferenceClickListener {
                (requireActivity() as SettingsActivity).launchGalleryPicker()
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
            val active = prefs.localSourceKind
            val folderName = prefs.folderUri?.let { readableFolderName(it) }
            // The active source's summary shows the concrete selection; the
            // inactive one keeps its explainer so the user sees which of the
            // two local pickers currently feeds the slideshow.
            findPreference<Preference>(KEY_PICK_FOLDER)?.summary =
                if (active == Prefs.LocalSourceKind.SAF && folderName != null) {
                    folderName
                } else {
                    getString(R.string.pref_folder_summary_none)
                }
            findPreference<Preference>(KEY_PICK_GALLERY)?.summary =
                if (active == Prefs.LocalSourceKind.MEDIA_STORE) {
                    prefs.mediaBucketName ?: getString(R.string.gallery_all_photos)
                } else {
                    getString(R.string.pref_gallery_summary_none)
                }
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
            private const val KEY_PICK_GALLERY = "pick_gallery"
            private const val KEY_RESCAN = "rescan"
            private const val KEY_GP_ADD = "gp_add"
            private const val KEY_GP_CLEAR = "gp_clear"
        }
    }

    companion object {
        private const val QR_SIZE_PX = 512
    }
}
