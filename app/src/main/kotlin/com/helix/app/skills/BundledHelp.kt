package com.helix.app.skills

import android.content.Context
import android.content.pm.ApplicationInfo
import com.helix.app.DistributionModuleRegistry
import com.helix.extensions.skills.BuiltInSkills
import com.helix.extensions.skills.HelixHelpIdentity
import com.helix.extensions.skills.SkillDocument

/** PackageManager is the authority even for an isolated validation applicationId. */
internal fun bundledSkills(context: Context): List<SkillDocument> {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    return BuiltInSkills.documents(
        helpIdentity =
            HelixHelpIdentity(
                versionName = info.versionName,
                versionCode = info.longVersionCode,
                distribution = DistributionModuleRegistry.EDITION,
                applicationId = context.packageName,
                debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            ),
    )
}
