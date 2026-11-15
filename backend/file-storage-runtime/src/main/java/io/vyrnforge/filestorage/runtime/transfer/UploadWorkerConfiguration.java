package io.vyrnforge.filestorage.runtime.transfer;

import io.vyrnforge.filestorage.runtime.RuntimeRole;
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
    Tick uploadWorkerTick(UploadVerificationWorker verify,UploadScanWorker scan,@Value("${fs.role}") String role,
                          @Value("${fs.upload-worker.delay-ms}") long delay){
        var runtimeRole=RuntimeRole.parse(role);
        if(runtimeRole!=RuntimeRole.WORKER&&runtimeRole!=RuntimeRole.COMBINED)
            throw new IllegalStateException("upload workers require WORKER or COMBINED role");
        if(delay<10||delay>3600000)throw new IllegalArgumentException("invalid worker delay");
        return new Tick(verify,scan);
    }
    public static final class Tick {
        private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(Tick.class);
        private final UploadVerificationWorker verify;
        private final UploadScanWorker scan;
        Tick(UploadVerificationWorker verify,UploadScanWorker scan){this.verify=verify;this.scan=scan;}
        /** Fixed delay, at most one verification and one scan attempt per tick. */
        @Scheduled(fixedDelayString="${fs.upload-worker.delay-ms}",initialDelayString="${fs.upload-worker.delay-ms}")
        public void run(){
            try{verify.runOne();}catch(RuntimeException ignored){LOG.warn("Upload verification tick failed; durable lease recovery applies");}
            try{scan.runOne();}catch(RuntimeException ignored){LOG.warn("Upload scan tick failed; durable lease recovery applies");}
        }
    }
}
