# Generated state transition tables and Mermaid diagrams

Product evaluation of shipped pure APIs + structure-preserving hierarchy/orthogonality.
Stdlib only — no FSM/statechart engines.

## TrafficLight

### TrafficLight transition table

| From | Event | To | Kind | Note |
| --- | --- | --- | --- | --- |
| Red | Tick | Green | ACCEPTED |  |
| Red | Emergency | Red | ACCEPTED |  |
| Green | Tick | Yellow | ACCEPTED |  |
| Green | Emergency | Red | ACCEPTED |  |
| Yellow | Tick | Red | ACCEPTED |  |
| Yellow | Emergency | Red | ACCEPTED |  |

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

| From | Event | To | Kind | Note |
| --- | --- | --- | --- | --- |
| Closed | Open | Opened | ACCEPTED |  |
| Closed | Read | Closed | REFUSED | not in menu |
| Closed | Close | Closed | REFUSED | not in menu |
| Opened | Open | Opened | REFUSED | not in menu |
| Opened | Read | Opened | ACCEPTED |  |
| Opened | Close | Closed | ACCEPTED |  |

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

| From | Event | To | Kind | Note |
| --- | --- | --- | --- | --- |
| Offline | Connect | Idle | ACCEPTED |  |
| Offline | Disconnect | Offline | REFUSED | no state in hierarchy accepted event |
| Offline | StartTransfer | Offline | REFUSED | no state in hierarchy accepted event |
| Offline | FinishTransfer | Offline | REFUSED | no state in hierarchy accepted event |
| Offline | Heartbeat | Offline | REFUSED | no state in hierarchy accepted event |
| Idle | Connect | Idle | REFUSED | no state in hierarchy accepted event |
| Idle | Disconnect | Offline | ACCEPTED |  |
| Idle | StartTransfer | Transferring | ACCEPTED |  |
| Idle | FinishTransfer | Idle | REFUSED | no state in hierarchy accepted event |
| Idle | Heartbeat | Idle | ACCEPTED |  |
| Transferring | Connect | Transferring | REFUSED | no state in hierarchy accepted event |
| Transferring | Disconnect | Offline | ACCEPTED |  |
| Transferring | StartTransfer | Transferring | REFUSED | no state in hierarchy accepted event |
| Transferring | FinishTransfer | Idle | ACCEPTED |  |
| Transferring | Heartbeat | Transferring | ACCEPTED |  |

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

| From | Event | To | Kind | Note |
| --- | --- | --- | --- | --- |
| Locked | Lock | Locked | ACCEPTED | region=Latch |
| Locked | Unlock | Unlocked | ACCEPTED | region=Latch |
| Unlocked | Lock | Locked | ACCEPTED | region=Latch |
| Unlocked | Unlock | Unlocked | ACCEPTED | region=Latch |
| Closed | OpenDoor | Open | ACCEPTED | region=Swing |
| Closed | CloseDoor | Closed | ACCEPTED | region=Swing |
| Open | OpenDoor | Open | ACCEPTED | region=Swing |
| Open | CloseDoor | Closed | ACCEPTED | region=Swing |

### Latch transition table

| From | Event | To | Kind | Note |
| --- | --- | --- | --- | --- |
| Locked | Lock | Locked | ACCEPTED |  |
| Locked | Unlock | Unlocked | ACCEPTED |  |
| Unlocked | Lock | Locked | ACCEPTED |  |
| Unlocked | Unlock | Unlocked | ACCEPTED |  |

### Swing transition table

| From | Event | To | Kind | Note |
| --- | --- | --- | --- | --- |
| Closed | OpenDoor | Open | ACCEPTED |  |
| Closed | CloseDoor | Closed | ACCEPTED |  |
| Open | OpenDoor | Open | ACCEPTED |  |
| Open | CloseDoor | Closed | ACCEPTED |  |

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
