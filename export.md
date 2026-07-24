# Generated state transition tables and Mermaid diagrams

Product evaluation of shipped pure APIs + structure-preserving hierarchy/orthogonality.
Tables include declared effects from pure steps (not interpreter side effects).
Stdlib only — no FSM/statechart engines.

## TrafficLight

### TrafficLight transition table

| From | Event | To | Kind | Effects | Note |
| --- | --- | --- | --- | --- | --- |
| Red | Tick | Green | ACCEPTED | LogTransition |  |
| Red | Emergency | Red | ACCEPTED | LogTransition |  |
| Green | Tick | Yellow | ACCEPTED | LogTransition |  |
| Green | Emergency | Red | ACCEPTED | LogTransition |  |
| Yellow | Tick | Red | ACCEPTED | LogTransition |  |
| Yellow | Emergency | Red | ACCEPTED | LogTransition |  |

```mermaid
stateDiagram-v2
  %% TrafficLight (flat product edges)
  Red
  Green
  Yellow
  Red --> Green : Tick
  Red --> Red : Emergency
  Green --> Yellow : Tick
  Green --> Red : Emergency
  Yellow --> Red : Tick
  Yellow --> Red : Emergency
```

## FileProtocol

### FileProtocol transition table

| From | Event | To | Kind | Effects | Note |
| --- | --- | --- | --- | --- | --- |
| Closed | Open | Opened | ACCEPTED | LogTransition |  |
| Closed | Read | Closed | REFUSED | LogRefusal | not in menu |
| Closed | Close | Closed | REFUSED | LogRefusal | not in menu |
| Opened | Open | Opened | REFUSED | LogRefusal | not in menu |
| Opened | Read | Opened | ACCEPTED | LogTransition |  |
| Opened | Close | Closed | ACCEPTED | LogTransition |  |

```mermaid
stateDiagram-v2
  %% FileProtocol (accepted edges; refusals in table) (flat product edges)
  Closed
  Opened
  Closed --> Opened : Open
  Opened --> Opened : Read
  Opened --> Closed : Close
```

## Connection

### Connection transition table

| From | Event | To | Kind | Effects | Note |
| --- | --- | --- | --- | --- | --- |
| Offline | Connect | Idle | ACCEPTED | LogExit(Offline), LogTransition, LogEntry(Online), LogEntry(Idle) |  |
| Offline | Disconnect | Offline | REFUSED | LogRefusal | no state in hierarchy accepted event |
| Offline | StartTransfer | Offline | REFUSED | LogRefusal | no state in hierarchy accepted event |
| Offline | FinishTransfer | Offline | REFUSED | LogRefusal | no state in hierarchy accepted event |
| Offline | Heartbeat | Offline | REFUSED | LogRefusal | no state in hierarchy accepted event |
| Idle | Connect | Idle | REFUSED | LogRefusal | no state in hierarchy accepted event |
| Idle | Disconnect | Offline | ACCEPTED | LogExit(Idle), LogExit(Online), LogTransition, LogEntry(Offline) |  |
| Idle | StartTransfer | Transferring | ACCEPTED | LogExit(Idle), LogTransition, LogEntry(Transferring) |  |
| Idle | FinishTransfer | Idle | REFUSED | LogRefusal | no state in hierarchy accepted event |
| Idle | Heartbeat | Idle | ACCEPTED | IncHeartbeat, LogTransition |  |
| Transferring | Connect | Transferring | REFUSED | LogRefusal | no state in hierarchy accepted event |
| Transferring | Disconnect | Offline | ACCEPTED | LogExit(Transferring), LogExit(Online), LogTransition, LogEntry(Offline) |  |
| Transferring | StartTransfer | Transferring | REFUSED | LogRefusal | no state in hierarchy accepted event |
| Transferring | FinishTransfer | Idle | ACCEPTED | LogExit(Transferring), LogTransition, LogEntry(Idle) |  |
| Transferring | Heartbeat | Transferring | ACCEPTED | IncHeartbeat, LogTransition |  |

```mermaid
stateDiagram-v2
  %% Connection (hierarchical composites + product edges)
  Offline
  state Online {
    Idle
    Transferring
  }
  Offline --> Idle : Connect
  Idle --> Offline : Disconnect
  Idle --> Transferring : StartTransfer
  Idle --> Idle : Heartbeat
  Transferring --> Offline : Disconnect
  Transferring --> Idle : FinishTransfer
  Transferring --> Transferring : Heartbeat
  %% composites: Online
```

## DoorSystem

### DoorSystem transition table

| From | Event | To | Kind | Effects | Note |
| --- | --- | --- | --- | --- | --- |
| Locked | Lock | Locked | ACCEPTED | LogTransition | region=Latch |
| Locked | Unlock | Unlocked | ACCEPTED | LogTransition | region=Latch |
| Unlocked | Lock | Locked | ACCEPTED | LogTransition | region=Latch |
| Unlocked | Unlock | Unlocked | ACCEPTED | LogTransition | region=Latch |
| Closed | OpenDoor | Open | ACCEPTED | LogTransition | region=Swing |
| Closed | CloseDoor | Closed | ACCEPTED | LogTransition | region=Swing |
| Open | OpenDoor | Open | ACCEPTED | LogTransition | region=Swing |
| Open | CloseDoor | Closed | ACCEPTED | LogTransition | region=Swing |

### Latch transition table

| From | Event | To | Kind | Effects | Note |
| --- | --- | --- | --- | --- | --- |
| Locked | Lock | Locked | ACCEPTED | LogTransition |  |
| Locked | Unlock | Unlocked | ACCEPTED | LogTransition |  |
| Unlocked | Lock | Locked | ACCEPTED | LogTransition |  |
| Unlocked | Unlock | Unlocked | ACCEPTED | LogTransition |  |

### Swing transition table

| From | Event | To | Kind | Effects | Note |
| --- | --- | --- | --- | --- | --- |
| Closed | OpenDoor | Open | ACCEPTED | LogTransition |  |
| Closed | CloseDoor | Closed | ACCEPTED | LogTransition |  |
| Open | OpenDoor | Open | ACCEPTED | LogTransition |  |
| Open | CloseDoor | Closed | ACCEPTED | LogTransition |  |

```mermaid
stateDiagram-v2
  %% DoorSystem (orthogonal concurrent regions)
  state DoorSystem {
    state Latch {
      Locked
      Unlocked
      Locked --> Locked : Lock
      Locked --> Unlocked : Unlock
      Unlocked --> Locked : Lock
      Unlocked --> Unlocked : Unlock
    }
    --
    state Swing {
      Closed
      Open
      Closed --> Open : OpenDoor
      Closed --> Closed : CloseDoor
      Open --> Open : OpenDoor
      Open --> Closed : CloseDoor
    }
  }
  note right of DoorSystem
    Container guards (not product states): Open only if Unlocked; Lock only if Closed
  end note
```

<!-- machines: TrafficLight, FileProtocol, Connection, DoorSystem tables=6 -->
