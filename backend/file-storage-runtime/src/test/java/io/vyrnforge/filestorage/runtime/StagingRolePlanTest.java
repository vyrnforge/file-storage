package io.vyrnforge.filestorage.runtime;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class StagingRolePlanTest {
    @Test void reviewOnlyPlanMatchesAllElevenVerifiedProfileMatrices()throws Exception{
        var lines=Files.readAllLines(Path.of("../../docs/deployment/staging-role-plan.sql"));
        var kinds=List.of(IsolatedRoleProfiles.Kind.METADATA_API,IsolatedRoleProfiles.Kind.LOGICAL,
            IsolatedRoleProfiles.Kind.PURGE_PREPARATION,IsolatedRoleProfiles.Kind.PLACEMENT,IsolatedRoleProfiles.Kind.BACKEND_ONLY,
            IsolatedRoleProfiles.Kind.GRANT_READ,IsolatedRoleProfiles.Kind.READINESS,IsolatedRoleProfiles.Kind.TRANSFER,
            IsolatedRoleProfiles.Kind.COMPLETION_WORKER,IsolatedRoleProfiles.Kind.GOVERNANCE,IsolatedRoleProfiles.Kind.DISPATCHER);
        var expected=new HashSet<String>();var roles=RuntimeDatabaseRoles.Role.values();
        for(int i=0;i<roles.length;i++){
            String group="fs_staging_"+roles[i].name().toLowerCase(Locale.ROOT)+"_profile";
            expected.addAll(new IsolatedRoleProfiles(kinds.get(i)).stagingGrantStatements(group));
            assertTrue(lines.contains("CREATE ROLE "+group+" NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;"));
        }
        var actual=lines.stream().filter(x->x.startsWith("GRANT ")).toList();
        assertEquals(expected,new HashSet<>(actual));assertEquals(expected.size(),actual.size());
        assertEquals("ROLLBACK;",lines.getLast());assertTrue(lines.stream().noneMatch(x->x.startsWith("COMMIT")));
    }
}
