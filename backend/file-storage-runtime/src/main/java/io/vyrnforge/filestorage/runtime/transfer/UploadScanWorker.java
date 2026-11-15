package io.vyrnforge.filestorage.runtime.transfer;

import io.vyrnforge.filestorage.domain.StorageModel.*;
import io.vyrnforge.filestorage.persistence.JdbcOperations;
import io.vyrnforge.filestorage.persistence.JdbcUploadScan;
import io.vyrnforge.filestorage.spi.StorageProvider;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.Semaphore;

/** Explicit bounded worker handler. Existing operation lease fences commits, not external reads.
 * No scheduler, default scanner, external authority or production role enabled automatically.
 */
public final class UploadScanWorker {
    private final JdbcUploadScan repository;
    private final TransferProviderResolver resolver;
    private final ContentScanner scanner;
    private final TransferLimits limits;
    private final Clock clock;
    private final Semaphore slots;

    public UploadScanWorker(JdbcUploadScan repository,TransferProviderResolver resolver,
                            ContentScanner scanner,TransferLimits limits,Clock clock) {
        this.repository=Objects.requireNonNull(repository);
        this.resolver=Objects.requireNonNull(resolver);
        this.scanner=Objects.requireNonNull(scanner);
        this.limits=Objects.requireNonNull(limits);
        this.clock=Objects.requireNonNull(clock);
        slots=new Semaphore(limits.concurrency());
    }

    public enum Outcome { IDLE, COMPLETED, QUARANTINED, FAILED, RETRY, BUSY }

    /** One bounded claim/attempt per invocation. Scheduler and dead-letter sweep are separate. */
    public Outcome runOne() {
        if(!slots.tryAcquire())return Outcome.BUSY;
        try {
            var batch=repository.claim(1);
            if(batch.claims().isEmpty())return Outcome.IDLE;
            return process(batch.claims().getFirst());
        } finally { slots.release(); }
    }

    private Outcome process(JdbcOperations.Claim claim) {
        var job=repository.load(claim);
        var pin=job.pin();
        if(!clock.instant().isBefore(pin.expiresAt())||pin.size()>limits.maxBytes()){
            repository.fail(job);return Outcome.FAILED;
        }
        var deadline=new Deadline(limits.timeout(),claim.expiresAt());
        ContentScanner.Verdict verdict;
        Content measured;
        try {
            var instruction=new TransferInstruction(pin.caller().tenant(),pin.caller().actor(),pin.caller().consumer(),
                    TransferInstruction.Action.UPLOAD,pin.file(),pin.version(),pin.session(),pin.backend(),pin.revision(),
                    pin.locator(),pin.size(),pin.expiresAt());
            try(var lease=Objects.requireNonNull(resolver.resolve(instruction))){
                var provider=Objects.requireNonNull(lease.provider());
                var head=provider.head(pin.locator(),new StorageProvider.Call(deadline.remaining()));
                if(head==null||head.size()!=pin.size())throw new Mismatch();
                try(var raw=provider.get(pin.locator(),new StorageProvider.Call(deadline.remaining()))){
                    var stream=new MeasuredStream(raw,pin.size(),limits.bufferBytes(),deadline);
                    verdict=Objects.requireNonNull(scanner.scan(stream,pin.size(),deadline.remaining()));
                    if(verdict.result()==Scan.UNAVAILABLE)throw new Retry();
                    String digest=stream.complete();
                    if(!digest.equals(job.digest())||(head.sha256()!=null&&!digest.equals(head.sha256())))throw new Mismatch();
                    String mime=verdict.detectedMime();
                    if(mime==null||mime.isBlank()||mime.length()>255||mime.codePoints().anyMatch(Character::isISOControl))
                        throw new Mismatch();
                    if(verdict.result()==Scan.CLEAN&&!mime.equals(job.declaredMime()))throw new Mismatch();
                    measured=new Content(pin.size(),digest,mime);
                }
            }
            deadline.remaining();
        } catch(Mismatch ex){
            repository.fail(job);return Outcome.FAILED;
        } catch(IOException|RuntimeException ex){
            if(!repository.retry(claim))throw new TransferFailure(TransferFailure.Code.CONFLICT);
            repository.reconcileDead(pin.caller().tenant(),pin.session());
            return Outcome.RETRY;
        }
        // Do not catch/ack a failed metadata commit. Lease recovery handles uncertain outcomes.
        if(!clock.instant().isBefore(pin.expiresAt())){
            repository.fail(job);return Outcome.FAILED;
        }
        repository.finish(job,measured,verdict.result());
        return verdict.result()==Scan.CLEAN?Outcome.COMPLETED:Outcome.QUARANTINED;
    }

    private static final class Mismatch extends RuntimeException {}
    private static final class Retry extends RuntimeException {}

    private final class Deadline {
        private final long start=System.nanoTime();
        private final long budget;
        private final java.time.Instant claimExpiry;
        Deadline(Duration timeout,java.time.Instant claimExpiry){
            budget=timeout.toNanos();this.claimExpiry=claimExpiry;
        }
        Duration remaining(){
            long nanos=budget-(System.nanoTime()-start);
            Duration lease=Duration.between(clock.instant(),claimExpiry);
            if(nanos<=0||lease.isZero()||lease.isNegative()||Thread.currentThread().isInterrupted())throw new Retry();
            Duration local=Duration.ofNanos(nanos);
            return local.compareTo(lease)<0?local:lease;
        }
    }

    /** Scanner cannot bypass measurement via skip/reset or retain provider ownership. */
    private static final class MeasuredStream extends InputStream {
        private final InputStream raw;
        private final MessageDigest hash;
        private final int bufferBound;
        private final Deadline deadline;
        private long remaining;
        private boolean eof;
        MeasuredStream(InputStream raw,long size,int bufferBound,Deadline deadline){
            this.raw=Objects.requireNonNull(raw);remaining=size;this.bufferBound=bufferBound;this.deadline=deadline;
            try{hash=MessageDigest.getInstance("SHA-256");}
            catch(NoSuchAlgorithmException ex){throw new IllegalStateException("digest unavailable");}
        }
        @Override public int read()throws IOException{
            byte[] one=new byte[1];int n=read(one,0,1);return n<0?-1:Byte.toUnsignedInt(one[0]);
        }
        @Override public int read(byte[] bytes,int offset,int length)throws IOException{
            Objects.checkFromIndexSize(offset,length,bytes.length);
            if(length==0)return 0;
            deadline.remaining();
            if(eof)return -1;
            if(remaining==0){
                if(raw.read()!=-1)throw new Mismatch();
                eof=true;return -1;
            }
            int n=raw.read(bytes,offset,(int)Math.min(Math.min(length,bufferBound),remaining));
            if(n<=0)throw new Mismatch();
            hash.update(bytes,offset,n);remaining-=n;
            deadline.remaining();return n;
        }
        String complete()throws IOException{
            if(remaining!=0||read()!=-1)throw new Mismatch();
            return HexFormat.of().formatHex(hash.digest());
        }
    }
}
