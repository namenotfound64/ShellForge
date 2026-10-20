# Fluid regression checks

Run the automated checks with Java 21:

```powershell
.\gradlew.bat test
```

The unit tests use real Create pipe connections and tanks with mocked world
lookups. They cover directional pressure, branches, loops, distance limits,
disconnected/unloaded neighbors, pressure wipe lifecycle, and tank simulation.
They also check ordinary sided capability queries and neighbor-update feedback.
They do not replace an in-game test of Sable sublevel ticking.

## Through-shell routes

A single pump can drive exterior face A -> interface A -> connected internal
Create pipes -> interface B -> exterior face B. Reversing the pump reverses
transport. Interfaces retain separate buffers; only Create's internal network
moves fluid between them. A disconnected internal path never becomes a shortcut.

Routes are rebuilt from native pressure snapshots before any synthetic pressure
is applied. Each source has its own visited pipe/endpoint set and remaining
distance budget, so returning through a shell face does not create another
source or reset the pump range. For standard Create pumps, the upstream pipe
distance is deducted; other pressure providers use the configured range at entry.
This routing graph covers the bindings of one shell, not arbitrary chains of shells.

## Shell outlets

An internal pump pushing into an interface drives the corresponding exterior
face. Adjacent Create pipes retain native transport. Without a pipe, the shell
pushes into a sided fluid capability, or uses Create's OpenEndedPipe for an open
end. The direct transfer budget is half the absolute routed net pressure in
mB per tick, with a minimum of 1. Positive pressure pushes outward; negative
pressure pulls from an adjacent container or open end into the interface.
Zero pressure means no active exchange. Repeated maintenance calls in one
world tick do not duplicate exchange.

Open ends retain and serialize Create's partial bucket, including across shell
movement. They obey Create's source-placement setting and open-pipe effects.
A different fluid waits while an earlier fluid remains in that partial bucket.
Blocked, full, incompatible, and unloaded destinations retain source fluid.

## In-game checks

1. Bind a fluid interface to a shell face. Connect an exterior Create pump and
   water tank, and an interior pipe and receiving tank. Check continuous transfer
   after the 8100 mB interface buffer fills, rather than periodic pipe restarts.
2. Reverse the pump and verify return flow. Stop the pump and confirm transfer
   settles to a stop; restart it and verify transfer resumes.
3. Bind separate faces to separate internal interfaces. Use water on one face
   and lava on another; verify the fluids stay isolated. Query a bound shell face
   using a normal sided fluid capability, without a special BlockPos subtype.
4. Test a T junction and a pipe loop. Check flow directions and confirm the loop
   does not create pressure after the source pump stops.
5. Break and replace an adjacent pipe, then remove and replace the shell. Check
   that the new network does not inherit negative or lingering mirrored pressure.
6. Save/reload and unload/reload the relevant chunks. Check buffer contents are
   retained and fluid transfer resumes. Include a pump inside the sublevel and a
   shell whose position changes.
7. Bind multiple internal interfaces to one exterior face. Verify their buffers
   remain accessible without pressure growing on successive ticks.
8. With only an internal pump, test the exterior face against a tank, an open
   space, and a pipe leading to a tank. Verify output without an exterior pump.
   Stop/reverse/restart the internal pump, fill the destination, and block the
   outlet. Confirm stopped/blocked output retains the interface contents.
9. Save with a partially filled open-end bucket, then reload and resume pumping.
   Confirm the prior partial amount contributes to the next placed source block.
10. Connect A and B internally with pipes only. Put a pump outside A and a tank
    outside B. Verify flow in both directions, then remove an internal pipe and
    verify transfer stops after fluid already downstream has settled.
11. Repeat with an exterior pipe on B, a full output tank, an internal branch,
    and an exterior loop returning to A. Check conservation and stop/restart.
