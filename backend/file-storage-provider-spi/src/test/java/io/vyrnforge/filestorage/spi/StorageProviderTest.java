package io.vyrnforge.filestorage.spi;
import io.vyrnforge.filestorage.domain.StorageModel.*;
import io.vyrnforge.filestorage.spi.StorageProvider.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StorageProviderTest {
    /** Synthetic fixture only, no production guarantees. */
    static final class Memory implements StorageProvider {
        private final Map<Locator,byte[]> objects=new HashMap<>();
        public Set<Capability> capabilities(){return Set.of();}
        public Metadata put(Locator l,InputStream input,long expected,Call call){
            try {
                if(expected<0||expected>1024)throw new StorageFailure(ErrorCode.INVALID);
                byte[] bytes=input.readNBytes((int)expected+1);
                if(bytes.length!=expected)throw new StorageFailure(ErrorCode.INVALID);
                if(objects.putIfAbsent(l,bytes)!=null)throw new StorageFailure(ErrorCode.CONFLICT);
                return head(l,call);
            }catch(IOException e){throw new StorageFailure(ErrorCode.TRANSIENT);}
        }
        public InputStream get(Locator l,Call call){return new ByteArrayInputStream(require(l));}
        public Metadata head(Locator l,Call call){return new Metadata(require(l).length,null,"application/octet-stream");}
        public void delete(Locator l,Call call){objects.remove(l);}
        public Page list(LocationId location,String prefix,String cursor,int limit,Call call){
            if(limit<1)throw new StorageFailure(ErrorCode.INVALID);
            var all=objects.keySet().stream().filter(l->l.location().equals(location)&&l.objectKey().startsWith(prefix))
                .filter(l->cursor==null||l.objectKey().compareTo(cursor)>0).sorted(Comparator.comparing(Locator::objectKey)).toList();
            var page=all.stream().limit(limit).toList();
            return new Page(page,all.size()>limit?page.getLast().objectKey():null);
        }
        byte[] require(Locator l){byte[] b=objects.get(l);if(b==null)throw new StorageFailure(ErrorCode.NOT_FOUND);return b;}
    }
    @Test void optionalSigningAndMultipartFailExplicitly(){
        StorageProvider p=new Memory();var a=new Locator(new LocationId(new UUID(0,1)),"a",null);
        var call=new Call(Duration.ofSeconds(1));var expiry=Instant.parse("2026-01-01T00:01:00Z");
        var upload=new Multipart(a,"synthetic-handle");
        assertEquals(ErrorCode.UNSUPPORTED,assertThrows(StorageFailure.class,()->p.signUpload(a,1,expiry,call)).code());
        assertEquals(ErrorCode.UNSUPPORTED,assertThrows(StorageFailure.class,()->p.signDownload(a,expiry,call)).code());
        assertEquals(ErrorCode.UNSUPPORTED,assertThrows(StorageFailure.class,()->p.beginMultipart(a,call)).code());
        assertEquals(ErrorCode.UNSUPPORTED,assertThrows(StorageFailure.class,()->p.putPart(upload,1,new ByteArrayInputStream(new byte[]{1}),1,call)).code());
        assertEquals(ErrorCode.UNSUPPORTED,assertThrows(StorageFailure.class,()->p.completeMultipart(upload,List.of(new Part(1,"synthetic")),call)).code());
        assertEquals(ErrorCode.UNSUPPORTED,assertThrows(StorageFailure.class,()->p.abortMultipart(upload,call)).code());
        assertFalse(upload.toString().contains("synthetic"));
    }
    @Test void a08_neutralPortAndOptionalCapability() throws IOException {
        StorageProvider p=new Memory();var loc=new LocationId(new UUID(0,1));var a=new Locator(loc,"a",null);var b=new Locator(loc,"b",null);
        var call=new Call(Duration.ofSeconds(1));assertEquals(Set.of(),p.capabilities());
        assertEquals(3,p.put(a,new ByteArrayInputStream(new byte[]{1,2,3}),3,call).size());
        assertEquals(ErrorCode.CONFLICT,assertThrows(StorageFailure.class,()->p.put(a,new ByteArrayInputStream(new byte[]{1}),1,call)).code());
        try(var in=p.get(a,call)){assertArrayEquals(new byte[]{1,2,3},in.readAllBytes());}
        p.put(b,new ByteArrayInputStream(new byte[]{4}),1,call);
        var page=p.list(loc,"",null,1,call);assertEquals(List.of(a),page.objects());assertEquals(List.of(b),p.list(loc,"",page.nextCursor(),1,call).objects());
        assertEquals(ErrorCode.UNSUPPORTED,assertThrows(StorageFailure.class,()->p.copy(a,b,call)).code());
        p.delete(a,call);p.delete(a,call);assertEquals(ErrorCode.NOT_FOUND,assertThrows(StorageFailure.class,()->p.head(a,call)).code());
        assertThrows(IllegalArgumentException.class,()->new Call(Duration.ZERO));
        for(var m:StorageProvider.class.getMethods()){
            assertFalse(m.toGenericString().contains("software.amazon"));assertFalse(m.toGenericString().contains("com.aliyun"));
        }
    }
}
