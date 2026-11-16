package io.vyrnforge.filestorage.runtime;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;

/** Trusted owner-only regular-file mount. No symlinks, path traversal or environment fallback. */
final class MountedStagingSecrets {
    private final Path root;
    MountedStagingSecrets(Path root){
        try{if(!root.equals(root.toRealPath())||!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw new IOException();
            privateMode(root);this.root=root;
        }catch(IOException|RuntimeException ex){throw unavailable();}
    }
    private static void privateMode(Path path)throws IOException{
        var permissions=Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS);
        if(permissions.stream().anyMatch(p->p.name().startsWith("GROUP_")||p.name().startsWith("OTHERS_")))throw new IOException();
    }
    private Properties read(String ref){
        byte[] bytes=null;
        try{
            String name=StagingBootstrapConfig.reference(ref).substring("ref:staging/file-storage/".length());
            Path file=root.resolve(name);
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException();
            privateMode(file);bytes=StagingBootstrapConfig.read(file,16384);
            return StagingBootstrapConfig.properties(bytes);
        }catch(IOException|RuntimeException ex){throw unavailable();}
        finally{if(bytes!=null)Arrays.fill(bytes,(byte)0);}
    }
    String databasePassword(String ref){
        try{var p=read(ref);String password=StagingBootstrapConfig.take(p,"password");
            if(!p.isEmpty())throw unavailable();return password;
        }catch(RuntimeException ex){throw unavailable();}
    }
    ProviderSecret provider(String ref){
        try{
            var p=read(ref);String access=StagingBootstrapConfig.take(p,"access"),secret=StagingBootstrapConfig.take(p,"secret");
            String token=p.containsKey("token")?StagingBootstrapConfig.take(p,"token"):null;
            if(!p.isEmpty())throw unavailable();
            return new ProviderSecret(access,secret,token);
        }catch(RuntimeException ex){throw unavailable();}
    }
    static IllegalStateException unavailable(){return new IllegalStateException("staging secret unavailable");}
    static final class ProviderSecret implements AutoCloseable{
        private char[] access,secret,token;
        ProviderSecret(String access,String secret,String token){
            this.access=access.toCharArray();this.secret=secret.toCharArray();this.token=token==null?null:token.toCharArray();
        }
        String access(){return new String(Objects.requireNonNull(access));}
        String secret(){return new String(Objects.requireNonNull(secret));}
        String token(){return token==null?null:new String(token);}
        @Override public void close(){if(access!=null)Arrays.fill(access,'\0');if(secret!=null)Arrays.fill(secret,'\0');
            if(token!=null)Arrays.fill(token,'\0');access=null;secret=null;token=null;}
        @Override public String toString(){return "ProviderSecret[redacted]";}
    }
}
