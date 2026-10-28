package dev.daarkolabs.tandem.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import java.nio.file.Path

/** Test hook: the folder `Add folder` picks, in place of the platform's chooser (IDE tests cannot drive it). */
private const val ADD_FOLDER_PROPERTY: String = "tandem.addFolder"

/**
 * Picks the folder the `+` menu's `Add folder` adds (docs/superpowers/specs/2026-10-22-desktop-parity-design.md 3.4). [choose] may
 * show a modal dialog: call it only through [chooseLater], never from a Compose callback or a coroutine launched from composition,
 * where the modal loop re-enters the scene's effect dispatcher and the chat stops painting (the 0.1.1 picture freeze).
 */
fun interface FolderChooser {
    /** The folder picked, or null when the user cancelled. Modal: called only from [chooseLater]. */
    fun choose(project: Project): Path?

    /**
     * Runs [choose] in the task [launcher] schedules, never in the caller's own call, and passes a chosen folder to [onChosen]; a
     * cancel passes nothing on. By default an EDT event of its own after the current one, in the default modality, and nothing opens
     * once [project] is disposed: the base's `ComposerImages.chooseImagesLater` pattern.
     */
    fun chooseLater(project: Project, onChosen: (Path) -> Unit, launcher: (Runnable) -> Unit = laterOnEdt(project)) {
        launcher(Runnable { choose(project)?.let(onChosen) })
    }
}

/** Posts a task as an EDT event of its own, in the default modality, dropped once [project] is disposed. */
internal fun laterOnEdt(project: Project): (Runnable) -> Unit = { task ->
    ApplicationManager.getApplication().invokeLater(task, ModalityState.defaultModalityState(), project.disposed)
}

object FolderChoosers {
    /**
     * The folder the `tandem.addFolder` system property names (IDE tests) when it is set, else the platform's single-folder chooser,
     * titled `Add Folder`, which reads nothing itself.
     */
    fun default(): FolderChooser = FolderChooser { project ->
        propertyFolder() ?: FileChooser.chooseFile(
            FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Add Folder"),
            project,
            null,
        )?.toNioPath()
    }

    /** The `tandem.addFolder` system property as a path; null when it is not set. */
    internal fun propertyFolder(): Path? = System.getProperty(ADD_FOLDER_PROPERTY)?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
}
