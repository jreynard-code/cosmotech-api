// Copyright (c) Cosmo Tech.
// Licensed under the MIT license.
package com.cosmotech.dataset

import com.azure.cosmos.models.CosmosContainerProperties
import com.azure.cosmos.models.CosmosQueryRequestOptions
import com.azure.cosmos.models.SqlParameter
import com.azure.cosmos.models.SqlQuerySpec
import com.cosmotech.api.AbstractCosmosBackedService
import com.cosmotech.api.events.ConnectorRemoved
import com.cosmotech.api.events.ConnectorRemovedForOrganization
import com.cosmotech.api.events.OrganizationRegistered
import com.cosmotech.api.events.OrganizationUnregistered
import com.cosmotech.api.utils.changed
import com.cosmotech.api.utils.findAll
import com.cosmotech.api.utils.findByIdOrThrow
import com.cosmotech.api.utils.toDomain
import com.cosmotech.connector.api.ConnectorApiService
import com.cosmotech.dataset.api.DatasetApiService
import com.cosmotech.dataset.domain.Dataset
import com.cosmotech.dataset.domain.DatasetCompatibility
import com.cosmotech.dataset.domain.DatasetCopyParameters
import com.cosmotech.organization.api.OrganizationApiService
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(name = ["csm.platform.vendor"], havingValue = "azure", matchIfMissing = true)
class DatasetServiceImpl(
    private val organizationService: OrganizationApiService,
    private val connectorService: ConnectorApiService
) : AbstractCosmosBackedService(), DatasetApiService {
  override fun findAllDatasets(organizationId: String) =
      cosmosTemplate.findAll<Dataset>("${organizationId}_datasets")

  override fun findDatasetById(organizationId: String, datasetId: String): Dataset =
      cosmosTemplate.findByIdOrThrow(
          "${organizationId}_datasets",
          datasetId,
          "Dataset $datasetId not found in organization $organizationId")

  override fun removeAllDatasetCompatibilityElements(organizationId: String, datasetId: String) {
    val dataset = findDatasetById(organizationId, datasetId)
    if (!dataset.compatibility.isNullOrEmpty()) {
      dataset.compatibility = listOf()
      cosmosTemplate.upsert("${organizationId}_datasets", dataset)
    }
  }

  override fun createDataset(organizationId: String, dataset: Dataset): Dataset {
    if (dataset.name.isNullOrBlank()) {
      throw IllegalArgumentException("Name cannot be null or blank")
    }
    if (dataset.connector == null || dataset.connector!!.id.isNullOrBlank()) {
      throw IllegalArgumentException("Connector or its ID cannot be null or blank")
    }
    val existingConnector = connectorService.findConnectorById(dataset.connector!!.id!!)
    logger.debug("Found connector: {}", existingConnector)

    return cosmosTemplate.insert(
        "${organizationId}_datasets", dataset.copy(id = idGenerator.generate("dataset")))
        ?: throw IllegalArgumentException("No Dataset returned in response: $dataset")
  }

  override fun deleteDataset(organizationId: String, datasetId: String) {
    cosmosTemplate.deleteEntity(
        "${organizationId}_datasets", findDatasetById(organizationId, datasetId))
  }

  override fun updateDataset(organizationId: String, datasetId: String, dataset: Dataset): Dataset {
    val existingDataset = findDatasetById(organizationId, datasetId)

    var hasChanged = false
    if (dataset.name != null && dataset.changed(existingDataset) { name }) {
      existingDataset.name = dataset.name
      hasChanged = true
    }
    if (dataset.description != null && dataset.changed(existingDataset) { description }) {
      existingDataset.description = dataset.description
      hasChanged = true
    }
    if (dataset.connector != null && dataset.changed(existingDataset) { connector }) {
      // TODO Validate connector ID
      existingDataset.connector = dataset.connector
      hasChanged = true
    }
    // TODO Allow to change the ownerId as well, but only the owner can transfer the ownership

    if (dataset.tags != null && dataset.tags?.toSet() != existingDataset.tags?.toSet()) {
      existingDataset.tags = dataset.tags
      hasChanged = true
    }

    if (dataset.compatibility != null &&
        dataset.compatibility?.toSet() != existingDataset.compatibility?.toSet()) {
      existingDataset.compatibility = dataset.compatibility
      hasChanged = true
    }

    return if (hasChanged) {
      cosmosTemplate.upsertAndReturnEntity("${organizationId}_datasets", existingDataset)
    } else {
      existingDataset
    }
  }

  override fun addOrReplaceDatasetCompatibilityElements(
      organizationId: String,
      datasetId: String,
      datasetCompatibility: List<DatasetCompatibility>
  ): List<DatasetCompatibility> {
    if (datasetCompatibility.isEmpty()) {
      return datasetCompatibility
    }

    val existingDataset = findDatasetById(organizationId, datasetId)
    val datasetCompatibilityMap =
        existingDataset
            .compatibility
            ?.associateBy { "${it.solutionKey}-${it.minimumVersion}-${it.maximumVersion}" }
            ?.toMutableMap()
            ?: mutableMapOf()
    datasetCompatibilityMap.putAll(
        datasetCompatibility.filter { it.solutionKey.isNotBlank() }.associateBy {
          "${it.solutionKey}-${it.minimumVersion}-${it.maximumVersion}"
        })
    existingDataset.compatibility = datasetCompatibilityMap.values.toList()
    cosmosTemplate.upsertAndReturnEntity("${organizationId}_datasets", existingDataset)

    return datasetCompatibility
  }

  override fun copyDataset(
      organizationId: String,
      datasetCopyParameters: DatasetCopyParameters
  ): DatasetCopyParameters {
    TODO("Not yet implemented")
  }

  @EventListener(OrganizationRegistered::class)
  fun onOrganizationRegistered(organizationRegistered: OrganizationRegistered) {
    cosmosCoreDatabase.createContainerIfNotExists(
        CosmosContainerProperties("${organizationRegistered.organizationId}_datasets", "/id"))
  }

  @EventListener(OrganizationUnregistered::class)
  @Async("csm-in-process-event-executor")
  fun onOrganizationUnregistered(organizationUnregistered: OrganizationUnregistered) {
    cosmosTemplate.deleteContainer("${organizationUnregistered.organizationId}_datasets")
  }
}
