package com.githubupload.mt

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.githubupload.mt.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var securePrefs: SecurePrefs
    private val executor = Executors.newSingleThreadExecutor()
    private var uploadFuture: Future<*>? = null
    private var selectedDirUri: Uri? = null
    private var hasCurrentSessionDirectorySelection = false

    /** 内存中的完整日志，用于弹窗展示 */
    private val logBuffer = StringBuilder()
    private var logDialog: AlertDialog? = null
    private var logTextView: TextView? = null
    private var logScrollView: ScrollView? = null

    private val directoryPickerRequestCode = 2001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        securePrefs = SecurePrefs(this)
        loadConfig()

        binding.btnSelectDir.setOnClickListener { selectDirectory() }
        binding.btnSaveConfig.setOnClickListener { saveConfig() }
        binding.btnUpload.setOnClickListener { startUpload() }
        binding.btnLog.setOnClickListener { showLogDialog() }

        refreshPermissionState()
        log("应用已启动")
        log("选择目录后会递归上传全部子目录和文件")
        log("上传引擎使用 GitHub REST API，不依赖 JGit")
        log("文件过多时会自动分批次上传，Blob 创建已并行加速")
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
    }

    private fun loadConfig() {
        binding.etUsername.setText(prefs.getString(KEY_USERNAME, ""))
        binding.etToken.setText(securePrefs.getToken())
        binding.etRepoUrl.setText(prefs.getString(KEY_REPO_URL, ""))
        binding.etLocalPath.setText(prefs.getString(KEY_LOCAL_PATH, ""))
        binding.etBranch.setText(prefs.getString(KEY_BRANCH, "main"))
        binding.switchForcePush.isChecked = prefs.getBoolean(KEY_FORCE_PUSH, false)

        selectedDirUri = prefs.getString(KEY_LOCAL_URI, "")
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?.takeIf { uri -> contentResolver.persistedUriPermissions.any { it.uri == uri } }
    }

    private fun saveConfig() {
        try {
            prefs.edit()
                .putString(KEY_USERNAME, binding.etUsername.text?.toString()?.trim().orEmpty())
                .putString(KEY_REPO_URL, binding.etRepoUrl.text?.toString()?.trim().orEmpty())
                .putString(KEY_LOCAL_PATH, binding.etLocalPath.text?.toString()?.trim().orEmpty())
                .putString(KEY_LOCAL_URI, selectedDirUri?.toString().orEmpty())
                .putString(KEY_BRANCH, binding.etBranch.text?.toString()?.trim().orEmpty().ifBlank { "main" })
                .putBoolean(KEY_FORCE_PUSH, binding.switchForcePush.isChecked)
                .apply()
            securePrefs.putToken(binding.etToken.text?.toString()?.trim().orEmpty())
            log("配置已保存（令牌使用 Android Keystore 加密保存）")
            Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            log("保存配置失败：${e.message ?: e.javaClass.simpleName}")
            Toast.makeText(this, "保存配置失败", Toast.LENGTH_LONG).show()
        }
    }

    private fun selectDirectory() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        startActivityForResult(intent, directoryPickerRequestCode)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != directoryPickerRequestCode || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        selectedDirUri = uri
        hasCurrentSessionDirectorySelection = true
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
            // 某些文档提供商不支持持久化权限，但本次会话仍可使用。
        }
        val displayName = runCatching {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull() ?: uri.toString()
        binding.etLocalPath.setText(uri.toString())
        log("已选择目录：$displayName")
        log("目录 URI：$uri")
    }

    private fun startUpload() {
        if (uploadFuture?.isDone == false) {
            log("已有上传任务正在执行")
            return
        }
        if (!hasAllFilesPermission()) {
            log("缺少“所有文件访问权限”，正在打开系统授权页")
            requestAllFilesPermission()
            return
        }

        val username = binding.etUsername.text?.toString()?.trim().orEmpty()
        val token = binding.etToken.text?.toString()?.trim().orEmpty()
        val repoUrl = binding.etRepoUrl.text?.toString()?.trim().orEmpty()
        val localInput = binding.etLocalPath.text?.toString()?.trim().orEmpty()
        val branch = binding.etBranch.text?.toString()?.trim().orEmpty().ifBlank { "main" }
        val forcePush = binding.switchForcePush.isChecked

        clearFieldErrors()
        if (username.isBlank()) {
            binding.layoutUsername.error = "请输入 Git 用户名"
            binding.etUsername.requestFocus()
            return
        }
        if (token.isBlank()) {
            binding.layoutToken.error = "请输入 Git 令牌"
            binding.etToken.requestFocus()
            return
        }
        if (repoUrl.isBlank()) {
            binding.layoutRepoUrl.error = "请输入远程仓库 URL"
            binding.etRepoUrl.requestFocus()
            return
        }
        if (localInput.isBlank()) {
            binding.layoutLocalPath.error = "请选择本地目录"
            binding.etLocalPath.requestFocus()
            return
        }

        val source = sourceFromInput(localInput) ?: run {
            binding.layoutLocalPath.error = "目录不存在或当前路径无效"
            binding.etLocalPath.requestFocus()
            return
        }

        saveConfig()
        setUploading(true)
        log("----------------------------------------")
        log("开始一键上传")
        log("来源：${displaySource(localInput)}")
        log("远程仓库：${sanitizeRepoUrl(repoUrl)}")
        log("远程分支：$branch")
        log("强制推送：${if (forcePush) "是" else "否"}")

        val request = GitUploader.Request(source, repoUrl, username, token, branch, forcePush)
        uploadFuture = executor.submit {
            val uploader = GitUploader(contentResolver, ::log)
            val result = uploader.upload(request)
            runOnUiThread {
                setUploading(false)
                if (result.success) {
                    log("✅ 上传成功：${result.message}")
                    Toast.makeText(
                        this,
                        "上传成功：${result.copiedFiles} 个文件，${formatBytes(result.copiedBytes)}",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    log("❌ 上传失败：${result.message}")
                    if (!forcePush) log("如遇非快进冲突，可勾选“强制推送”后再次执行")
                    Toast.makeText(this, "上传失败，请查看运行日志", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun sourceFromInput(input: String): GitUploader.Source? {
        val uri = selectedDirUri
        if (uri != null && input == uri.toString() && (hasCurrentSessionDirectorySelection || contentResolver.persistedUriPermissions.any { it.uri == uri })) {
            return GitUploader.Source.TreeUri(uri)
        }
        return File(input).takeIf { it.isDirectory }?.let { GitUploader.Source.FileSystem(it) }
    }

    private fun displaySource(input: String): String = if (input.startsWith("content://")) "SAF 文档目录" else input

    private fun clearFieldErrors() {
        binding.layoutUsername.error = null
        binding.layoutToken.error = null
        binding.layoutRepoUrl.error = null
        binding.layoutLocalPath.error = null
    }

    private fun setUploading(uploading: Boolean) {
        binding.btnUpload.isEnabled = !uploading && hasAllFilesPermission()
        binding.btnSaveConfig.isEnabled = !uploading
        binding.btnSelectDir.isEnabled = !uploading
        binding.btnUpload.text = if (uploading) "上传中…" else "一键上传"
        // 上传期间保持屏幕常亮，防止息屏中断
        if (uploading) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun hasAllFilesPermission(): Boolean = Environment.isExternalStorageManager()

    private fun requestAllFilesPermission() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    private fun refreshPermissionState() {
        binding.btnUpload.isEnabled = hasAllFilesPermission() && uploadFuture?.isDone != false
    }

    private fun sanitizeRepoUrl(url: String): String =
        url.replace(Regex("(?i)(https?://)([^/@:]+):([^/@]+)@"), "$1***:***@")

    private fun log(message: String) {
        runOnUiThread {
            val now = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val line = "[$now] $message"
            if (logBuffer.isNotEmpty()) logBuffer.append('\n')
            logBuffer.append(line)
            // 防止内存无限增长
            if (logBuffer.length > MAX_LOG_CHARS) {
                logBuffer.delete(0, logBuffer.length - MAX_LOG_CHARS)
            }
            // 若日志弹窗正在显示，实时刷新
            logTextView?.let { tv ->
                tv.text = logBuffer.toString()
                logScrollView?.post {
                    logScrollView?.fullScroll(ScrollView.FOCUS_DOWN)
                }
            }
        }
    }

    private fun showLogDialog() {
        if (logDialog?.isShowing == true) {
            logDialog?.dismiss()
        }

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            setPadding(32, 16, 32, 16)
        }
        val textView = TextView(this).apply {
            text = if (logBuffer.isEmpty()) "暂无日志" else logBuffer.toString()
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true) // 支持长按复制
            movementMethod = ScrollingMovementMethod.getInstance()
            setPadding(8, 8, 8, 8)
            setBackgroundColor(0x12000000)
        }
        scrollView.addView(textView)
        logScrollView = scrollView
        logTextView = textView

        logDialog = AlertDialog.Builder(this)
            .setTitle("运行日志")
            .setView(scrollView)
            .setPositiveButton("关闭", null)
            .setNeutralButton("清空") { _, _ ->
                logBuffer.clear()
                textView.text = "暂无日志"
                Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("复制全部") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("log", logBuffer.toString()))
                Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
            .setOnDismissListener {
                logTextView = null
                logScrollView = null
                logDialog = null
            }
            .create()

        logDialog?.show()
        // 打开后自动滚到底部
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }

    override fun onDestroy() {
        uploadFuture?.cancel(true)
        executor.shutdownNow()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        logDialog?.dismiss()
        super.onDestroy()
    }

    companion object {
        private const val PREFS_NAME = "github_upload_config"
        private const val KEY_USERNAME = "username"
        private const val KEY_REPO_URL = "repo_url"
        private const val KEY_LOCAL_PATH = "local_path"
        private const val KEY_LOCAL_URI = "local_uri"
        private const val KEY_BRANCH = "branch"
        private const val KEY_FORCE_PUSH = "force_push"
        private const val MAX_LOG_CHARS = 80_000
    }
}
