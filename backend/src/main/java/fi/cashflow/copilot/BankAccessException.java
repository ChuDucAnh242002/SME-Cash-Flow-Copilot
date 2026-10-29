package fi.cashflow.copilot;

public class BankAccessException extends RuntimeException {
    public BankAccessException(String message) { super(message); }
    public BankAccessException(String message, Throwable cause) { super(message, cause); }
}
