package com.template;

import co.paralleluniverse.fibers.Suspendable;
import com.google.common.collect.ImmutableList;
import net.corda.core.contracts.Command;
import net.corda.core.contracts.StateAndRef;
import net.corda.core.flows.*;
import net.corda.core.identity.Party;
import net.corda.core.node.services.Vault;
import net.corda.core.node.services.vault.QueryCriteria;
import net.corda.core.transactions.SignedTransaction;
import net.corda.core.transactions.TransactionBuilder;
import net.corda.core.utilities.ProgressTracker;
import net.corda.core.utilities.ProgressTracker.Step;
//import org.apache.log4j.Logger;

import java.security.PublicKey;
import java.util.Iterator;
import java.util.List;

import static net.corda.core.contracts.ContractsDSL.requireThat;

public class BankAssess {

    @InitiatingFlow
    @StartableByRPC
    public static class Initiator extends FlowLogic<Void>{
        private final String bondID;
        /* the node running the flow is the bank (these sign the transaction)*/
        private final Party exporter;
        private final Party ukef;
        private final String bankSupplyId;
        private double exporterTurnover;
        private double exporterNet;
        private int bankRiskLevel;
        private double bankCreditScore;

        private final Step PREPARATION = new Step("Retrieve state to amend.");
        //    private final Step GENERATING_UKEF_TRANSACTION = new Step("Generating transaction based on UKEF activity.");
        private final Step GENERATING_BANK_TRANSACTION = new Step("Creating bank transaction.");
        private final Step SIGNING_TRANSACTION = new Step("Signing transaction with our private key.");
        private final Step GATHERING_SIGS = new Step("Gathering the counterparty's signature.") {
            @Override
            public ProgressTracker childProgressTracker() {
                return CollectSignaturesFlow.Companion.tracker();
            }
        };
        private final Step FINALISING_TRANSACTION = new Step("Obtaining notary signature and recording transaction.") {
            @Override
            public ProgressTracker childProgressTracker() {
                return FinalityFlow.Companion.tracker();
            }
        };


        private final ProgressTracker progressTracker = new ProgressTracker(
                GENERATING_BANK_TRANSACTION,
                PREPARATION,
                SIGNING_TRANSACTION,
                GATHERING_SIGS,
                FINALISING_TRANSACTION
        );

        /**
         * @param bankSupplyContractID UUID created internally from the bank
         * @param turnover             exporter turnover
         * @param net                  exporter net income
         * @param riskLevel            [0 - lowest, 5 - highest]
         * @param creditScore          [0.0 lowest - 4.0 highest]
         * @param exporter             party
         * @param ukef                 party
         */
        public Initiator(String bondID, String bankSupplyContractID, Double turnover, Double net, int riskLevel, Double
            creditScore, Party exporter, Party ukef){
            this.bondID = bondID;
            this.exporter = exporter;
            this.ukef = ukef;
            this.bankSupplyId = bankSupplyContractID;
            this.exporterTurnover = turnover;
            this.exporterNet = net;
            this.bankRiskLevel = riskLevel;
            this.bankCreditScore = creditScore;
        }

        @Override
        public ProgressTracker getProgressTracker () {
            return progressTracker;
        }

        @Suspendable
        @Override
        public Void call () throws FlowException {


            //Notary for the transaction
            final Party notary = getServiceHub().getNetworkMapCache().getNotaryIdentities().get(0);

            //Stage1 - generate transaction
            progressTracker.setCurrentStep(PREPARATION);

            //gather previous state to amend
            StateAndRef<UKTFBond> inputState = getUKTFBond(this.bondID);
            UKTFBond inputBond = inputState.getState().getData();

            if (!inputBond.getBank().equals(getOurIdentity())) {
                throw new FlowException("Assessment of exporter bond can only be done by the bank reported in the bond");
            }

            Bond updBond = new Bond(inputBond.getBondValue(), this.bankSupplyId, this.exporterTurnover, this.exporterNet, this.bankRiskLevel, this.bankCreditScore);
            UKTFBond outputBond = inputBond.copy(updBond);

            // Stage 2 - verifying trx
            progressTracker.setCurrentStep(GENERATING_BANK_TRANSACTION);

            List<PublicKey> requiredSigners = ImmutableList.of(getOurIdentity().getOwningKey(), exporter.getOwningKey(), ukef.getOwningKey());
            final Command<UKTFContract.Commands.BankAssess> cmd = new Command<>(new UKTFContract.Commands.BankAssess(), requiredSigners);
            final TransactionBuilder txBuilder = new TransactionBuilder(notary)
                    .addInputState(inputState)
                    .addOutputState(outputBond, UKTFContract.UKTF_CONTRACT_ID)
                    .addCommand(cmd);

            txBuilder.verify(getServiceHub());

            // Stage 3 - signing trx
            progressTracker.setCurrentStep(SIGNING_TRANSACTION);
            final SignedTransaction partSignedTx = getServiceHub().signInitialTransaction(txBuilder);


            //Step 4 - gathering trx signs
            progressTracker.setCurrentStep(GATHERING_SIGS);

            //bank & ukef signatures
            FlowSession exporterSession = initiateFlow(exporter);
            FlowSession ukefSession = initiateFlow(ukef);
            SignedTransaction fullySignedTx = subFlow(new CollectSignaturesFlow(
                    partSignedTx, ImmutableList.of(exporterSession, ukefSession), CollectSignaturesFlow.tracker()));


            //Step 5 - finalising
            progressTracker.setCurrentStep(FINALISING_TRANSACTION);

            subFlow(new FinalityFlow(fullySignedTx));

            return null;
        }


         StateAndRef<UKTFBond> getUKTFBond (String bondID) throws FlowException {
          //  Logger logger = Logger.getLogger("BankAssess");
            //List<StateAndRef<UKTFBond>> bonds = getServiceHub().getVaultService().queryBy(UKTFBond.class, criteria).getStates();
            QueryCriteria.VaultQueryCriteria criteria = new QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED);
            Vault.Page<UKTFBond> results = getServiceHub().getVaultService().queryBy(UKTFBond.class, criteria);
            List<StateAndRef<UKTFBond>> bonds = results.getStates();

          // logger.info("Number of UNCONSUMED bonds " +  bonds.toArray().length);

          //  logger.info("Bond id to find " + bondID);

            Iterator<StateAndRef<UKTFBond>> i = bonds.iterator();
            while (i.hasNext()) {
                StateAndRef<UKTFBond> state = i.next();
                if (state.getState().getData().getBondID().equals(bondID)) {
          //          logger.info("found state");
                    return state;
                }
            }
            throw new FlowException(String.format("Bond with id %s not found", bondID));
        }

    }

    @InitiatedBy(Initiator.class)
    public static class BankAssessResponder extends FlowLogic<Void> {

        private FlowSession bank;

        public BankAssessResponder (FlowSession bank) {
            this.bank = bank;
        }

        @Suspendable
        @Override
        public Void call() throws FlowException {

            class SignExpTxFlow extends SignTransactionFlow {

                private SignExpTxFlow(FlowSession bank, ProgressTracker progressTracker) {
                    super(bank, progressTracker);
                }

                @Override
                protected void checkTransaction(SignedTransaction stx) {
                    requireThat(require -> {
                        int sizeInputs = stx.getTx().getInputs().size();
                        require.using("There should be an input input",  sizeInputs == 1);
                        return null;
                    });
                }
            }


            subFlow(new SignExpTxFlow(bank, SignTransactionFlow.Companion.tracker()));

            return null;
        }
    }

}
