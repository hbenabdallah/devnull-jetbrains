package io.github.hbenabdallah.devnull

import com.intellij.ide.BrowserUtil
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import git4idea.repo.GitRepositoryManager
import java.net.InetAddress

private fun notify(project: Project?, content: String, type: NotificationType = NotificationType.INFORMATION, vararg actions: NotificationAction) {
    NotificationGroupManager.getInstance().getNotificationGroup("/dev/null")
        .createNotification("/dev/null", content, type)
        .apply { actions.forEach(::addAction) }
        .notify(project)
}

private fun openAction(text: String, url: String) = object : NotificationAction(text) {
    override fun actionPerformed(event: AnActionEvent, notification: Notification) {
        BrowserUtil.browse(url)
        notification.expire()
    }
}

private fun deviceName(): String {
    val host = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("this computer")

    return "${ApplicationNamesInfo.getInstance().fullProductName} on $host"
}

/**
 * Signs in in the background, as approving the device in the browser takes a while, then runs [then] with the client.
 */
fun signIn(project: Project?, then: (DevNullClient) -> Unit = {}) {
    val settings = DevNullSettings.getInstance()

    object : Task.Backgroundable(project, "/dev/null: approve this IDE in your browser…", true) {
        override fun run(indicator: ProgressIndicator) {
            try {
                val (key, author) = SignIn.run(settings.url, deviceName(), BrowserUtil::browse)
                settings.signedIn(key, author)
                notify(project, "Signed in as @${author.handle}.")
                then(DevNullClient(settings.url, key))
            } catch (exception: Exception) {
                notify(project, exception.message ?: "Could not sign in.", NotificationType.ERROR)
            }
        }
    }.queue()
}

fun post(project: Project?, body: String) {
    val client = DevNullSettings.getInstance().client()

    if (client == null) {
        notify(project, "Sign in to commit.", NotificationType.INFORMATION, object : NotificationAction("Sign In") {
            override fun actionPerformed(event: AnActionEvent, notification: Notification) {
                notification.expire()
                signIn(project) { commitWith(project, it, body) }
            }
        })

        return
    }

    commitWith(project, client, body)
}

private fun commitWith(project: Project?, client: DevNullClient, body: String) {
    object : Task.Backgroundable(project, "/dev/null: committing…", false) {
        override fun run(indicator: ProgressIndicator) {
            try {
                val commit = client.commit(body)
                val text = if (commit.status == "pending") {
                    "Committed ${commit.shortHash}. Everyone will see it once it is approved."
                } else {
                    "Committed ${commit.shortHash}."
                }
                notify(project, text, NotificationType.INFORMATION, openAction("Open", commit.htmlUrl))
            } catch (exception: ApiException) {
                if (exception.status == 401) {
                    DevNullSettings.getInstance().forget()
                    notify(project, "This device was revoked. Sign in again to commit.", NotificationType.ERROR)
                } else {
                    notify(project, exception.message ?: "Could not commit.", NotificationType.ERROR, openAction("Open /dev/null", DevNullSettings.getInstance().url))
                }
            } catch (exception: Exception) {
                notify(project, exception.message ?: "Could not commit.", NotificationType.ERROR)
            }
        }
    }.queue()
}

class SignInAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) = signIn(event.project)
}

class SignOutAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val handle = DevNullSettings.getInstance().forget()
        notify(
            event.project,
            "Signed out${handle?.let { " of @$it" } ?: ""}. Revoke this device in Settings → Devices to remove its key for good.",
            NotificationType.INFORMATION,
            openAction("Open Devices", "${DevNullSettings.getInstance().url}/settings/devices"),
        )
    }
}

class CommitAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val body = Messages.showMultilineInputDialog(event.project, "What are you working on?", "Commit to /dev/null", "", null, null)
            ?.takeIf { it.isNotBlank() } ?: return

        if (body.length > CommitBody.MAX_LENGTH) {
            Messages.showErrorDialog(event.project, "A commit holds at most ${CommitBody.MAX_LENGTH} characters.", "/dev/null")

            return
        }

        post(event.project, body)
    }
}

class CommitSelectionAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.getData(CommonDataKeys.EDITOR)?.selectionModel?.hasSelection() == true
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val selection = selectionOf(project, editor, file)
        val message = Messages.showMultilineInputDialog(project, "Say something about ${selection.path} (optional)", "Commit Selection to /dev/null", "", null, null)
            ?: return
        val body = CommitBody.of(message, selection)

        if (body == null) {
            Messages.showErrorDialog(project, "A commit holds at most ${CommitBody.MAX_LENGTH} characters. Select fewer lines.", "/dev/null")

            return
        }

        invokeLater { post(project, body) }
    }

    private fun selectionOf(project: Project, editor: Editor, file: VirtualFile): CodeSelection {
        val document = editor.document
        val model = editor.selectionModel
        val startLine = document.getLineNumber(model.selectionStart) + 1
        // A selection ending at the start of a line doesn't take that line.
        val endOffset = model.selectionEnd
        val endLine = document.getLineNumber(endOffset).let { if (it + 1 > startLine && document.getLineStartOffset(it) == endOffset) it else it + 1 }
        val code = if (startLine == endLine && document.getLineNumber(model.selectionStart) == document.getLineNumber(endOffset)) {
            model.selectedText.orEmpty()
        } else {
            document.getText(com.intellij.openapi.util.TextRange(document.getLineStartOffset(startLine - 1), document.getLineEndOffset(endLine - 1)))
        }
        val repository = GitRepositoryManager.getInstance(project).getRepositoryForFileQuick(file)
        val path = repository?.let { VfsUtilCore.getRelativePath(file, it.root) } ?: file.name
        val language = PsiDocumentManager.getInstance(project).getPsiFile(document)?.language?.displayName ?: ""

        return CodeSelection(
            code = code,
            language = CommitBody.languageOf(language, file.extension),
            path = path,
            startLine = startLine,
            endLine = endLine,
            permalink = repository?.let { permalinkFor(project, it, file, path, startLine, endLine) },
        )
    }

    /**
     * Only lines exactly as pushed get a link, so it shows what the commit shows.
     */
    private fun permalinkFor(project: Project, repository: git4idea.repo.GitRepository, file: VirtualFile, path: String, startLine: Int, endLine: Int): String? {
        val revision = repository.currentRevision ?: return null
        val tracked = repository.currentBranch?.findTrackedBranch(repository) ?: return null
        val isPushed = repository.branches.getHash(tracked)?.asString() == revision
        val isChanged = FileDocumentManager.getInstance().isFileModified(file) || ChangeListManager.getInstance(project).getChange(file) != null
        val remote = repository.remotes.firstOrNull { it.name == "origin" } ?: repository.remotes.firstOrNull()
        val remoteUrl = remote?.firstUrl ?: return null

        if (!DevNullSettings.getInstance().state.linkToGitHub || !isPushed || isChanged) {
            return null
        }

        return CommitBody.gitHubPermalink(remoteUrl, revision, path, startLine, endLine)
    }
}
