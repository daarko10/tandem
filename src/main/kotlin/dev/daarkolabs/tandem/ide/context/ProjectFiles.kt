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
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.psi.codeStyle.NameUtil
import org.jetbrains.annotations.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * The project's files for the `@` palette (spec 3.5): their paths relative to the project's base directory, `/`-separated, read in a
 * `readAction` off the EDT from the base directory and the content roots under it (excluded and ignored folders, and `.idea`, left
 * out), and kept until a file is created, deleted, moved or renamed in a listed folder: a change under an excluded or ignored one
 * (`build/` during a build, `.git/` during a commit) is not a change of the list. The re-reads run one at a time, with at most one
 * more waiting ([rereads] is conflated), however many event batches come while one runs. [matching] ranks them with the IDE's own
 * matcher (`NameUtil.buildMatcher`); [known] holds the last list read, for the bubble's chips.
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

    /** A re-read asked for by a change; conflated, so one runs and at most one more waits. */
    private val rereads = Channel<Unit>(Channel.CONFLATED)

    /** How many times the files were walked; tests read it. */
    @VisibleForTesting
    internal val reads: AtomicInteger = AtomicInteger()

    /** How many reads run now; [settle] waits for none. */
    private val busy = MutableStateFlow(0)

    /** Returns once no read runs: a test's point of rest. Bounded by the caller. */
    @VisibleForTesting
    internal suspend fun settle() {
        busy.first { it == 0 }
    }

    init {
        project.messageBus.connect(scope).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    // A content change keeps the list; a create, delete, move, rename or copy in a listed folder may change it.
                    // Whether the list is cached or still being read, it is read again.
                    val walked = roots
                    if (events.any { walked.isEmpty() || listed(it, walked) }) {
                        synchronized(lock) {
                            generation++
                            cache = null
                        }
                        rereads.trySend(Unit)
                    }
                }
            },
        )
        scope.launch { for (unit in rereads) candidates() }
        // The bubbles draw `@path` chips from [known]: read the list now rather than at the first `@` typed.
        scope.launch { candidates() }
    }

    /**
     * Whether [event] may change the list: a file or folder created, deleted, moved, renamed or copied in a folder of [roots] that
     * [collect] walks into, so one under no excluded or ignored folder (nor `.idea`). Under the VFS's write action, so with read
     * access for the index.
     */
    private fun listed(event: VFileEvent, roots: List<String>): Boolean {
        val folders: List<VirtualFile?> = when (event) {
            is VFileCreateEvent -> listOf(event.parent)
            is VFileDeleteEvent -> listOf(event.file.parent)
            is VFileCopyEvent -> listOf(event.newParent)
            is VFileMoveEvent -> listOf(event.oldParent, event.newParent)
            is VFilePropertyChangeEvent -> if (event.isRename) listOf(event.file.parent) else return false
            else -> return false
        }
        val index = ProjectFileIndex.getInstance(project)
        val types = FileTypeManager.getInstance()
        return folders.any { folder -> folder != null && walkedInto(folder, roots, index, types) }
    }

    /** Every file of the project, relative to its base directory; read once, then from the cache until the files change. */
    suspend fun candidates(): List<String> {
        var read: List<String> = emptyList()
        repeat(MAX_READS) {
            val started = synchronized(lock) {
                cache?.let { return it }
                generation
            }
            busy.update { it + 1 }
            try {
                read = readAction { collect() }
            } finally {
                busy.update { it - 1 }
            }
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
        // A content root outside the base holds no listable file ([relativePath] is null for all of it): it is not walked.
        val roots = (listOf(base) + ProjectRootManager.getInstance(project).contentRoots.filter { VfsUtilCore.isAncestor(base, it, false) })
            .distinct()
        this.roots = roots.map { it.path }
        reads.incrementAndGet()
        val out = LinkedHashSet<String>()
        for (root in roots) {
            VfsUtilCore.iterateChildrenRecursively(
                root,
                { file ->
                    ProgressManager.checkCanceled()
                    !file.isDirectory || !hidden(file, index, types)
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

        /** Whether [collect] stops at the folder [file]: `.idea`, excluded or ignored. */
        private fun hidden(file: VirtualFile, index: ProjectFileIndex, types: FileTypeManager): Boolean =
            file.name == ".idea" || index.isExcluded(file) || types.isFileIgnored(file)

        /**
         * Whether [collect] walks into [folder]: it is one of [roots] (VFS paths), or lies under one with no [hidden] folder between
         * (the root itself included).
         */
        private fun walkedInto(folder: VirtualFile, roots: List<String>, index: ProjectFileIndex, types: FileTypeManager): Boolean {
            var at: VirtualFile? = folder
            while (at != null) {
                val here = at
                if (here.path in roots) return true
                if (hidden(here, index, types) || roots.none { root -> FileUtil.isAncestor(root, here.path, true) }) return false
                at = here.parent
            }
            return false
        }

        /** No more files than this are listed: a huge project's palette still opens at once. */
        private const val MAX_FILES = 50_000

        fun getInstance(project: Project): ProjectFiles = project.service()

        private fun relativePath(file: VirtualFile, base: VirtualFile): String? =
            VfsUtilCore.getRelativePath(file, base, '/')?.takeIf { it.isNotEmpty() }
    }
}
