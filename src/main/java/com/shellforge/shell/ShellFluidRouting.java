package com.shellforge.shell;

import com.shellforge.shell.ShellFluidPressureBridge.AppliedPressure;
import com.shellforge.shell.ShellFluidPressureBridge.Endpoint;
import com.shellforge.shell.ShellFluidPressureBridge.FacePressure;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Recomputes routes from native pressure; mirrored pressure is never promoted to a new source. */
public final class ShellFluidRouting {
    public record Link(Endpoint exterior, Endpoint interior) {}
    public record Result(List<AppliedPressure> applied, Map<Endpoint, Float> outputPressure) {}
    private record Route(Endpoint endpoint, boolean intoEndpoint, float pressure, int remaining) {}

    private ShellFluidRouting() {}

    public static Result solve(List<Link> links) {
        Map<Endpoint, List<Endpoint>> crossings = new HashMap<>();
        for (Link link : links) {
            crossings.computeIfAbsent(link.exterior(), ignored -> new ArrayList<>()).add(link.interior());
            crossings.computeIfAbsent(link.interior(), ignored -> new ArrayList<>()).add(link.exterior());
        }
        // Snapshot all native sources before writing any synthetic pressure.
        List<Route> sources = new ArrayList<>();
        crossings.keySet().forEach(endpoint -> {
            FacePressure pressure = ShellFluidPressureBridge.readPipePressure(endpoint.level(), endpoint.pos(), endpoint.side());
            if (pressure.intoEndpoint() > 0) sources.add(new Route(endpoint, true, pressure.intoEndpoint(),
                    ShellFluidPressureBridge.remainingRange(endpoint, true)));
            if (pressure.fromEndpoint() > 0) sources.add(new Route(endpoint, false, pressure.fromEndpoint(),
                    ShellFluidPressureBridge.remainingRange(endpoint, false)));
        });
        List<AppliedPressure> applied = new ArrayList<>();
        Map<Endpoint, Float> output = new HashMap<>();
        for (Route source : sources) {
            ArrayDeque<Route> queue = new ArrayDeque<>();
            Set<Endpoint> crossed = new HashSet<>();
            Set<ShellFluidPressureBridge.PipeNode> visited = new HashSet<>();
            visited.add(new ShellFluidPressureBridge.PipeNode(source.endpoint().level(),
                    source.endpoint().pos().relative(source.endpoint().side())));
            queue.add(source);
            while (!queue.isEmpty()) {
                Route route = queue.remove();
                if (route.remaining() <= 0 || !crossed.add(route.endpoint())) continue;
                List<Endpoint> destinations = crossings.getOrDefault(route.endpoint(), List.of());
                float pressure = route.pressure() / Math.max(1, destinations.size());
                for (Endpoint destination : destinations) {
                    if (!crossed.add(destination)) continue;
                    output.merge(destination, route.intoEndpoint() ? pressure : -pressure, Float::sum);
                    applied.addAll(ShellFluidPressureBridge.mirrorPressure(destination.level(), destination.pos(),
                            destination.side(), route.intoEndpoint(), pressure, route.remaining(), visited,
                            (endpoint, intoEndpoint, value, remaining) -> {
                                if (crossings.containsKey(endpoint) && !crossed.contains(endpoint)) {
                                    queue.add(new Route(endpoint, intoEndpoint, value, remaining));
                                }
                            }));
                }
            }
        }
        return new Result(applied, output);
    }
}
