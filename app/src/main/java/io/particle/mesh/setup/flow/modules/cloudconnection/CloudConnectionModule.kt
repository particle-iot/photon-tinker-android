package io.particle.mesh.setup.flow.modules.cloudconnection

import android.arch.lifecycle.LiveData
import android.arch.lifecycle.MutableLiveData
import io.particle.android.sdk.cloud.ParticleCloud
import io.particle.firmwareprotos.ctrl.Network
import io.particle.firmwareprotos.ctrl.Network.InterfaceType
import io.particle.firmwareprotos.ctrl.cloud.Cloud.ConnectionStatus
import io.particle.mesh.common.android.livedata.ClearValueOnInactiveLiveData
import io.particle.mesh.common.android.livedata.castAndPost
import io.particle.mesh.common.android.livedata.liveDataSuspender
import io.particle.mesh.common.android.livedata.setOnMainThread
import io.particle.mesh.common.truthy
import io.particle.mesh.setup.flow.Clearable
import io.particle.mesh.setup.flow.FlowException
import io.particle.mesh.setup.flow.FlowManager
import io.particle.mesh.setup.flow.throwOnErrorOrAbsent
import io.particle.mesh.setup.ui.DialogSpec
import io.particle.sdk.app.R
import io.particle.sdk.app.R.id
import io.particle.sdk.app.R.string
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.withContext
import mu.KotlinLogging


