package com.topjohnwu.magisk.ui.superuser

import android.content.pm.PackageManager
import android.content.res.Resources
import com.skoumal.teanity.databinding.ComparableRvItem
import com.skoumal.teanity.extensions.applySchedulers
import com.skoumal.teanity.extensions.subscribeK
import com.skoumal.teanity.rxbus.RxBus
import com.skoumal.teanity.util.DiffObservableList
import com.skoumal.teanity.viewevents.SnackbarEvent
import com.topjohnwu.magisk.BR
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.data.database.MagiskDB
import com.topjohnwu.magisk.model.entity.Policy
import com.topjohnwu.magisk.model.entity.recycler.PolicyRvItem
import com.topjohnwu.magisk.model.events.PolicyEnableEvent
import com.topjohnwu.magisk.model.events.PolicyUpdateEvent
import com.topjohnwu.magisk.ui.base.MagiskViewModel
import com.topjohnwu.magisk.utils.FingerprintHelper
import com.topjohnwu.magisk.utils.toggle
import com.topjohnwu.magisk.view.dialogs.CustomAlertDialog
import com.topjohnwu.magisk.view.dialogs.FingerprintAuthDialog
import io.reactivex.Single
import me.tatarka.bindingcollectionadapter2.ItemBinding

class SuperuserViewModel(
    private val database: MagiskDB,
    private val packageManager: PackageManager,
    private val resources: Resources,
    rxBus: RxBus
) : MagiskViewModel() {

    val items = DiffObservableList(ComparableRvItem.callback)
    val itemBinding = ItemBinding.of<ComparableRvItem<*>> { itemBinding, _, item ->
        item.bind(itemBinding)
        itemBinding.bindExtra(BR.viewModel, this@SuperuserViewModel)
    }

    private var ignoreNext: PolicyRvItem? = null

    init {
        rxBus.register<PolicyEnableEvent>()
            .subscribeK { togglePolicy(it.item, it.enable) }
            .add()
        rxBus.register<PolicyUpdateEvent>()
            .subscribeK { updatePolicy(it) }
            .add()

        updatePolicies()
    }

    fun updatePolicies() {
        Single.fromCallable { database.policyList }
            .flattenAsFlowable { it }
            .map { PolicyRvItem(it, it.info.loadIcon(packageManager)) }
            .toList()
            .applySchedulers()
            .applyViewModel(this)
            .subscribeK { items.update(it) }
            .add()
    }

    fun deletePressed(item: PolicyRvItem) {
        fun updateState() = deletePolicy(item.item)
            .subscribeK { items.remove(item) }
            .add()

        withView {
            if (FingerprintHelper.useFingerprint()) {
                FingerprintAuthDialog(this) { updateState() }.show()
            } else {
                CustomAlertDialog(this)
                    .setTitle(R.string.su_revoke_title)
                    .setMessage(getString(R.string.su_revoke_msg, item.item.appName))
                    .setPositiveButton(R.string.yes) { _, _ -> updateState() }
                    .setNegativeButton(R.string.no_thanks, null)
                    .setCancelable(true)
                    .show()
            }
        }
    }

    private fun updatePolicy(it: PolicyUpdateEvent) = when (it) {
        is PolicyUpdateEvent.Notification -> updatePolicy(it.item) {
            val textId = if (it.logging) R.string.su_snack_notif_on else R.string.su_snack_notif_off
            val text = resources.getString(textId).format(it.appName)
            SnackbarEvent(text).publish()
        }
        is PolicyUpdateEvent.Log -> updatePolicy(it.item) {
            val textId =
                if (it.notification) R.string.su_snack_log_on else R.string.su_snack_log_off
            val text = resources.getString(textId).format(it.appName)
            SnackbarEvent(text).publish()
        }
    }

    private fun updatePolicy(item: PolicyRvItem, onSuccess: (Policy) -> Unit) =
        updatePolicy(item.item)
            .subscribeK { onSuccess(it) }
            .add()

    private fun togglePolicy(item: PolicyRvItem, enable: Boolean) {
        fun updateState() {
            item.item.policy = if (enable) Policy.ALLOW else Policy.DENY

            updatePolicy(item.item)
                .map { it.policy == Policy.ALLOW }
                .subscribeK {
                    val textId = if (it) R.string.su_snack_grant else R.string.su_snack_deny
                    val text = resources.getString(textId).format(item.item.appName)
                    SnackbarEvent(text).publish()
                }
                .add()
        }

        if (FingerprintHelper.useFingerprint()) {
            withView {
                FingerprintAuthDialog(this, { updateState() }, {
                    ignoreNext = item
                    item.isEnabled.toggle()
                }).show()
            }
        } else {
            updateState()
        }
    }

    private fun updatePolicy(policy: Policy) =
        Single.fromCallable { database.updatePolicy(policy); policy }
            .applySchedulers()

    private fun deletePolicy(policy: Policy) =
        Single.fromCallable { database.deletePolicy(policy); policy }
            .applySchedulers()

}