package io.vyrnforge.filestorage.s3;

import io.vyrnforge.filestorage.domain.StorageModel.*;
import io.vyrnforge.filestorage.spi.StorageProvider;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/** Explicit credential/endpoint factory; no SDK default credentials or region chain. */
public final class S3StagingFactory {
    private S3StagingFactory(){}
    public static S3StorageProvider open(Target target,Location location,String access,String secret,String token,
            Path spool,long maxBytes,Duration timeout){
        if(target.provider()!=Provider.S3||!(target.addressing()==Addressing.PATH||target.addressing()==Addressing.VIRTUAL_HOST)
            ||access==null||access.isBlank()||secret==null||secret.isBlank())throw new StorageProvider.StorageFailure(StorageProvider.ErrorCode.INVALID);
        S3Client client=null;S3StorageProvider provider=null;
        try{
            var credentials=token==null?AwsBasicCredentials.create(access,secret):AwsSessionCredentials.create(access,secret,token);
            client=S3Client.builder().endpointOverride(target.endpoint()).region(Region.of(target.region()))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(target.addressing()==Addressing.PATH).build())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClientBuilder(ApacheHttpClient.builder().connectionTimeout(timeout).socketTimeout(timeout)
                    .connectionAcquisitionTimeout(timeout).maxConnections(2))
                .overrideConfiguration(c->c.retryPolicy(RetryPolicy.none()).apiCallTimeout(timeout)).build();
            provider=new S3StorageProvider(client,Map.of(location.id(),location),spool,maxBytes,1,timeout,1,Duration.ofMinutes(1));
            provider.validateUnversioned(new StorageProvider.Call(timeout));return provider;
        }catch(RuntimeException ex){
            try{if(provider!=null)provider.close();else if(client!=null)client.close();}catch(RuntimeException ignored){}
            throw new StorageProvider.StorageFailure(StorageProvider.ErrorCode.TRANSIENT);
        }
    }
}
