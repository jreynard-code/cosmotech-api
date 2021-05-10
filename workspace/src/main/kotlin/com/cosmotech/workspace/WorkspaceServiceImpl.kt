// Copyright (c) Cosmo Tech.
// Licensed under the MIT license.
package com.cosmotech.workspace

import com.azure.cosmos.models.CosmosContainerProperties
import com.cosmotech.api.AbstractCosmosBackedService
import com.cosmotech.api.events.OrganizationRegistered
import com.cosmotech.api.events.OrganizationUnregistered
import com.cosmotech.api.utils.findAll
import com.cosmotech.api.utils.findByIdOrThrow
import com.cosmotech.workspace.api.WorkspaceApiService
import com.cosmotech.workspace.domain.Workspace
import com.cosmotech.workspace.domain.WorkspaceFile
import com.cosmotech.workspace.domain.WorkspaceUser
import java.util.*
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(name = ["csm.platform.vendor"], havingValue = "azure", matchIfMissing = true)
class WorkspaceServiceImpl : AbstractCosmosBackedService(), WorkspaceApiService {
  override fun findAllWorkspaces(organizationId: String) =
      cosmosTemplate.findAll<Workspace>("${organizationId}_workspaces")

  override fun findWorkspaceById(organizationId: String, workspaceId: String): Workspace =
      cosmosTemplate.findByIdOrThrow(
          "${organizationId}_workspaces",
          workspaceId,
          "Workspace $workspaceId not found in organization $organizationId")

  override fun removeAllUsersOfWorkspace(organizationId: String, workspaceId: String) {
    TODO("Not yet implemented")
  }

  override fun addUsersToOrganizationWorkspace(
      organizationId: String,
      workspaceId: String,
      workspaceUser: List<WorkspaceUser>
  ): List<WorkspaceUser> {
    TODO("Not yet implemented")
  }

  override fun createWorkspace(organizationId: String, workspace: Workspace): Workspace =
      cosmosTemplate.insert(
          "${organizationId}_workspaces", workspace.copy(id = idGenerator.generate("workspace")))
          ?: throw IllegalArgumentException("No Workspace returned in response: $workspace")

  override fun updateWorkspace(
      organizationId: String,
      workspaceId: String,
      workspace: Workspace
  ): Workspace {
    TODO("Not yet implemented")
  }

  override fun deleteWorkspace(organizationId: String, workspaceId: String): Workspace {
    val workspace = findWorkspaceById(organizationId, workspaceId)
    cosmosTemplate.deleteEntity("${organizationId}_workspaces", workspace)
    return workspace
  }

  override fun deleteWorkspaceFile(
      organizationId: kotlin.String,
      workspaceId: kotlin.String,
      workspaceFile: WorkspaceFile
  ): WorkspaceFile {
    TODO("Not yet implemented")
  }

  override fun uploadWorkspaceFile(
      organizationId: kotlin.String,
      workspaceId: kotlin.String,
      fileName: org.springframework.core.io.Resource?
  ): WorkspaceFile {
    TODO("Not yet implemented")
  }
  override fun findAllWorkspaceFiles(
      organizationId: kotlin.String,
      workspaceId: kotlin.String
  ): List<WorkspaceFile> {
    TODO("Not yet implemented")
  }

  @EventListener(OrganizationRegistered::class)
  fun onOrganizationRegistered(organizationRegistered: OrganizationRegistered) {
    cosmosCoreDatabase.createContainerIfNotExists(
        CosmosContainerProperties("${organizationRegistered.organizationId}_workspaces", "/id"))
  }

  @EventListener(OrganizationUnregistered::class)
  @Async("csm-in-process-event-executor")
  fun onOrganizationUnregistered(organizationUnregistered: OrganizationUnregistered) {
    cosmosTemplate.deleteContainer("${organizationUnregistered.organizationId}_workspaces")
  }
}