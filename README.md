# ShellForge / 集成传动

**ShellForge** is a NeoForge addon for [Create](https://www.curseforge.com/minecraft/mc-mods/create) that lets you **package entire machine builds into a single shell block**. The internal mechanics run in a remote [Sable](https://modrinth.com/mod/sable) sublevel, while the main world interacts with them through **per-face interfaces** for stress, redstone, and fluids.

**集成传动** 是一个面向 Create 的 NeoForge 模组：将一整段机械结构压缩为一个外壳方块，内部逻辑在 Sable 子关卡中运行，外壳各面通过接口与主世界桥接应力、红石与流体。

| | |
|---|---|
| **Mod ID** | `shellforge` |
| **Minecraft** | 1.21.1 |
| **Loader** | NeoForge 21.1+ |
| **Required** | Create, Sable |

---

## Features

- **Structure packaging** — Copy a region into a **Shell Blueprint**, then place it anywhere as a **Shell Block** that preserves all internal blocks and block entities.
- **Sable sublevels** — Packed structures live in an isolated sublevel plot (remote Y), not in the main world chunk space.
- **Per-face I/O** — Each shell face can expose independent endpoints:
  - **Stress Interface** — Bidirectional Create kinetic / stress bridging (RPM + SU).
  - **Redstone Interface** — Analog redstone in and out (0–15).
  - **Fluid Interface** — Pipe fluids through the shell boundary.
- **Automatic direction** — Stress flow follows Create's `hasSource()` rules: external motor drives the sublevel, otherwise the sublevel drives external consumers.
- **Multi-face stress** — Multiple faces can import or export stress on the same internal network; capacity and load feedback are split across active faces to avoid double-counting.
- **Wireless redstone bridge** — Create **Redstone Links** inside a shell can relay to links near the shell block in the main world (and vice versa).
- **Kinetic angle sync** — Shaft angles stay aligned across the shell boundary, including spring / sequenced gearshift edge cases.

---

## Blocks & Items

| Block / Item | Purpose |
|---|---|
| **Shell Block** | The placed shell in the main world. Bridges all bound endpoints each tick. |
| **Shell Blueprint** | Holds a copied structure definition. Right-click a block face to place the shell. |
| **Face Marker** | Marks which shell face an adjacent interface belongs to during `/shellforge copy`. Not copied into the definition. |
| **Stress Interface** | Sublevel endpoint for Create kinetic networks. Place inside the structure, facing outward. |
| **Redstone Interface** | Sublevel endpoint for redstone signals. |
| **Fluid Interface** | Sublevel endpoint for fluid pipes / tanks. |
| **Shell Core** | Internal shell metadata holder (used by the shell system). |

All items are available in the **ShellForge** creative tab.

---

## Quick Start

### 1. Build a machine with interfaces

Inside your future shell region:

1. Build your Create (or other) machine as usual.
2. On each face that should connect to the outside world, place a **Stress / Redstone / Fluid Interface** on the **boundary** of the selection (or use a **Face Marker** next to an interface to assign a face explicitly).
3. **Face markers** are optional but recommended when an interface is not on the outer boundary, or when you need a specific face assignment.

### 2. Copy the region

Requires operator permission (level 2):

```
/shellforge copy <x1> <y1> <z1> <x2> <y2> <z2>
```

Optional face overrides (when auto-discovery is not enough):

```
/shellforge copy <x1> <y1> <z1> <x2> <y2> <z2> --north stress --south redstone --up fluid
```

This gives you a **Shell Blueprint** item and creates a template sublevel for the copied structure.

### 3. Place the shell

Right-click the **Shell Blueprint** on the block face where the shell should attach. A **Shell Block** appears in the main world; its internal logic runs in the linked Sable sublevel.

Connect external Create shafts, redstone, or pipes to the corresponding faces of the shell block.

---

## Commands

| Command | Description |
|---|---|
| `/shellforge copy <pos1> <pos2> [face flags]` | Copy a region into a blueprint. Face flags: `--<north\|south\|east\|west\|up\|down> <stress\|redstone\|fluid>` |
| `/shellforge debug` | Show whether placement debug projection is enabled. |
| `/shellforge debug on` | Log sublevel projection positions when placing blueprints. |
| `/shellforge debug off` | Disable placement debug (default). |

All commands require permission level **2** (operator).

---

## How Bridging Works

### Stress

Each stress-bound shell face gets a hidden **PerFaceStressHub** (Create kinetic block entity) on that face. The hub talks to the sublevel **Stress Interface**:

- **Import** (external source → sublevel): injects RPM and stress capacity per RPM into the internal network.
- **Export** (sublevel → external): publishes remaining internal capacity as a generator on the external Create network.

When several faces share the same internal kinetic network, import load feedback and export capacity are **split among active faces** so totals stay correct.

### Redstone

Shell faces read analog power from external neighbors and forward it to internal **Redstone Interfaces**. Internal signals aggregate back to the shell face output. Feedback loops on the shell face are suppressed.

### Fluids

Each fluid-bound face exposes a merged **IFluidHandler** that delegates to internal **Fluid Interface** block(s). Adjacent Create fluid pipes and pumps use their native transport. Internal pumping also drives direct output into adjacent containers or Create-style open ends, without requiring an exterior pump. Open-end partial buckets are saved with the shell; stopped pumps and blocked destinations retain fluid.

Connected internal fluid pipes can relay a single pump's pressure from one shell face to another. Flow can reverse, and direct exterior endpoints support both pushing and pulling. Disconnected interfaces remain isolated; pressure routes retain a distance budget and suppress feedback.

### Wireless redstone

If the sublevel contains Create **Redstone Links**, transmitters and receivers inside can merge with links in link-range of the shell block in the main world, relaying frequencies across the boundary.

---

## Building from Source

### Prerequisites

- JDK 21+
- Git

### Dependencies (runtime)

ShellForge **requires** these mods at runtime:

- **NeoForge** for Minecraft 1.21.1
- **Create** `6.0.10+`
- **Sable** `1.2.2+` (with sable-companion)

The dev environment pulls Create and Sable via Gradle `localRuntime` dependencies. Optional sibling mods (Simulated, Aeronautics) are referenced from `build.gradle` for local testing only and are **not** required to run ShellForge.

### Run locally

```bash
./gradlew runClient
```

On Windows:

```powershell
.\gradlew runClient
```

Compile only:

```bash
./gradlew compileJava
```

Build the mod JAR:

```bash
./gradlew build
```

Output: `build/libs/shellforge-<version>.jar`

---

## Project Layout

```
src/main/java/com/shellforge/
├── block/              # Shell block, interfaces, face marker
├── block/entity/       # Block entities (shell, stress hub, interfaces)
├── command/            # /shellforge commands
├── content/            # Face-extra kinetic positions, connection forwarding
├── item/               # Shell blueprint
├── mixin/              # Create / Level integration hooks
├── registry/           # Deferred registers
└── shell/              # Copy, sublevel lifecycle, redstone bridge, angle sync
```

---

## Known Issues

- Dev runs may load other kinetic mixins (e.g. from Simulated) that compete with ShellForge's `KineticNetworkMixin` — watch the log if stress behaviour differs between dev and production.
---

## Credits

- [Create](https://www.curseforge.com/minecraft/mc-mods/create) — kinetic, stress, and redstone link systems
- [Sable](https://modrinth.com/mod/sable) — sublevel storage and simulation
