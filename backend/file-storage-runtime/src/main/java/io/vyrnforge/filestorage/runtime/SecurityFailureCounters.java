package io.vyrnforge.filestorage.runtime;

import io.vyrnforge.filestorage.security.SecurityObservation;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/** Process-local trusted operational counters; not an HTTP endpoint, audit record or authorization input.
 * Fixed enum dimensions only. No network I/O, payload, exception, identifier or unbounded key allocation.
 */
public final class SecurityFailureCounters implements SecurityObservation {
    private final Map<Boundary,Map<Reason,LongAdder>> counts;

    public SecurityFailureCounters(){
        var all=new EnumMap<Boundary,Map<Reason,LongAdder>>(Boundary.class);
        for(var boundary:Boundary.values()){
            var reasons=new EnumMap<Reason,LongAdder>(Reason.class);
            for(var reason:Reason.values())reasons.put(reason,new LongAdder());
            all.put(boundary,Map.copyOf(reasons));
        }
        counts=Map.copyOf(all);
    }

    @Override public void record(Boundary boundary,Reason reason){counts.get(boundary).get(reason).increment();}

    /** Non-atomic monotonic observation snapshots for trusted in-process consumers. Reset on restart. */
    public Map<Boundary,Map<Reason,Long>> snapshot(){
        var result=new EnumMap<Boundary,Map<Reason,Long>>(Boundary.class);
        counts.forEach((boundary,counters)->{
            var values=new EnumMap<Reason,Long>(Reason.class);
            counters.forEach((reason,counter)->values.put(reason,counter.sum()));
            result.put(boundary,Map.copyOf(values));
        });
        return Map.copyOf(result);
    }
}
