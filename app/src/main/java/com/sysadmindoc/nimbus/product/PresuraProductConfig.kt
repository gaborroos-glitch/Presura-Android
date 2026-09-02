package com.sysadmindoc.nimbus.product

/**
 * Product-facing Presura references. This intentionally does not configure
 * providers or API endpoints; the established ZeusWatch runtime remains active
 * until a dedicated backend migration phase.
 */
object PresuraProductConfig {
    const val displayName = "Presura Weather"
    const val websiteUrl = "https://presura.eu"
    const val sourceRepositoryUrl = "https://github.com/gaborroos-glitch/Presura-Android"
    const val upstreamFoundationRepositoryUrl = "https://github.com/SysAdminDoc/ZeusWatch"
}
