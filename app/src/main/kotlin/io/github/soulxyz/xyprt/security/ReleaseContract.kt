package io.github.soulxyz.xyprt.security

import io.github.soulxyz.xyprt.BuildConfig

/**
 * Neutral release hook kept in public source on purpose.
 *
 * Private release tooling may inject build-specific shadow metadata around this contract, but real
 * canary generation rules, registry credentials and detection definitions must never live here.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class BuildContract

@BuildContract
object ReleaseContract {
    val channel: String get() = BuildConfig.DISTRIBUTION_CHANNEL
    val buildEdition: String get() = BuildConfig.BUILD_EDITION
    val contractId: String get() = BuildConfig.BUILD_CONTRACT_ID

    val channelLabel: String
        get() = when (channel.lowercase()) {
            "cocreator", "sponsor" -> "共创版"
            "internal", "beta" -> "内测版"
            else -> "社区版"
        }
}
