package com.factory.repair.statemachine;

import com.factory.repair.exception.InvalidStateTransitionException;
import com.factory.repair.model.enums.WorkOrderStatus;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class WorkOrderStateMachine {

    private record StateEventKey(WorkOrderStatus state, WorkOrderEvent event) {}

    private final Map<StateEventKey, WorkOrderStatus> transitions = new HashMap<>();

    public WorkOrderStateMachine() {
        // REPORTED transitions
        register(WorkOrderStatus.REPORTED, WorkOrderEvent.DISPATCH, WorkOrderStatus.DISPATCHED);
        register(WorkOrderStatus.REPORTED, WorkOrderEvent.CANCEL, WorkOrderStatus.CANCELLED);

        // DISPATCHED transitions
        register(WorkOrderStatus.DISPATCHED, WorkOrderEvent.ACCEPT, WorkOrderStatus.ACCEPTED);
        register(WorkOrderStatus.DISPATCHED, WorkOrderEvent.REJECT, WorkOrderStatus.REPORTED);
        register(WorkOrderStatus.DISPATCHED, WorkOrderEvent.TRANSFER, WorkOrderStatus.DISPATCHED);
        register(WorkOrderStatus.DISPATCHED, WorkOrderEvent.CANCEL, WorkOrderStatus.CANCELLED);

        // ACCEPTED transitions
        register(WorkOrderStatus.ACCEPTED, WorkOrderEvent.ARRIVE, WorkOrderStatus.ARRIVED);
        register(WorkOrderStatus.ACCEPTED, WorkOrderEvent.TRANSFER, WorkOrderStatus.DISPATCHED);
        register(WorkOrderStatus.ACCEPTED, WorkOrderEvent.CANCEL, WorkOrderStatus.CANCELLED);

        // ARRIVED transitions
        register(WorkOrderStatus.ARRIVED, WorkOrderEvent.START_REPAIR, WorkOrderStatus.REPAIRING);
        register(WorkOrderStatus.ARRIVED, WorkOrderEvent.TRANSFER, WorkOrderStatus.DISPATCHED);

        // REPAIRING transitions
        register(WorkOrderStatus.REPAIRING, WorkOrderEvent.APPLY_SPARE_PART, WorkOrderStatus.REPAIRING);
        register(WorkOrderStatus.REPAIRING, WorkOrderEvent.PAUSE, WorkOrderStatus.PAUSED);
        register(WorkOrderStatus.REPAIRING, WorkOrderEvent.COMPLETE, WorkOrderStatus.COMPLETED);
        register(WorkOrderStatus.REPAIRING, WorkOrderEvent.TRANSFER, WorkOrderStatus.DISPATCHED);
        register(WorkOrderStatus.REPAIRING, WorkOrderEvent.ESCALATE, WorkOrderStatus.REPORTED);

        // PAUSED transitions
        register(WorkOrderStatus.PAUSED, WorkOrderEvent.RESUME, WorkOrderStatus.REPAIRING);
        register(WorkOrderStatus.PAUSED, WorkOrderEvent.CANCEL, WorkOrderStatus.CANCELLED);
        register(WorkOrderStatus.PAUSED, WorkOrderEvent.TRANSFER, WorkOrderStatus.DISPATCHED);

        // COMPLETED transitions
        register(WorkOrderStatus.COMPLETED, WorkOrderEvent.CLOSE, WorkOrderStatus.CLOSED);
        register(WorkOrderStatus.COMPLETED, WorkOrderEvent.REOPEN, WorkOrderStatus.REPORTED);

        // CLOSED transitions
        register(WorkOrderStatus.CLOSED, WorkOrderEvent.REOPEN, WorkOrderStatus.REPORTED);
    }

    private void register(WorkOrderStatus from, WorkOrderEvent event, WorkOrderStatus to) {
        transitions.put(new StateEventKey(from, event), to);
    }

    public WorkOrderStatus fire(WorkOrderStatus currentState, WorkOrderEvent event) {
        WorkOrderStatus target = transitions.get(new StateEventKey(currentState, event));
        if (target == null) {
            throw new InvalidStateTransitionException(currentState.name(), event.name());
        }
        return target;
    }

    public boolean canFire(WorkOrderStatus currentState, WorkOrderEvent event) {
        return transitions.containsKey(new StateEventKey(currentState, event));
    }

    public Set<WorkOrderEvent> availableEvents(WorkOrderStatus currentState) {
        Set<WorkOrderEvent> events = new HashSet<>();
        for (Map.Entry<StateEventKey, WorkOrderStatus> entry : transitions.entrySet()) {
            if (entry.getKey().state() == currentState) {
                events.add(entry.getKey().event());
            }
        }
        return events;
    }
}
