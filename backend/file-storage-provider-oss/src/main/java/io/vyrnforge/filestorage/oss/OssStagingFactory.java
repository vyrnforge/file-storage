package io.vyrnforge.filestorage.oss;

import io.vyrnforge.filestorage.domain.StorageModel.*;
import io.vyrnforge.filestorage.spi.StorageProvider;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.common.comm.SignVersion;

/** Native OSS V4 explicit factory. No default credential chain or redirect fallback. */
public final class OssStagingFactory {
    private OssStagingFactory(){}
    public static OssStorageProvider open(Target target,Location location,String access,String secret,String token,
            Path spool,long maxBytes,Duration timeout){
        if(target.provider()!=Provider.OSS||target.addressing()!=Addressing.VIRTUAL_HOST
            ||access==null||access.isBlank()||secret==null||secret.isBlank())throw new StorageProvider.StorageFailure(StorageProvider.ErrorCode.INVALID);
        OSS client=null;OssStorageProvider provider=null;
        try{
            int ms=Math.toIntExact(Math.max(1,timeout.toMillis()));
            var config=new ClientBuilderConfiguration();config.setSignatureVersion(SignVersion.V4);
            config.setSLDEnabled(false);config.setSupportCname(false);
            config.setConnectionTimeout(ms);config.setConnectionRequestTimeout(ms);config.setSocketTimeout(ms);
            config.setRequestTimeoutEnabled(true);config.setRequestTimeout(ms);config.setMaxConnections(2);
            config.setMaxErrorRetry(0);config.setCrcCheckEnabled(true);config.setRedirectEnable(false);
            var credentials=token==null?new DefaultCredentialProvider(access,secret):new DefaultCredentialProvider(access,secret,token);
            client=OSSClientBuilder.create().endpoint(target.endpoint().toString()).region(target.region())
                .credentialsProvider(credentials).clientConfiguration(config).build();
            provider=new OssStorageProvider(client,Map.of(location.id(),location),spool,maxBytes,1,timeout,1,Duration.ofMinutes(1),1);
            provider.validateUnversioned(new StorageProvider.Call(timeout));return provider;
        }catch(RuntimeException ex){
            try{if(provider!=null)provider.close();else if(client!=null)client.shutdown();}catch(RuntimeException ignored){}
            throw new StorageProvider.StorageFailure(StorageProvider.ErrorCode.TRANSIENT);
        }
    }
}
