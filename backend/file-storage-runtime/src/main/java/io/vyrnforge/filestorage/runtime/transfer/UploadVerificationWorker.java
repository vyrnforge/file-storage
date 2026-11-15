package io.vyrnforge.filestorage.runtime.transfer;

import io.vyrnforge.filestorage.application.FileApplication.Failure;
import io.vyrnforge.filestorage.persistence.JdbcCompletionWork;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Semaphore;

/** Bounded one-attempt handler, using durable work rather than end-user credentials. */
public final class UploadVerificationWorker {
    public enum Outcome { IDLE, SCAN_QUEUED, RETRY, DEAD, BUSY }
    private final JdbcCompletionWork work;
    private final IntegrityReader reader;
    private final TransferLimits limits;
    private final Clock clock;
    private final Semaphore slots;
    public UploadVerificationWorker(JdbcCompletionWork work,TransferProviderResolver resolver,TransferLimits limits,Clock clock){
        this.work=Objects.requireNonNull(work);this.reader=new IntegrityReader(resolver,limits,clock);
        this.limits=Objects.requireNonNull(limits);this.clock=Objects.requireNonNull(clock);slots=new Semaphore(limits.concurrency());
    }
    public Outcome runOne(){
        if(!slots.tryAcquire())return Outcome.BUSY;
        try{
            var batch=work.claim();
            if(batch.claims().isEmpty())return batch.exhausted()>0?Outcome.DEAD:Outcome.IDLE;
            var claim=batch.claims().getFirst();
            JdbcCompletionWork.Work job;
            try{job=work.load(claim);}
            catch(Failure ex){return work.retry(claim,true)?Outcome.DEAD:Outcome.RETRY;}
            String digest;
            try{
                Duration budget=Duration.between(clock.instant(),claim.expiresAt());
                if(budget.isNegative()||budget.isZero())return Outcome.RETRY;
                if(budget.compareTo(limits.timeout())>0)budget=limits.timeout();
                digest=job.job().matched()?job.job().expectedSha256():reader.measure(job.job(),budget);
            }catch(TransferFailure ex){
                boolean permanent=switch(ex.code()){case INVALID,TOO_LARGE,DENIED->true;default->false;};
                if(!work.retry(claim,permanent))throw new TransferFailure(TransferFailure.Code.CONFLICT);
                return permanent?Outcome.DEAD:Outcome.RETRY;
            }
            work.finish(job,digest);
            return Outcome.SCAN_QUEUED;
        }finally{slots.release();}
    }
}
