// Copyright (c) Cosmo Tech.
// Licensed under the MIT license.
package com.cosmotech.connector.azure

import com.azure.cosmos.models.CosmosContainerProperties
import com.cosmotech.api.azure.CsmAzureService
import com.cosmotech.api.azure.findAll
import com.cosmotech.api.azure.findByIdOrThrow
import com.cosmotech.api.events.ConnectorRemoved
import com.cosmotech.api.exceptions.CsmAccessForbiddenException
import com.cosmotech.api.utils.getCurrentAuthenticatedUserName
import com.cosmotech.connector.api.ConnectorApiService
import com.cosmotech.connector.domain.Connector
import javax.annotation.PostConstruct
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(name = ["csm.platform.vendor"], havingValue = "azure", matchIfMissing = true)
internal class ConnectorServiceImpl : CsmAzureService(), ConnectorApiService {

  private lateinit var coreConnectorContainer: String

  @PostConstruct
  fun initService() {
    this.coreConnectorContainer =
        csmPlatformProperties.azure!!.cosmos.coreDatabase.connectors.container
    cosmosCoreDatabase.createContainerIfNotExists(
        CosmosContainerProperties(coreConnectorContainer, "/id"))
  }

  override fun findAllConnectors() = cosmosTemplate.findAll<Connector>(coreConnectorContainer)

  override fun findConnectorById(connectorId: String): Connector =
      cosmosTemplate.findByIdOrThrow(coreConnectorContainer, connectorId)

  override fun registerConnector(connector: Connector): Connector {
    if (connector.azureManagedIdentity == true &&
        connector.azureAuthenticationWithCustomerAppRegistration == true) {
      throw IllegalArgumentException(
          "Don't know which authentication mechanism to use to connect " +
              "against Azure services. " +
              "Both azureManagedIdentity and azureAuthenticationWithCustomerAppRegistration " +
              "cannot be set to true")
    }
    return cosmosTemplate.insert(
        coreConnectorContainer,
        connector.copy(
            id = idGenerator.generate("connector"), ownerId = getCurrentAuthenticatedUserName()))
        ?: throw IllegalStateException("No connector returned in response: $connector")
  }

  override fun unregisterConnector(connectorId: String) {
    val connector = this.findConnectorById(connectorId)
    if (connector.ownerId != getCurrentAuthenticatedUserName()) {
      // TODO Only the owner or an admin should be able to perform this operation
      throw CsmAccessForbiddenException("You are not allowed to delete this Resource")
    }
    cosmosTemplate.deleteEntity(coreConnectorContainer, connector)
    this.eventPublisher.publishEvent(ConnectorRemoved(this, connectorId))
  }
}
