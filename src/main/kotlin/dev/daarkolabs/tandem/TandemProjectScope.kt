package dev.daarkolabs.tandem

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope

/** Owns Tandem's coroutines for one project. The platform cancels [scope] when the project closes. */
@Service(Service.Level.PROJECT)
class TandemProjectScope(val scope: CoroutineScope) {
    companion object {
        fun get(project: Project): TandemProjectScope = project.service()
    }
}
