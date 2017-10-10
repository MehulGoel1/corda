package net.corda.ptflows.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Amount
import net.corda.core.contracts.InsufficientBalanceException
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.Party
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.DEFAULT_PAGE_NUM
import net.corda.core.node.services.vault.PageSpecification
import net.corda.core.node.services.vault.QueryCriteria.VaultQueryCriteria
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.ProgressTracker
import net.corda.ptflows.contracts.asset.PtCash
import net.corda.ptflows.contracts.asset.PtCashSelection
import net.corda.ptflows.issuedBy
import java.util.*

/**
 * Initiates a flow that produces an cash exit transaction.
 *
 * @param amount the amount of a currency to remove from the ledger.
 * @param issuerRef the reference on the issued currency. Added to the node's legal identity to determine the
 * issuer.
 */
@StartableByRPC
class PtCashExitFlow(private val amount: Amount<Currency>,
                   private val issuerRef: OpaqueBytes,
                   progressTracker: ProgressTracker) : AbstractPtCashFlow<AbstractPtCashFlow.Result>(progressTracker) {
    constructor(amount: Amount<Currency>, issueRef: OpaqueBytes) : this(amount, issueRef, tracker())
    constructor(request: ExitRequest) : this(request.amount, request.issueRef, tracker())

    companion object {
        fun tracker() = ProgressTracker(GENERATING_TX, SIGNING_TX, FINALISING_TX)
    }

    /**
     * @return the signed transaction, and a mapping of parties to new anonymous identities generated
     * (for this flow this map is always empty).
     */
    @Suspendable
    @Throws(PtCashException::class)
    override fun call(): AbstractPtCashFlow.Result {
        progressTracker.currentStep = GENERATING_TX
        val builder = TransactionBuilder(notary = null)
        val issuer = ourIdentity.ref(issuerRef)
        val exitStates = PtCashSelection
                .getInstance { serviceHub.jdbcSession().metaData }
                .unconsumedCashStatesForSpending(serviceHub, amount, setOf(issuer.party), builder.notary, builder.lockId, setOf(issuer.reference))
        val signers = try {
            PtCash().generateExit(
                    builder,
                    amount.issuedBy(issuer),
                    exitStates)
        } catch (e: InsufficientBalanceException) {
            throw PtCashException("Exiting more cash than exists", e)
        }

        // Work out who the owners of the burnt states were (specify page size so we don't silently drop any if > DEFAULT_PAGE_SIZE)
        val inputStates = serviceHub.vaultQueryService.queryBy<PtCash.State>(VaultQueryCriteria(stateRefs = builder.inputStates()),
                                                                           PageSpecification(pageNumber = DEFAULT_PAGE_NUM, pageSize = builder.inputStates().size)).states

        // TODO: Is it safe to drop participants we don't know how to contact? Does not knowing how to contact them
        //       count as a reason to fail?
        val participants: Set<Party> = inputStates
                .mapNotNull { serviceHub.identityService.wellKnownPartyFromAnonymous(it.state.data.owner) }
                .toSet()
        // Sign transaction
        progressTracker.currentStep = SIGNING_TX
        val tx = serviceHub.signInitialTransaction(builder, signers)

        // Commit the transaction
        progressTracker.currentStep = FINALISING_TX
        val notarised = finaliseTx(tx, participants, "Unable to notarise exit")
        return Result(notarised, null)
    }

    @CordaSerializable
    class ExitRequest(amount: Amount<Currency>, val issueRef: OpaqueBytes) : AbstractRequest(amount)
}
