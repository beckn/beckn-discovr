package org.beckn.catalogpublish.logging;

public final class MdcField {
    private MdcField() {}

    public static final String TRANSACTION_ID = "transactionId";
    public static final String MESSAGE_ID     = "messageId";
    public static final String BPP_ID         = "bppId";
    public static final String NETWORK_ID     = "networkId";
    public static final String CATALOG_ID     = "catalogId";
    public static final String BAP_ID         = "bapId";
}
