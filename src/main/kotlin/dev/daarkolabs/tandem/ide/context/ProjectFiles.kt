package dev.daarkolabs.tandem.ide.context

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.psi.codeStyle.NameUtil
import org.jetbrains.annotations.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The project's files for the `@` palette (spec 3.5): their paths relative to the project's base directory, `/`-separated, read in a
 * `readAction` off the EDT from the base directory and the content roots under it (excluded and ignored folders, and `.idea`, left
 * out), and kept until a file is created, deleted, moved or renamed. [matching] ranks them with the IDE's own matcher
 * (`NameUtil.buildMatcher`); [known] holds the last list read, for the bubble's chips.
 */
@Service(Service.Level.PROJECT)
class ProjectFiles(private val project: Project, private val scope: CoroutineScope) {
    /** Guards [cache] against [generation]: a list read before a change is never stored as the current one. */
    private val lock = Any()

    @Volatile
    private var cache: List<String>? = null

    /** Bumped by every change that may add or remove a file; a read stores its list only if no change came while it read. */
    private var generation = 0L

    /** The folders [collect] last walked (base directory and content roots), as VFS paths; empty until the first read. */
    @Volatile
    private var roots: List<String> = emptyList()

    private val mutableKnown = MutableStateFlow<Set<String>>(emptySet())

    /** The paths of the last list read; empty until the first, which starts with the service, off the EDT. */
    val known: StateFlow<Set<String>> = mutableKnown.asStateFlow()

    init {
        project.messageBus.connect(scope).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    // A content change keeps the list; anything else (create, delete, move, rename, copy) under the project may
                    // change it. Whether the list is cached or still being read, it is read again.
                    val walked = roots
                    if (events.any { it !is VFileContentChangeEvent && (walked.isEmpty() || touches(it, walked)) }) {
                        synchronized(lock) {
                            generation++
                            cache = null
                        }
                        scope.launch { candidates() }
                    }
                }
            },
        )
        // The bubbles draw `@path` chips from [known]: read the list now rather than at the first `@` typed.
        scope.launch { candidates() }
    }

    /** Every file of the project, relative to its base directory; read once, then from the cache until the files change. */
    suspend fun candidates(): List<String> {
        var read: List<String> = emptyList()
        repeat(MAX_READS) {
            val started = synchronized(lock) {
                cache?.let { return it }
                generation
            }
            read = readAction { collect() }
            synchronized(lock) {
                if (generation == started) {
                    cache = read
                    mutableKnown.value = read.toHashSet()
                    return read
                }
            }
        }
        // The files kept changing while they were read: give the last list, uncached, and the next change reads again.
        mutableKnown.value = read.toHashSet()
        return read
    }

    /** At most [limit] of the [candidates] that match [text], best first by the IDE's matcher; the first [limit] for an empty text. */
    suspend fun matching(text: String, limit: Int = 40): List<String> {
        val all = candidates()
        if (text.isEmpty()) return all.take(limit)
        return withContext(Dispatchers.Default) {
            val matcher = NameUtil.buildMatcher("*$text", NameUtil.MatchingCaseSensitivity.NONE)
            all.asSequence()
                .mapNotNull { path -> if (matcher.matches(path)) path to matcher.matchingDegree(path) else null }
                .sortedByDescending { it.second }
                .take(limit)
                .map { it.first }
                .toList()
        }
    }

    /**
     * The text of the file at [path] (relative to the base directory, or absolute) as open in the IDE, when its document has unsaved
     * changes; null when it has none, is not loaded, or no such file is known (spec 3.5). In a `readAction`, off the EDT.
     */
    suspend fun unsavedText(path: String): String? = readAction {
        val base = project.guessProjectDir()
        val file = base?.findFileByRelativePath(path) ?: LocalFileSystem.getInstance().findFileByPath(path)
        val documents = FileDocumentManager.getInstance()
        file?.takeIf { !it.isDirectory }?.let(documents::getCachedDocument)?.takeIf(documents::isDocumentUnsaved)?.text
    }

    /** The files open in editors, the selected ones first; tests replace it. */
    @VisibleForTesting
    internal var openFiles: () -> List<VirtualFile> = {
        val editors = FileEditorManager.getInstance(project)
        editors.selectedFiles.toList() + editors.openFiles.toList()
    }

    /** The files open in editors, the selected one first, as paths relative to the base directory, each once. */
    val openFirst: List<String>
        get() {
            val base = project.guessProjectDir() ?: return emptyList()
            return openFiles().mapNotNull { relativePath(it, base) }.distinct()
        }

    private fun collect(): List<String> {
        val base = project.guessProjectDir() ?: return emptyList()
        val index = ProjectFileIndex.getInstance(project)
        val types = FileTypeManager.getInstance()
        val roots = (listOf(base) + ProjectRootManager.getInstance(project).contentRoots).distinct()
        this.roots = roots.map { it.path }
        val out = LinkedHashSet<String>()
        for (root in roots) {
            VfsUtilCore.iterateChildrenRecursively(
                root,
                { file ->
                    ProgressManager.checkCanceled()
                    !file.isDirectory || !(file.name == ".idea" || index.isExcluded(file) || types.isFileIgnored(file))
                },
                { file ->
                    if (!file.isDirectory) relativePath(file, base)?.let(out::add)
                    out.size < MAX_FILES
                },
            )
        }
        return out.toList()
    }

    companion object {
        /** A read the files changed under is retried at most this many times in all. */
        private const val MAX_READS = 3

        /** Whether [event] (or, for a move or rename, its old place) is in one of [roots], VFS paths. */
        private fun touches(event: VFileEvent, roots: List<String>): Boolean {
            val paths = when (event) {
                is VFileMoveEvent -> listOf(event.oldPath, event.newPath)
                is VFilePropertyChangeEvent -> listOf(event.oldPath, event.newPath)
                else -> listOf(event.path)
            }
            return paths.any { path -> roots.any { root -> FileUtil.isAncestor(root, path, false) } }
        }

        /** No more files than this are listed: a huge project's palette still opens at once. */
        private const val MAX_FILES = 50_000

        fun getInstance(project: Project): ProjectFiles = project.service()

        private fun relativePath(file: VirtualFile, base: VirtualFile): String? =
            VfsUtilCore.getRelativePath(file, base, '/')?.takeIf { it.isNotEmpty() }
    }
}
