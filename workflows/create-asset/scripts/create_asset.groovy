// create_asset.groovy
//
// Creates a new Collibra asset from workflow form inputs.
//
// Implicit variables injected by Collibra at runtime (see stubs/ for type info):
//   execution  – org.flowable.engine.delegate.DelegateExecution
//   assetApi   – com.collibra.dgc.core.api.AssetApi
//   loggerApi  – com.collibra.dgc.core.api.LoggerApi
//   item       – com.collibra.dgc.workflow.api.bean.BusinessItemBean (the workflow's target resource)
//
// Process variables set by this script (available to downstream tasks):
//   createdAssetId   (String) – UUID of the newly created asset
//   createdAssetName (String) – Name of the newly created asset

import com.collibra.dgc.core.api.dto.instance.asset.AddAssetRequest

// Read form inputs collected on the start event
def assetName   = execution.getVariable('assetName')   as String
def assetTypeId = execution.getVariable('assetTypeId') as String
def domainId    = execution.getVariable('domainId')    as String

if (!assetName || !assetTypeId || !domainId) {
    throw new IllegalArgumentException(
        "Required workflow variables are missing. " +
        "assetName='${assetName}', assetTypeId='${assetTypeId}', domainId='${domainId}'"
    )
}

// Build and execute the create-asset request
def request = AddAssetRequest.builder()
    .name(assetName)
    .typeId(UUID.fromString(assetTypeId))
    .domainId(UUID.fromString(domainId))
    .build()

def createdAsset = assetApi.addAsset(request)

// Store results as process variables for any downstream tasks
execution.setVariable('createdAssetId', createdAsset.getId().toString())
execution.setVariable('createdAssetName', createdAsset.getName())

loggerApi.info("Created asset '${createdAsset.getName()}' [${createdAsset.getId()}]")
