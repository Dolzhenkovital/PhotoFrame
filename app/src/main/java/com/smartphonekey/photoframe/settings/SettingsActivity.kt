package com.smartphonekey.photoframe.settings

import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.smartphonekey.photoframe.PhotoFrameApp
import com.smartphonekey.photoframe.R
import com.smartphonekey.photoframe.source.local.PhotoScanner

class SettingsActivity : AppCompatActivity() {

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) onFolderPicked(uri)
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

    fun launchFolderPicker() {
        pickFolder.launch(null)
    }

    private fun onFolderPicked(uri: Uri) {
        // Keep read access across reboots — this is the whole permission
        // story of the SAF source (local-photos skill).
        contentResolver.takePersistableUriPermission(
            uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        (application as PhotoFrameApp).prefs.folderUri = uri.toString()
        currentFragment()?.updateFolderSummary()
        rescan()
    }

    fun rescan() {
        val app = application as PhotoFrameApp
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
        }
    }
}
