package com.library.loans.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@ConditionalOnProperty(name="loans.saga.recovery-enabled",havingValue="true",matchIfMissing=true)
public class SagaRecoveryScheduler {
    private static final Logger log=LoggerFactory.getLogger(SagaRecoveryScheduler.class);
    private final SagaRecovery recovery;
    public SagaRecoveryScheduler(SagaRecovery recovery) { this.recovery=recovery; }
    @Scheduled(fixedDelayString="${loans.saga.recovery-interval-ms:5000}")
    public void recover() {
        try { int count=recovery.reconcile(); if(count>0) log.info("Compensacoes solicitadas: count={}",count); }
        catch(RuntimeException failure) { log.warn("Falha na reconciliacao: type={}",failure.getClass().getSimpleName()); }
    }
}