class CloudConnectionModule(
        private val flowManager: FlowManager,
        private val cloud: ParticleCloud
) : Clearable {

    private val log = KotlinLogging.logger {}

    var claimCode: String? = null // FIXME: make this a LiveData?  Where is it used?
    val targetDeviceShouldBeClaimedLD: LiveData<Boolean?> = MutableLiveData()
    val targetOwnedByUserLD: LiveData<Boolean?> = MutableLiveData()
    val targetDeviceNameToAssignLD: LiveData<String?> = MutableLiveData()
    val isTargetDeviceNamedLD: LiveData<Boolean?> = MutableLiveData()
    val targetDeviceEthernetConnectedToCloud: LiveData<Boolean?> = ClearValueOnInactiveLiveData()
    val connectToDeviceCloudButtonClicked: LiveData<Boolean?> = MutableLiveData()

    private var checkedIsTargetClaimedByUser = false
    private var connectedToMeshNetworkAndOwnedUiShown = false
    private var checkEthernetGatewayUiShown = false

    private val targetXceiver
        get() = flowManager.bleConnectionModule.targetDeviceTransceiverLD.value


    override fun clearState() {

        // FIXME: UPDATE THIS WITH ALL VALUES

        claimCode = null
        checkedIsTargetClaimedByUser = false
        connectedToMeshNetworkAndOwnedUiShown = false
        checkEthernetGatewayUiShown = false

        val setToNulls = listOf(
                targetDeviceShouldBeClaimedLD,
                targetOwnedByUserLD,
                targetDeviceNameToAssignLD,
                isTargetDeviceNamedLD,
                connectToDeviceCloudButtonClicked
        )
        for (ld in setToNulls) {
            (ld as MutableLiveData).postValue(null)
        }
    }

    fun updateTargetOwnedByUser(owned: Boolean) {
        log.info { "updateTargetOwnedByUser(): $owned" }
        targetOwnedByUserLD.castAndPost(owned)
    }

    fun updateIsTargetDeviceNamed(named: Boolean) {
        log.info { "updateIsTargetDeviceNamed(): $named" }
        isTargetDeviceNamedLD.castAndPost(named)
    }

    fun updateTargetDeviceNameToAssign(name: String) {
        log.info { "updateTargetDeviceNameToAssign(): $name" }
        targetDeviceNameToAssignLD.castAndPost(name)
    }

    fun updateConnectToDeviceCloudButtonClicked(enteredConnectingScreen: Boolean) {
        log.info { "updateUserEnteredConnectingToDeviceCloudScreen()" }
        connectToDeviceCloudButtonClicked.castAndPost(enteredConnectingScreen)
    }

    suspend fun ensureClaimCodeFetched() {
        log.info { "ensureClaimCodeFetched(), claimCode=$claimCode" }
        if (claimCode == null) {
            log.info { "Fetching new claim code" }
            claimCode = cloud.generateClaimCode().claimCode
        }
    }

    suspend fun ensureEthernetConnectedToCloud() {
        log.info { "ensureEthernetConnectedToCloud()" }
        for (i in 0..14) { // 30 seconds
            delay(500)
            val statusReply = targetXceiver!!.sendGetConnectionStatus().throwOnErrorOrAbsent()
            if (statusReply.status == ConnectionStatus.CONNECTED) {
                (targetDeviceEthernetConnectedToCloud as MutableLiveData).postValue(true)
                return
            }
        }
        throw FlowException("Error ensuring connection to cloud via ethernet")
    }

    // FIXME: where does this belong?
    suspend fun ensureDeviceIsUsingEligibleFirmware() {
        log.info { "ensureDeviceIsUsingEligibleFirmware()" }
        // TODO: TO BE IMPLEMENTED
    }

    suspend fun ensureCheckedIsClaimed(targetDeviceId: String) {
        log.info { "ensureCheckedIsClaimed()" }
        if (checkedIsTargetClaimedByUser) {
            return
        }

        val userOwnsDevice = cloud.userOwnsDevice(targetDeviceId)
        checkedIsTargetClaimedByUser = true
        if (userOwnsDevice) {
            return
        }

        // FIXME: FINISH IMPLEMENTING!


        // FIXME: REMOVE!
        (targetDeviceShouldBeClaimedLD as MutableLiveData).setOnMainThread(true)
        // REMOVE ^^^

        // show dialog
//        val ldSuspender = liveDataSuspender({ flowManager.targetDeviceShouldBeClaimedLD })
//        val shouldClaim = withContext(UI) {
//            flowManager.navigate()
//            ldSuspender.awaitResult()
//        }
    }

    suspend fun ensureSetClaimCode() {
        log.info { "ensureSetClaimCode()" }
        if (!targetDeviceShouldBeClaimedLD.value.truthy()) {
            return
        }

        targetXceiver!!.sendSetClaimCode(claimCode!!).throwOnErrorOrAbsent()
    }

    suspend fun ensureEthernetHasIP() {
        log.info { "ensureEthernetHasIP()" }
        // FIXME: remove?
        suspend fun findEthernetInterface(): Network.InterfaceEntry? {
            val ifaceListReply = targetXceiver!!.sendGetInterfaceList().throwOnErrorOrAbsent()
            return ifaceListReply.interfacesList.firstOrNull { it.type == InterfaceType.ETHERNET }
        }

        val ethernet = findEthernetInterface()
        requireNotNull(ethernet)

        val reply = targetXceiver!!.sendGetInterface(ethernet!!.index).throwOnErrorOrAbsent()
        val iface = reply.`interface`
        for (addyList in listOf(iface.ipv4Config.addressesList, iface.ipv6Config.addressesList)) {

            val address = addyList.firstOrNull {
                it.address.v4.address != null || it.address.v6.address != null
            }
            if (address != null) {
                log.debug { "IP address on ethernet (interface ${ethernet.index}) found: $address" }
                return
            }
        }

        val ldSuspender = liveDataSuspender({ flowManager.dialogResultLD })
        val result = withContext(UI) {
            flowManager.newDialogRequest(DialogSpec(
                    string.p_connecttocloud_xenon_gateway_needs_ethernet,
                    android.R.string.ok
            ))
            ldSuspender.awaitResult()
        }
        log.info { "result from awaiting on 'ethernet must be plugged in dialog: $result" }
        flowManager.clearDialogResult()
        delay(500)
        throw FlowException("Ethernet connection not plugged in; user prompted.")
    }

    suspend fun ensureCheckGatewayUiShown() {
        log.info { "ensureCheckGatewayUiShown()" }
        if (checkEthernetGatewayUiShown) {
            // we've already shown this screen; bail.
            return
        }
        flowManager.navigate(R.id.action_global_checkEthernetGatewayFragment)
        checkEthernetGatewayUiShown = true
        val ldSuspender = liveDataSuspender({connectToDeviceCloudButtonClicked})
        withContext(UI) {
            ldSuspender.awaitResult()
        }
    }

    suspend fun ensureConnectingToDeviceCloudUiShown() {
        flowManager.navigate(R.id.action_global_connectingToDeviceCloudFragment)
    }

    suspend fun ensureTargetDeviceClaimedByUser() {
        log.info { "ensureTargetDeviceClaimedByUser()" }
        if (targetOwnedByUserLD.value.truthy()) {
            return
        }

        suspend fun pollDevicesForNewDevice(deviceId: String): Boolean {
            val idLower = deviceId.toLowerCase()
            for (i in 0..14) { // 30 seconds
                // FIXME: what should the timing be here?
                delay(500)
                val userOwnsDevice = try {
                    cloud.userOwnsDevice(idLower)
                } catch (ex: Exception) {
                    false
                }
                if (userOwnsDevice) {
                    log.info { "Found assigned to user device with ID $deviceId" }
                    return true
                }
                log.info { "No device with ID $deviceId found yet assigned to user" }
            }
            log.warn { "Timed out waiting for user to own a device with ID $deviceId" }
            return false
        }

        // FIXME: is this how we want to allow access to things like the target's device ID?
        val targetDeviceId = flowManager.bleConnectionModule.ensureTargetDeviceId()
        val isInList = pollDevicesForNewDevice(targetDeviceId)
        if (!isInList) {
            throw FlowException("Target device does not appear to be claimed")
        }

        updateTargetOwnedByUser(true)

        if (!connectedToMeshNetworkAndOwnedUiShown) {
            delay(2000)
            connectedToMeshNetworkAndOwnedUiShown = true
        }
    }

    suspend fun ensureTargetDeviceIsNamed() {
        log.info { "ensureTargetDeviceIsNamed()" }
        if (isTargetDeviceNamedLD.value.truthy()) {
            return
        }

        // FIXME: show progress spinner

        val ldSuspender = liveDataSuspender({ targetDeviceNameToAssignLD })
        val nameToAssign = withContext(UI) {
            flowManager.navigate(id.action_global_nameYourDeviceFragment)
            ldSuspender.awaitResult()
        }

        if (nameToAssign == null) {
            throw FlowException("Error ensuring target device is named")
        }

        val targetDeviceId = flowManager.bleConnectionModule.ensureTargetDeviceId()
        val joiner = cloud.getDevice(targetDeviceId)
        joiner.setName(nameToAssign)

        updateIsTargetDeviceNamed(true)
    }



}
