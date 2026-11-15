package io.vyrnforge.filestorage.runtime.transfer;

import io.vyrnforge.filestorage.runtime.RuntimeRole;
import io.vyrnforge.filestorage.persistence.JdbcUploadRecovery;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Explicit bounded worker tick; no default import or production readiness claim. */
@Configuration(proxyBeanMethods=false)
@EnableScheduling
public class UploadWorkerConfiguration {
    @Bean
    Tick uploadWorkerTick(UploadVerificationWorker verify,UploadScanWorker scan,JdbcUploadRecovery recovery,@Value("${fs.role}") String role,
                          @Value("${fs.upload-worker.delay-ms}") long delay){
        var runtimeRole=RuntimeRole.parse(role);
        if(runtimeRole!=RuntimeRole.WORKER&&runtimeRole!=RuntimeRole.COMBINED)
            throw new IllegalStateException("upload workers require WORKER or COMBINED role");
        if(delay<10||delay>3600000)throw new IllegalArgumentException("invalid worker delay");
        return new Tick(verify,scan,recovery);
    }
    public static final class Tick {
        private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(Tick.class);
        private final UploadVerificationWorker verify;
        private final UploadScanWorker scan;
        private final JdbcUploadRecovery recovery;
        Tick(UploadVerificationWorker verify,UploadScanWorker scan,JdbcUploadRecovery recovery){this.verify=verify;this.scan=scan;this.recovery=recovery;}
        /** Fixed delay, at most one verification, scan and recovery attempt per tick. */
        @Scheduled(fixedDelayString="${fs.upload-worker.delay-ms}",initialDelayString="${fs.upload-worker.delay-ms}")
        public void run(){
            try{recovery.runOne();}catch(RuntimeException ignored){LOG.warn("Upload recovery tick failed; durable recovery remains pending");}
            try{verify.runOne();}catch(RuntimeException ignored){LOG.warn("Upload verification tick failed; durable lease recovery applies");}
            try{scan.runOne();}catch(RuntimeException ignored){LOG.warn("Upload scan tick failed; durable lease recovery applies");}
        }
    }
}
