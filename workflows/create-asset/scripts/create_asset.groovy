// create_asset.groovy
//
// Creates a new Collibra asset from variables set by the createAssetForm user task.
//
// Process variables consumed (set by the form):
//   assetName   (String) – full name of the new asset
//   assetTypeId (String) – UUID of the asset type, stored as String by the form picker
//   domainId    (String) – UUID of the target domain, stored as String by the form picker
//
// Process variables produced (available to downstream tasks):
//   createdAssetId   (String) – UUID of the newly created asset
//   createdAssetName (String) – display name of the newly created asset

import com.collibra.dgc.core.api.dto.instance.asset.AddAssetRequest

def assetName   = execution.getVariable('assetName')   as String
def assetTypeId = execution.getVariable('assetTypeId') as String
def domainId    = execution.getVariable('domainId')    as String

def request = AddAssetRequest.builder()
    .name(assetName)
    .typeId(string2Uuid(assetTypeId))
    .domainId(string2Uuid(domainId))
    .build()

def createdAsset = assetApi.addAsset(request)

execution.setVariable('createdAssetId', createdAsset.getId().toString())
execution.setVariable('createdAssetName', createdAsset.getName())

loggerApi.info("Created asset '${createdAsset.getName()}' [${createdAsset.getId()}]")
