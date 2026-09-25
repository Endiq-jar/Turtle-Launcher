package kr.co.donghyun.flamelauncher.data.mapper

import kr.co.donghyun.flamelauncher.data.instance.InstanceMeta
import kr.co.donghyun.flamelauncher.data.instance.InstanceType as DataInstanceType
import kr.co.donghyun.flamelauncher.domain.model.Instance
import kr.co.donghyun.flamelauncher.domain.model.InstanceType as DomainInstanceType

/** data.instance.InstanceMeta → domain.model.Instance */
fun InstanceMeta.toDomain(): Instance = Instance(
    id = id,
    name = name,
    type = type.toDomain(),
    mcVersion = mcVersion,
    loaderType = loaderType,
    loaderVersion = loaderVersion,
    mainClass = mainClass,
    extraJars = extraJars,
    assetIndexId = assetIndexId,
    iconEmoji = iconEmoji,
    iconPath = iconPath,
    gameJvmArgs = gameJvmArgs,
    gameArgs = gameArgs,
    sourceModId = sourceModId,
    rendererId = rendererId,
)

/** domain.model.Instance → data.instance.InstanceMeta (저장/실행 시 다시 필요할 때) */
fun Instance.toData(): InstanceMeta = InstanceMeta(
    id = id,
    name = name,
    type = type.toData(),
    mcVersion = mcVersion,
    loaderType = loaderType,
    loaderVersion = loaderVersion,
    mainClass = mainClass,
    extraJars = extraJars,
    assetIndexId = assetIndexId,
    iconEmoji = iconEmoji,
    iconPath = iconPath,
    gameJvmArgs = gameJvmArgs,
    gameArgs = gameArgs,
    sourceModId = sourceModId,
    rendererId = rendererId,
)

fun DataInstanceType.toDomain(): DomainInstanceType = when (this) {
    DataInstanceType.VANILLA -> DomainInstanceType.VANILLA
    DataInstanceType.MODPACK -> DomainInstanceType.MODPACK
    DataInstanceType.FABRIC -> DomainInstanceType.FABRIC
}

fun DomainInstanceType.toData(): DataInstanceType = when (this) {
    DomainInstanceType.VANILLA -> DataInstanceType.VANILLA
    DomainInstanceType.MODPACK -> DataInstanceType.MODPACK
    DomainInstanceType.FABRIC -> DataInstanceType.FABRIC
}
