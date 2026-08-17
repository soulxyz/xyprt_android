package io.github.soulxyz.xyprt.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirm != null) runCatching { context.startActivity(confirm) }
                    .onFailure { Toast.makeText(context, "请在系统安装界面完成更新", Toast.LENGTH_LONG).show() }
            }
            PackageInstaller.STATUS_SUCCESS -> Toast.makeText(context, "口袋小印已更新", Toast.LENGTH_SHORT).show()
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                Toast.makeText(context, "安装没有完成${if (msg.isNotBlank()) "：$msg" else "（状态 $status）"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        const val ACTION_INSTALL_STATUS = "io.github.soulxyz.xyprt.UPDATE_INSTALL_STATUS"
        const val EXTRA_VERSION_CODE = "versionCode"
    }
}
