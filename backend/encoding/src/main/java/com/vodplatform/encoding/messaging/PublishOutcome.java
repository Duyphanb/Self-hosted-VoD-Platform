package com.vodplatform.encoding.messaging;

/** Only Confirmed permits the caller to record publication; all other outcomes remain relay work. */
public sealed interface PublishOutcome {
    record Confirmed() implements PublishOutcome { }
    record Returned(int replyCode, String replyText) implements PublishOutcome { }
    record Nacked(String reason) implements PublishOutcome { }
    record TimedOut() implements PublishOutcome { }
    record Failed(Kind kind) implements PublishOutcome { }
    enum Kind { UNAVAILABLE, BROKER_ERROR, INTERRUPTED, UNEXPECTED }
}
