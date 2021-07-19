// Copyright (c) Cosmo Tech.
// Licensed under the MIT license.
package com.cosmotech.scenario.azure

import com.azure.cosmos.models.CosmosContainerProperties
import com.azure.cosmos.models.CosmosItemRequestOptions
import com.azure.cosmos.models.CosmosQueryRequestOptions
import com.azure.cosmos.models.PartitionKey
import com.azure.cosmos.models.SqlParameter
import com.azure.cosmos.models.SqlQuerySpec
import com.cosmotech.api.azure.AbstractCosmosBackedService
import com.cosmotech.api.events.OrganizationRegistered
import com.cosmotech.api.events.OrganizationUnregistered
import com.cosmotech.api.events.ScenarioDatasetListChanged
import com.cosmotech.api.events.ScenarioRunStartedForScenario
import com.cosmotech.api.events.UserAddedToScenario
import com.cosmotech.api.events.UserRemovedFromScenario
import com.cosmotech.api.events.WorkflowStatusRequest
import com.cosmotech.api.exceptions.CsmAccessForbiddenException
import com.cosmotech.api.utils.changed
import com.cosmotech.api.utils.compareToAndMutateIfNeeded
import com.cosmotech.api.utils.convertToMap
import com.cosmotech.api.utils.getCurrentAuthenticatedUserName
import com.cosmotech.api.utils.toDomain
import com.cosmotech.organization.api.OrganizationApiService
import com.cosmotech.scenario.api.ScenarioApiService
import com.cosmotech.scenario.domain.Scenario
import com.cosmotech.scenario.domain.Scenario.State
import com.cosmotech.scenario.domain.ScenarioComparisonResult
import com.cosmotech.scenario.domain.ScenarioLastRun
import com.cosmotech.scenario.domain.ScenarioRunTemplateParameterValue
import com.cosmotech.scenario.domain.ScenarioUser
import com.cosmotech.solution.api.SolutionApiService
import com.cosmotech.user.api.UserApiService
import com.cosmotech.user.domain.User
import com.cosmotech.workspace.api.WorkspaceApiService
import com.fasterxml.jackson.databind.JsonNode
import java.time.OffsetDateTime
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(name = ["csm.platform.vendor"], havingValue = "azure", matchIfMissing = true)
class ScenarioServiceImpl(
    private val userService: UserApiService,
    private val solutionService: SolutionApiService,
    private val organizationService: OrganizationApiService,
    private val workspaceService: WorkspaceApiService,
) : AbstractCosmosBackedService(), ScenarioApiService {

  protected fun Scenario.asMapWithAdditionalData(workspaceId: String): Map<String, Any> {
    val scenarioAsMap = this.convertToMap().toMutableMap()
    scenarioAsMap["type"] = "Scenario"
    scenarioAsMap["workspaceId"] = workspaceId
    return scenarioAsMap
  }

  override fun addOrReplaceScenarioParameterValues(
      organizationId: String,
      workspaceId: String,
      scenarioId: String,
      scenarioRunTemplateParameterValue: List<ScenarioRunTemplateParameterValue>
  ): List<ScenarioRunTemplateParameterValue> {
    if (scenarioRunTemplateParameterValue.isNotEmpty()) {
      val scenario = findScenarioById(organizationId, workspaceId, scenarioId)
      val parametersValuesMap =
          scenario.parametersValues?.associateBy { it.parameterId }?.toMutableMap()
              ?: mutableMapOf()
      parametersValuesMap.putAll(
          scenarioRunTemplateParameterValue
              .filter { it.parameterId.isNotBlank() }
              .map { it.copy(isInherited = false) }
              .associateBy { it.parameterId })
      scenario.parametersValues = parametersValuesMap.values.toList()
      upsertScenarioData(organizationId, scenario, workspaceId)
    }
    return scenarioRunTemplateParameterValue
  }

  private fun fetchUsers(userIds: Collection<String>): Map<String, User> =
      userIds.toSet().map { userService.findUserById(it) }.associateBy { it.id!! }

  override fun addOrReplaceUsersInScenario(
      organizationId: String,
      workspaceId: String,
      scenarioId: String,
      scenarioUser: List<ScenarioUser>
  ): List<ScenarioUser> {
    if (scenarioUser.isEmpty()) {
      // Nothing to do
      return scenarioUser
    }

    val organization = organizationService.findOrganizationById(organizationId)
    val workspace = workspaceService.findWorkspaceById(organizationId, workspaceId)
    val scenario = findScenarioById(organizationId, workspaceId, scenarioId)

    val scenarioUserWithoutNullIds = scenarioUser.filter { it.id != null }
    val newUsersLoaded = fetchUsers(scenarioUserWithoutNullIds.mapNotNull { it.id })
    val scenarioUserWithRightNames =
        scenarioUserWithoutNullIds.map { it.copy(name = newUsersLoaded[it.id]!!.name!!) }
    val scenarioUserMap = scenarioUserWithRightNames.associateBy { it.id!! }

    val currentScenarioUsers =
        scenario.users?.filter { it.id != null }?.associateBy { it.id!! }?.toMutableMap()
            ?: mutableMapOf()

    newUsersLoaded.forEach { (userId, _) ->
      // Add or replace
      currentScenarioUsers[userId] = scenarioUserMap[userId]!!
    }
    scenario.users = currentScenarioUsers.values.toList()
    scenario.lastUpdate = OffsetDateTime.now()

    upsertScenarioData(organizationId, scenario, workspaceId)

    // Roles might have changed => notify all users so they can update their own items
    scenario.users?.forEach { user ->
      this.eventPublisher.publishEvent(
          UserAddedToScenario(
              this, organizationId, user.id!!, user.roles.map { role -> role.value }))
    }
    return scenarioUserWithRightNames
  }

  override fun compareScenarios(
      organizationId: String,
      workspaceId: String,
      scenarioId: String,
      comparedScenarioId: String
  ): ScenarioComparisonResult {
    TODO("Not yet implemented")
  }

  override fun createScenario(
      organizationId: String,
      workspaceId: String,
      scenario: Scenario
  ): Scenario {
    val organization = organizationService.findOrganizationById(organizationId)
    val workspace = workspaceService.findWorkspaceById(organizationId, workspaceId)
    val solution =
        workspace.solution.solutionId?.let { solutionService.findSolutionById(organizationId, it) }
    val runTemplate =
        solution?.runTemplates?.find { runTemplate -> runTemplate.id == scenario.runTemplateId }
    if (scenario.runTemplateId != null && runTemplate == null) {
      throw IllegalArgumentException("Run Template not found: ${scenario.runTemplateId}")
    }

    val usersLoaded = scenario.users?.map { it.id }?.let { fetchUsers(it) }
    val usersWithNames =
        usersLoaded?.let { scenario.users?.map { it.copy(name = usersLoaded[it.id]!!.name!!) } }

    var datasetList = scenario.datasetList
    val parentId = scenario.parentId
    var rootId: String? = null
    val newParametersValuesList = scenario.parametersValues?.toMutableList() ?: mutableListOf()

    if (parentId != null) {
      logger.debug("Applying / Overwriting Dataset list from parent ${parentId}")
      val parent = this.findScenarioByIdNoState(organizationId, workspaceId, parentId)
      datasetList = parent.datasetList
      rootId = parent.rootId
      if (rootId == null) {
        rootId = parentId
      }

      logger.debug("Copying parameters values from parent $parentId")

      logger.debug("Getting runTemplate parameters ids")
      val runTemplateParametersIds =
          solution?.parameterGroups
              ?.filter { parameterGroup ->
                runTemplate?.parameterGroups?.contains(parameterGroup.id) == true
              }
              ?.flatMap { parameterGroup -> parameterGroup.parameters }
      if (!runTemplateParametersIds.isNullOrEmpty()) {
        val parentParameters = parent.parametersValues?.associate { it.parameterId to it }
        val scenarioParameters = scenario.parametersValues?.associate { it.parameterId to it }
        // TODO: Handle default value
        runTemplateParametersIds.forEach { parameterId ->
          if (scenarioParameters?.contains(parameterId) != true) {
            logger.debug(
                "Parameter $parameterId is not defined in the Scenario. " +
                    "Checking if it is defined in its parent $parentId")
            if (parentParameters?.contains(parameterId) == true) {
              logger.debug("Copying parameter value from parent for parameter $parameterId")
              val parameterValue = parentParameters[parameterId]
              if (parameterValue != null) {
                parameterValue.isInherited = true
                newParametersValuesList.add(parameterValue)
              } else {
                logger.warn(
                    "Parameter $parameterId not found in parent ($parentId) parameters values")
              }
            } else {
              logger.debug(
                  "Skipping parameter ${parameterId}, defined neither in the parent nor in this Scenario")
            }
          } else {
            logger.debug(
                "Skipping parameter $parameterId since it is already defined in this Scenario")
          }
        }
      }
    }

    val now = OffsetDateTime.now()
    val scenarioToSave =
        scenario.copy(
            id = idGenerator.generate("scenario"),
            ownerId = getCurrentAuthenticatedUserName(),
            solutionId = solution?.id,
            solutionName = solution?.name,
            runTemplateName = runTemplate?.name,
            creationDate = now,
            lastUpdate = now,
            users = usersWithNames,
            state = State.Created,
            datasetList = datasetList,
            rootId = rootId,
            parametersValues = newParametersValuesList,
        )
    val scenarioAsMap = scenarioToSave.asMapWithAdditionalData(workspaceId)
    // We cannot use cosmosTemplate as it expects the Domain object to contain a field named 'id'
    // or annotated with @Id
    if (cosmosCoreDatabase
        .getContainer("${organizationId}_scenario_data")
        .createItem(scenarioAsMap, PartitionKey(scenarioToSave.ownerId), CosmosItemRequestOptions())
        .item == null) {
      throw IllegalArgumentException("No Scenario returned in response: $scenarioAsMap")
    }

    // Roles might have changed => notify all users so they can update their own items
    scenario.users?.forEach { user ->
      this.eventPublisher.publishEvent(
          UserAddedToScenario(
              this, organizationId, user.id!!, user.roles.map { role -> role.value }))
    }

    return scenarioToSave
  }

  override fun deleteScenario(
      organizationId: String,
      workspaceId: String,
      scenarioId: String,
      waitRelationshipPropagation: Boolean
  ) {
    val scenario = this.findScenarioById(organizationId, workspaceId, scenarioId)

    if (scenario.ownerId != getCurrentAuthenticatedUserName()) {
      // TODO Only the owner or an admin should be able to perform this operation
      throw CsmAccessForbiddenException("You are not allowed to delete this Resource")
    }

    cosmosTemplate.deleteEntity("${organizationId}_scenario_data", scenario)
    // TODO Notify users

    this.handleScenarioDeletion(organizationId, workspaceId, scenario, waitRelationshipPropagation)
  }

  override fun deleteAllScenarios(organizationId: kotlin.String, workspaceId: kotlin.String) {
    // TODO Only the workspace owner should be able to do this
    val scenarios = this.findAllScenariosStateOption(organizationId, workspaceId, false)
    scenarios.forEach { cosmosTemplate.deleteEntity("${organizationId}_scenario_data", it) }
  }

  /** See https://spaceport.cosmotech.com/jira/browse/PROD-7939 */
  private fun handleScenarioDeletion(
      organizationId: String,
      workspaceId: String,
      scenario: Scenario,
      waitRelationshipPropagation: Boolean
  ) {
    val parentId = scenario.parentId
    val children = this.findScenarioChildrenById(organizationId, workspaceId, scenario.id!!)
    val childrenUpdatesCoroutines =
        children.map { child ->
          GlobalScope.launch {
            // TODO Consider using a smaller coroutine scope
            child.parentId = parentId
            this@ScenarioServiceImpl.upsertScenarioData(organizationId, child, workspaceId)
          }
        }
    if (waitRelationshipPropagation) {
      runBlocking { childrenUpdatesCoroutines.joinAll() }
    }
  }

  override fun findAllScenarios(organizationId: String, workspaceId: String): List<Scenario> =
      this.findAllScenariosStateOption(organizationId, workspaceId, true)

  private fun findAllScenariosStateOption(
      organizationId: String,
      workspaceId: String,
      addState: Boolean
  ): List<Scenario> =
      cosmosCoreDatabase
          .getContainer("${organizationId}_scenario_data")
          .queryItems(
              SqlQuerySpec(
                  "SELECT * FROM c WHERE c.type = 'Scenario' AND c.workspaceId = @WORKSPACE_ID",
                  listOf(SqlParameter("@WORKSPACE_ID", workspaceId))),
              CosmosQueryRequestOptions(),
              // It would be much better to specify the Domain Type right away and
              // avoid the map operation, but we can't due
              // to the lack of customization of the Cosmos Client Object Mapper, as reported here :
              // https://github.com/Azure/azure-sdk-for-java/issues/12269
              JsonNode::class.java)
          .mapNotNull {
            val scenario = it.toDomain<Scenario>()
            if (addState) {
              this.addStateToScenario(scenario)
            }
            return@mapNotNull scenario
          }
          .toList()

  private fun findAllScenariosByRootId(
      organizationId: String,
      workspaceId: String,
      rootId: String
  ): List<Scenario> =
      cosmosCoreDatabase
          .getContainer("${organizationId}_scenario_data")
          .queryItems(
              SqlQuerySpec(
                  "SELECT * FROM c WHERE c.type = 'Scenario' AND c.workspaceId = @WORKSPACE_ID AND c.rootId = @ROOT_ID",
                  listOf(
                      SqlParameter("@WORKSPACE_ID", workspaceId),
                      SqlParameter("@ROOT_ID", rootId))),
              CosmosQueryRequestOptions(),
              // It would be much better to specify the Domain Type right away and
              // avoid the map operation, but we can't due
              // to the lack of customization of the Cosmos Client Object Mapper, as reported here :
              // https://github.com/Azure/azure-sdk-for-java/issues/12269
              JsonNode::class.java)
          .mapNotNull {
            val scenario = it.toDomain<Scenario>()
            return@mapNotNull scenario
          }
          .toList()

  override fun findScenarioById(
      organizationId: String,
      workspaceId: String,
      scenarioId: String
  ): Scenario {
    val scenario = this.findScenarioByIdNoState(organizationId, workspaceId, scenarioId)
    this.addStateToScenario(scenario)
    return scenario
  }

  internal fun findScenarioChildrenById(
      organizationId: String,
      workspaceId: String,
      parentId: String
  ) =
      cosmosCoreDatabase
          .getContainer("${organizationId}_scenario_data")
          .queryItems(
              SqlQuerySpec(
                  "SELECT * FROM c WHERE c.type = 'Scenario' " +
                      "AND c.workspaceId = @WORKSPACE_ID " +
                      "AND c.parentId = @PARENT_ID",
                  listOf(
                      SqlParameter("@WORKSPACE_ID", workspaceId),
                      SqlParameter("@PARENT_ID", parentId))),
              CosmosQueryRequestOptions(),
              // It would be much better to specify the Domain Type right away and
              // avoid the map operation, but we can't due
              // to the lack of customization of the Cosmos Client Object Mapper, as reported here :
              // https://github.com/Azure/azure-sdk-for-java/issues/12269
              JsonNode::class.java)
          .mapNotNull { it.toDomain<Scenario>() }
          .toList()

  internal fun findScenarioByIdNoState(
      organizationId: String,
      workspaceId: String,
      scenarioId: String
  ): Scenario =
      cosmosCoreDatabase
          .getContainer("${organizationId}_scenario_data")
          .queryItems(
              SqlQuerySpec(
                  "SELECT * FROM c WHERE c.type = 'Scenario' AND c.id = @SCENARIO_ID AND c.workspaceId = @WORKSPACE_ID",
                  listOf(
                      SqlParameter("@SCENARIO_ID", scenarioId),
                      SqlParameter("@WORKSPACE_ID", workspaceId))),
              CosmosQueryRequestOptions(),
              // It would be much better to specify the Domain Type right away and
              // avoid the map operation, but we can't due
              // to the lack of customization of the Cosmos Client Object Mapper, as reported here
              // :
              // https://github.com/Azure/azure-sdk-for-java/issues/12269
              JsonNode::class.java)
          .firstOrNull()
          ?.toDomain<Scenario>()
          ?: throw java.lang.IllegalArgumentException(
              "Scenario #$scenarioId not found in workspace #$workspaceId in organization #$organizationId")

  private fun addStateToScenario(scenario: Scenario?) {
    if (scenario?.lastRun != null) {
      val workflowId = scenario.lastRun?.workflowId
      val workflowName = scenario.lastRun?.workflowName
      if (workflowId == null || workflowName == null) {
        throw IllegalStateException(
            "Scenario has a last Scenario Run but workflowId or workflowName is null")
      }
      val workflowStatusRequest = WorkflowStatusRequest(this, workflowId, workflowName)
      this.eventPublisher.publishEvent(workflowStatusRequest)
      scenario.state = this.mapPhaseToState(scenario.lastRun, workflowStatusRequest)
    }
  }

  private fun mapPhaseToState(
      scenarioLastRun: ScenarioLastRun?,
      workflowStatusRequest: WorkflowStatusRequest
  ): State {
    val scenarioRunId = scenarioLastRun?.scenarioRunId
    val phase = workflowStatusRequest.response
    logger.debug("Mapping phase $phase for scenario run $scenarioRunId")
    return when (phase) {
      "Pending", "Running" -> State.Running
      "Succeeded" -> State.Successful
      "Skipped", "Failed", "Error", "Omitted" -> State.Failed
      else -> {
        logger.warn(
            "Unhandled state response for scenario run {}: {} => returning Unknown as state",
            scenarioRunId,
            phase)
        State.Unknown
      }
    }
  }

  override fun getScenariosTree(organizationId: String, workspaceId: String): List<Scenario> {
    return this.findAllScenarios(organizationId, workspaceId)
  }

  override fun removeAllScenarioParameterValues(
      organizationId: String,
      workspaceId: String,
      scenarioId: String
  ) {
    val scenario = findScenarioById(organizationId, workspaceId, scenarioId)
    if (!scenario.parametersValues.isNullOrEmpty()) {
      scenario.parametersValues = listOf()
      scenario.lastUpdate = OffsetDateTime.now()

      upsertScenarioData(organizationId, scenario, workspaceId)
    }
  }

  override fun removeAllUsersOfScenario(
      organizationId: String,
      workspaceId: String,
      scenarioId: String
  ) {
    val scenario = findScenarioById(organizationId, workspaceId, scenarioId)
    if (!scenario.users.isNullOrEmpty()) {
      val userIds = scenario.users!!.mapNotNull { it.id }
      scenario.users = listOf()
      scenario.lastUpdate = OffsetDateTime.now()

      upsertScenarioData(organizationId, scenario, workspaceId)

      userIds.forEach {
        this.eventPublisher.publishEvent(
            UserRemovedFromScenario(this, organizationId, workspaceId, scenarioId, it))
      }
    }
  }

  override fun removeUserFromScenario(
      organizationId: String,
      workspaceId: String,
      scenarioId: String,
      userId: String
  ) {
    val scenario = findScenarioById(organizationId, workspaceId, scenarioId)
    val scenarioUserMap = scenario.users?.associateBy { it.id!! }?.toMutableMap() ?: mutableMapOf()
    if (scenarioUserMap.containsKey(userId)) {
      scenarioUserMap.remove(userId)
      scenario.users = scenarioUserMap.values.toList()
      scenario.lastUpdate = OffsetDateTime.now()
      upsertScenarioData(organizationId, scenario, workspaceId)
      this.eventPublisher.publishEvent(
          UserRemovedFromScenario(this, organizationId, workspaceId, scenarioId, userId))
    }
  }

  override fun updateScenario(
      organizationId: String,
      workspaceId: String,
      scenarioId: String,
      scenario: Scenario
  ): Scenario {
    val existingScenario = findScenarioById(organizationId, workspaceId, scenarioId)
    val organization = organizationService.findOrganizationById(organizationId)
    val workspace = workspaceService.findWorkspaceById(organizationId, workspaceId)

    var hasChanged =
        existingScenario
            .compareToAndMutateIfNeeded(
                scenario,
                excludedFields =
                    arrayOf(
                        "ownerId",
                        "datasetList",
                        "solutionId",
                        "runTemplateId",
                        "parametersValues"))
            .isNotEmpty()

    if (scenario.ownerId != null && scenario.changed(existingScenario) { ownerId }) {
      // Allow to change the ownerId as well, but only the owner can transfer the ownership
      if (existingScenario.ownerId != getCurrentAuthenticatedUserName()) {
        // TODO Only the owner or an admin should be able to perform this operation
        throw CsmAccessForbiddenException(
            "You are not allowed to change the ownership of this Resource")
      }
      existingScenario.ownerId = scenario.ownerId
      hasChanged = true
    }

    var userIdsRemoved: List<String>? = listOf()
    if (scenario.users != null) {
      // Specifying a list of users here overrides the previous list
      val usersToSet = fetchUsers(scenario.users!!.mapNotNull { it.id })
      userIdsRemoved =
          scenario.users?.mapNotNull { it.id }?.filterNot { usersToSet.containsKey(it) }
      val usersWithNames =
          usersToSet.let { scenario.users!!.map { it.copy(name = usersToSet[it.id]!!.name!!) } }
      existingScenario.users = usersWithNames
      hasChanged = true
    }

    var datasetListUpdated = false
    if (scenario.datasetList != null &&
        scenario.datasetList?.toSet() != existingScenario.datasetList?.toSet()) {
      // Only root Scenarios can update their Dataset list
      if (scenario.parentId != null) {
        logger.info(
            "Cannot set Dataset list on child Scenario ${scenarioId}. Only root scenarios can be set.")
      } else {
        // TODO Need to validate those IDs too ?
        existingScenario.datasetList = scenario.datasetList
        hasChanged = true
        datasetListUpdated = true
      }
    }

    // TODO Allow to change the ownerId and ownerName as well, but only the owner can transfer the
    // ownership

    if (scenario.solutionId != null && scenario.changed(existingScenario) { solutionId }) {
      logger.debug("solutionId is a read-only property => ignored ! ")
    }
    if (scenario.runTemplateId != null && scenario.changed(existingScenario) { runTemplateId }) {
      // Validate the runTemplateId
      val solution =
          workspace.solution.solutionId?.let {
            solutionService.findSolutionById(organizationId, it)
          }
      val newRunTemplateId = scenario.runTemplateId
      val runTemplate =
          solution?.runTemplates?.find { it.id == newRunTemplateId }
              ?: throw IllegalArgumentException(
                  "No run template '${newRunTemplateId}' in solution ${solution?.id}")
      existingScenario.runTemplateId = scenario.runTemplateId
      existingScenario.runTemplateName = runTemplate.name
      hasChanged = true
    }

    if (scenario.parametersValues != null &&
        scenario.parametersValues?.toSet() != existingScenario.parametersValues?.toSet()) {
      existingScenario.parametersValues = scenario.parametersValues
      existingScenario.parametersValues?.forEach { it.isInherited = false }
      hasChanged = true
    }

    return if (hasChanged) {
      existingScenario.lastUpdate = OffsetDateTime.now()
      upsertScenarioData(organizationId, existingScenario, workspaceId)

      userIdsRemoved?.forEach {
        this.eventPublisher.publishEvent(
            UserRemovedFromScenario(this, organizationId, workspaceId, scenarioId, it))
      }
      scenario.users?.forEach { user ->
        this.eventPublisher.publishEvent(
            UserAddedToScenario(
                this, organizationId, user.id!!, user.roles.map { role -> role.value }))
      }

      if (datasetListUpdated) {
        this.eventPublisher.publishEvent(
            ScenarioDatasetListChanged(
                this, organizationId, workspaceId, scenarioId, scenario.datasetList))
      }

      existingScenario
    } else {
      existingScenario
    }
  }

  internal fun upsertScenarioData(organizationId: String, scenario: Scenario, workspaceId: String) {
    scenario.lastUpdate = OffsetDateTime.now()
    cosmosCoreDatabase
        .getContainer("${organizationId}_scenario_data")
        .upsertItem(
            scenario.asMapWithAdditionalData(workspaceId),
            PartitionKey(scenario.ownerId),
            CosmosItemRequestOptions())
  }

  @EventListener(OrganizationRegistered::class)
  fun onOrganizationRegistered(organizationRegistered: OrganizationRegistered) {
    cosmosCoreDatabase.createContainerIfNotExists(
        CosmosContainerProperties(
            "${organizationRegistered.organizationId}_scenario_data", "/ownerId"))
  }

  @EventListener(OrganizationUnregistered::class)
  @Async("csm-in-process-event-executor")
  fun onOrganizationUnregistered(organizationUnregistered: OrganizationUnregistered) {
    cosmosTemplate.deleteContainer("${organizationUnregistered.organizationId}_scenario_data")
  }

  @EventListener(ScenarioRunStartedForScenario::class)
  fun onScenarioRunStartedForScenario(scenarioRunStarted: ScenarioRunStartedForScenario) {
    logger.debug("onScenarioRunStartedForScenario ${scenarioRunStarted}")
    this.updateScenario(
        scenarioRunStarted.organizationId,
        scenarioRunStarted.workspaceId,
        scenarioRunStarted.scenarioId,
        Scenario(
            lastRun =
                ScenarioLastRun(
                    scenarioRunStarted.scenarioRunData.scenarioRunId,
                    scenarioRunStarted.scenarioRunData.csmSimulationRun,
                    scenarioRunStarted.workflowData.workflowId,
                    scenarioRunStarted.workflowData.workflowName,
                )))
  }

  @EventListener(ScenarioDatasetListChanged::class)
  fun onScenarioDatasetListChanged(scenarioDatasetListChanged: ScenarioDatasetListChanged) {
    logger.debug("onScenarioDatasetListChanged ${scenarioDatasetListChanged}")
    val children =
        this.findAllScenariosByRootId(
            scenarioDatasetListChanged.organizationId,
            scenarioDatasetListChanged.workspaceId,
            scenarioDatasetListChanged.scenarioId)
    children?.forEach {
      it.datasetList = scenarioDatasetListChanged.datasetList
      it.lastUpdate = OffsetDateTime.now()
      upsertScenarioData(
          scenarioDatasetListChanged.organizationId, it, scenarioDatasetListChanged.workspaceId)
    }
  }
}
