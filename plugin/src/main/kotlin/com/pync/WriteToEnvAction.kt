package com.pync

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import javax.swing.SwingUtilities

class WriteToEnvAction {

    private val envFileNames = setOf(".env", ".env.local", ".env.development", ".env.development.local")

    @Volatile
    private var rememberedPath: String? = null
    private var gitignoreWarned = false
    private var pendingChooser = false

    fun autoSync(project: Project, secrets: List<Pair<String, String>>) {
        ApplicationManager.getApplication().invokeLater {
            doAutoSync(project, secrets)
        }
    }

    private fun doAutoSync(project: Project, secrets: List<Pair<String, String>>) {
        val remembered = rememberedPath
        if (remembered != null) {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(remembered)
            if (vf != null) {
                writeToFile(project, vf, secrets)
                return
            }
            rememberedPath = null
        }

        if (secrets.isEmpty()) return
        if (pendingChooser) return

        val envFiles = findEnvFiles(project)

        when {
            envFiles.size > 1 -> {
                pendingChooser = true
                showChooserPopup(project, envFiles, secrets)
            }
            envFiles.size == 1 -> {
                rememberedPath = envFiles.first().path
                writeToFile(project, envFiles.first(), secrets)
            }
            else -> {
                createAndWrite(project, secrets)
            }
        }
    }

    fun resetRemembered() {
        rememberedPath = null
        gitignoreWarned = false
    }

    private fun findEnvFiles(project: Project): List<VirtualFile> {
        val rootPath = project.basePath ?: return emptyList()
        val root = LocalFileSystem.getInstance().refreshAndFindFileByPath(rootPath) ?: return emptyList()
        val results = mutableListOf<VirtualFile>()

        for (child in root.children) {
            if (child.name in envFileNames && !child.isDirectory) {
                results.add(child)
            }
        }

        for (child in root.children) {
            if (child.isDirectory && !child.name.startsWith(".") && child.name != "node_modules") {
                for (grandchild in child.children) {
                    if (grandchild.name in envFileNames && !grandchild.isDirectory) {
                        results.add(grandchild)
                    }
                }
            }
        }

        return results.sortedBy { it.path }
    }

    private fun showChooserPopup(project: Project, envFiles: List<VirtualFile>, secrets: List<Pair<String, String>>) {
        val rootPath = project.basePath ?: ""
        val labels = envFiles.map { file ->
            val rel = file.path.removePrefix(rootPath).removePrefix("/").removePrefix("\\")
            rel.ifEmpty { file.name }
        }

        val dialog = EnvFileChooserDialog(project, labels)
        if (dialog.showAndGet()) {
            val idx = dialog.getSelectedIndex()
            if (idx >= 0) {
                val file = envFiles[idx]
                writeToFile(project, file, secrets)
            }
        }
        pendingChooser = false
    }

    private fun createAndWrite(project: Project, secrets: List<Pair<String, String>>) {
        val rootPath = project.basePath ?: return
        val root = LocalFileSystem.getInstance().refreshAndFindFileByPath(rootPath) ?: return

        WriteCommandAction.runWriteCommandAction(project) {
            val file = root.createChildData(this, ".env")
            val content = EnvFileMerger.merge("", secrets.toLinkedMap())
            file.setBinaryContent(content.toByteArray(Charsets.UTF_8))
            rememberedPath = file.path
            checkGitignore(project, file)
        }
        notify(project, secrets.size, ".env")
    }

    private fun writeToFile(project: Project, file: VirtualFile, secrets: List<Pair<String, String>>) {
        WriteCommandAction.runWriteCommandAction(project) {
            file.refresh(false, false)
            val existing = String(file.contentsToByteArray(), Charsets.UTF_8)
            val merged = EnvFileMerger.merge(existing, secrets.toLinkedMap())
            file.setBinaryContent(merged.toByteArray(Charsets.UTF_8))
        }
        checkGitignore(project, file)
        notify(project, secrets.size, file.name)
    }

    private fun checkGitignore(project: Project, envFile: VirtualFile) {
        if (gitignoreWarned) return
        val root = project.basePath ?: return
        val gitignore = File(root, ".gitignore")
        if (!gitignore.exists()) {
            gitignoreWarned = true
            warnNotIgnored(project, envFile.name)
            return
        }
        val patterns = gitignore.readLines().map { it.trim() }
        val isIgnored = patterns.any { p ->
            p == envFile.name || p == "*.env" || p == ".env*" || p == ".env"
        }
        if (!isIgnored) {
            gitignoreWarned = true
            warnNotIgnored(project, envFile.name)
        }
    }

    private fun warnNotIgnored(project: Project, fileName: String) {
        SwingUtilities.invokeLater {
            val notification = NotificationGroupManager.getInstance()
                .getNotificationGroup("Pync")
                .createNotification(
                    "Pync",
                    "$fileName is not in .gitignore. Your secrets may be committed.",
                    NotificationType.WARNING
                )
            notification.notify(project)
            javax.swing.Timer(5000) { notification.expire() }.apply { isRepeats = false; start() }
        }
    }

    private fun notify(project: Project, count: Int, fileName: String) {
        SwingUtilities.invokeLater {
            val notification = NotificationGroupManager.getInstance()
                .getNotificationGroup("Pync")
                .createNotification(
                    "Pync",
                    "Wrote $count secret${if (count != 1) "s" else ""} to $fileName",
                    NotificationType.INFORMATION
                )
            notification.notify(project)
            javax.swing.Timer(3000) { notification.expire() }.apply { isRepeats = false; start() }
        }
    }

    private fun List<Pair<String, String>>.toLinkedMap(): Map<String, String> {
        val map = linkedMapOf<String, String>()
        for ((k, v) in this) map[k] = v
        return map
    }
}
