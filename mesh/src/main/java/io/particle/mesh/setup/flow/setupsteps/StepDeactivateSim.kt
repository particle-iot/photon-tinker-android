package io.particle.mesh.setup.flow.setupsteps

import io.particle.android.sdk.cloud.ParticleCloud
import io.particle.mesh.setup.flow.*
import io.particle.mesh.setup.flow.ExceptionType.ERROR_FATAL
import io.particle.mesh.setup.flow.context.SetupContexts
import io.particle.mesh.setup.flow.retrySimAction
import mu.KotlinLogging


class StepDeactivateSim(
    private val cloud: ParticleCloud,
    private val flowUi: FlowUiDelegate
) : MeshSetupStep() {

    private val log = KotlinLogging.logger {}

    override suspend fun doRunStep(ctxs: SetupContexts, scopes: Scopes) {
        log.info { "Deactivating SIM with ICCID=${ctxs.targetDevice.iccid!!}" }
        flowUi.showGlobalProgressSpinner(true)
        retrySimAction {
            cloud.deactivateSim(ctxs.targetDevice.iccid!!)
        }
    }

    override fun wrapException(cause: Exception): Exception {
        return FailedToDeactivateSimException()
    }
}