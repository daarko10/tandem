package dev.daarkolabs.tandem.ui

import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.fileLogger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import dev.daarkolabs.tandem.TandemProjectScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

private val LOG = fileLogger()

/**
 * Opens the file at [path] in [project]'s editor (spec 4.14): the VFS refresh and lookup run on [Dispatchers.IO] in the project's
 * [TandemProjectScope], never on the EDT; the editor opens on the EDT. A path with no file opens nothing and is logged at debug,
 * without the path.
 */
internal fun openInEditor(project: Project, path: Path) {
    TandemProjectScope.get(project).scope.launch(Dispatchers.IO) {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: run {
            LOG.debug("file to open not found")
            return@launch
        }
        withContext(Dispatchers.EDT) { OpenFileDescriptor(project, file).navigate(true) }
    }
}
