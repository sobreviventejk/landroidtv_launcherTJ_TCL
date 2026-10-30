package com.tvlauncher

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class UpdatePromptActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val version = intent.getStringExtra(EXTRA_VERSION) ?: run {
            finish()
            return
        }
        val apkPath = intent.getStringExtra(EXTRA_APK_PATH) ?: run {
            finish()
            return
        }
        val apkFile = File(apkPath)
        if (!apkFile.exists()) {
            finish()
            return
        }
        // Defense in depth: only offer the install when the file is this app, same signature.
        if (!AppUpdateManager.isTrustedUpdate(this, apkFile)) {
            Toast.makeText(this, R.string.update_invalid_release, Toast.LENGTH_LONG).show()
            apkFile.delete()
            finish()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.update_available_title)
            .setMessage(getString(R.string.update_available_message, version))
            .setPositiveButton(R.string.update_install) { _, _ ->
                if (!AppUpdateManager.canInstallPackages(this)) {
                    AppUpdateManager.openInstallPermissionSettings(this)
                } else if (!AppUpdateManager.installDownloadedApk(this, apkFile)) {
                    Toast.makeText(this, R.string.update_failed, Toast.LENGTH_LONG).show()
                }
                finish()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    companion object {
        const val EXTRA_VERSION = "com.tvlauncher.EXTRA_UPDATE_VERSION"
        const val EXTRA_APK_PATH = "com.tvlauncher.EXTRA_APK_PATH"
    }
}
