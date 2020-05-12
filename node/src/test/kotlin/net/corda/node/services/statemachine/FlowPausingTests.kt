package net.corda.node.services.statemachine

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.HospitalizeFlowException
import net.corda.core.flows.StateMachineRunId
import net.corda.core.identity.CordaX500Name
import net.corda.core.utilities.getOrThrow
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.BOB_NAME
import net.corda.testing.core.makeUnique
import net.corda.testing.node.internal.InternalMockNetwork
import net.corda.testing.node.internal.InternalMockNodeParameters
import net.corda.testing.node.internal.TestStartedNode
import net.corda.testing.node.internal.startFlow
import org.junit.Before
import org.junit.Test
import java.time.Duration
import kotlin.reflect.jvm.javaMethod
import kotlin.reflect.jvm.reflect
import kotlin.test.assertEquals

class FlowPausingTests {

    private lateinit var mockNet: InternalMockNetwork
    private lateinit var aliceNode: TestStartedNode
    private lateinit var bobNode: TestStartedNode

    @Before
    fun setUpMockNet() {
        mockNet = InternalMockNetwork()
        aliceNode = mockNet.createNode(InternalMockNodeParameters(legalName = ALICE_NAME))
        bobNode = mockNet.createNode(InternalMockNodeParameters(legalName = BOB_NAME))
    }

    @Test
    fun `Hospitalized flow can be paused and resumed`() {
        val flow = aliceNode.services.startFlow(HospitalizingFlow())
        assertEquals(true, aliceNode.smm.waitForFlowToBeHospitalised(flow.id))
        assertEquals(true, aliceNode.smm.markFlowsAsPaused(flow.id))
        aliceNode.database.transaction {
            val checkpoint = aliceNode.internals.checkpointStorage.getCheckpoint(flow.id)
            assertEquals(Checkpoint.FlowStatus.PAUSED, checkpoint!!.status)
        }
        val restartedAlice = mockNet.restartNode(aliceNode)
        assertEquals(0, restartedAlice.smm.snapshot().size)
        assertEquals(false, restartedAlice.smm.waitForFlowToBeHospitalised(flow.id))
        restartedAlice.smm.unPauseFlow(flow.id)
        assertEquals(true, restartedAlice.smm.waitForFlowToBeHospitalised(flow.id))
    }

    @Test
    fun `Checkpointing flow can be paused and resumed if the statemachine is stopped`() {
        val flow = aliceNode.services.startFlow(CheckpointingFlow())
        aliceNode.smm.stop(0)
        assertEquals(true, aliceNode.smm.markFlowsAsPaused(flow.id))
        val restartedAlice = mockNet.restartNode(aliceNode)
        assertEquals(0, restartedAlice.smm.snapshot().size)
        assertEquals(true, restartedAlice.smm.unPauseFlow(flow.id))
        assertEquals(1, restartedAlice.smm.snapshot().size)
    }

    fun StateMachineManager.waitForFlowToBeHospitalised(id: StateMachineRunId) : Boolean {
        for (i in 0..100) {
            if (this.flowHospital.contains(id)) return true
            Thread.sleep(10)
        }
        return false
    }

    internal class HospitalizingFlow(): FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            throw HospitalizeFlowException("Something went wrong.")
        }
    }

    internal class CheckpointingFlow(): FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            sleep(Duration.ofSeconds(5))
            sleep(Duration.ofSeconds(5))
        }
    }
}