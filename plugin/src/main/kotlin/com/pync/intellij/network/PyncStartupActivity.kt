package com.pync.intellij.network

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Triggers [PyncNetworkService.start] on first project open.
 *
 * [DumbAware] so it runs immediately without waiting for indexing to complete —
 * the network service has no dependency on the project index.
 */
internal class PyncStartupActivity : ProjectActivity, DumbAware {
    override suspend fun execute(project: Project) {
        PyncNetworkService.getInstance().start()
    }
}
