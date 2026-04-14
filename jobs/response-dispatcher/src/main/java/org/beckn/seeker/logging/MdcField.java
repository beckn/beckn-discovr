package org.beckn.seeker.logging;

public final class MdcField {
    private MdcField() {}

    public static final String TRANSACTION_ID = "transactionId";
    public static final String MESSAGE_ID     = "messageId";
    public static final String BAP_ID         = "bapId";
    public static final String BAP_URI        = "bapUri";
    public static final String BPP_ID         = "bppId";
    public static final String BPP_URI        = "bppUri";
    public static final String NETWORK_ID     = "networkId";
    public static final String ACTION         = "action";
    public static final String TAGS           = "tags";
}
