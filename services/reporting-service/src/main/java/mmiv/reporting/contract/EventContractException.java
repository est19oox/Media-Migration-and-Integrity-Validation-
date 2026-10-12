package mmiv.reporting.contract;

public final class EventContractException extends IllegalArgumentException {

    public EventContractException(String message) {
        super(message);
    }

    public EventContractException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}