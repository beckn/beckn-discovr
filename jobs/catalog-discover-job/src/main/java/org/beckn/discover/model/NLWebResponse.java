package org.beckn.discover.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * NLWeb Response DTO
 * 
 * Represents the response from NLWeb natural language querying engine.
 */
public class NLWebResponse {

    @JsonProperty("asking_sites")
    private AskingSites askingSites;

    @JsonProperty("user_agent")
    private String userAgent;

    @JsonProperty("data_retention")
    private String dataRetention;

    @JsonProperty("content")
    private List<ContentItem> content;

    @JsonProperty("conversation_id")
    private String conversationId;

    // Default constructor
    public NLWebResponse() {}

    // Getters and Setters
    public AskingSites getAskingSites() {
        return askingSites;
    }

    public void setAskingSites(AskingSites askingSites) {
        this.askingSites = askingSites;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public String getDataRetention() {
        return dataRetention;
    }

    public void setDataRetention(String dataRetention) {
        this.dataRetention = dataRetention;
    }

    public List<ContentItem> getContent() {
        return content;
    }

    public void setContent(List<ContentItem> content) {
        this.content = content;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    @Override
    public String toString() {
        return "NLWebResponse{" +
                "askingSites=" + askingSites +
                ", userAgent='" + userAgent + '\'' +
                ", dataRetention='" + dataRetention + '\'' +
                ", content=" + content +
                ", conversationId='" + conversationId + '\'' +
                '}';
    }

    // Nested AskingSites class
    public static class AskingSites {
        @JsonProperty("message_id")
        private String messageId;

        @JsonProperty("sender_type")
        private String senderType;

        @JsonProperty("timestamp")
        private String timestamp;

        @JsonProperty("content")
        private String content;

        @JsonProperty("conversation_id")
        private String conversationId;

        @JsonProperty("sender_info")
        private SenderInfo senderInfo;

        public AskingSites() {}

        // Getters and Setters
        public String getMessageId() {
            return messageId;
        }

        public void setMessageId(String messageId) {
            this.messageId = messageId;
        }

        public String getSenderType() {
            return senderType;
        }

        public void setSenderType(String senderType) {
            this.senderType = senderType;
        }

        public String getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(String timestamp) {
            this.timestamp = timestamp;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public String getConversationId() {
            return conversationId;
        }

        public void setConversationId(String conversationId) {
            this.conversationId = conversationId;
        }

        public SenderInfo getSenderInfo() {
            return senderInfo;
        }

        public void setSenderInfo(SenderInfo senderInfo) {
            this.senderInfo = senderInfo;
        }

        @Override
        public String toString() {
            return "AskingSites{" +
                    "messageId='" + messageId + '\'' +
                    ", senderType='" + senderType + '\'' +
                    ", timestamp='" + timestamp + '\'' +
                    ", content='" + content + '\'' +
                    ", conversationId='" + conversationId + '\'' +
                    ", senderInfo=" + senderInfo +
                    '}';
        }
    }

    // Nested SenderInfo class
    public static class SenderInfo {
        @JsonProperty("id")
        private String id;

        @JsonProperty("name")
        private String name;

        public SenderInfo() {}

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return "SenderInfo{" +
                    "id='" + id + '\'' +
                    ", name='" + name + '\'' +
                    '}';
        }
    }

    // Nested ContentItem class
    public static class ContentItem {
        @JsonProperty("@type")
        private String type;

        @JsonProperty("url")
        private String url;

        @JsonProperty("name")
        private String name;

        @JsonProperty("site")
        private String site;

        @JsonProperty("siteUrl")
        private String siteUrl;

        @JsonProperty("score")
        private Integer score;

        @JsonProperty("description")
        private String description;

        @JsonProperty("schema_object")
        private SchemaObject schemaObject;

        public ContentItem() {}

        // Getters and Setters
        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getSite() {
            return site;
        }

        public void setSite(String site) {
            this.site = site;
        }

        public String getSiteUrl() {
            return siteUrl;
        }

        public void setSiteUrl(String siteUrl) {
            this.siteUrl = siteUrl;
        }

        public Integer getScore() {
            return score;
        }

        public void setScore(Integer score) {
            this.score = score;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public SchemaObject getSchemaObject() {
            return schemaObject;
        }

        public void setSchemaObject(SchemaObject schemaObject) {
            this.schemaObject = schemaObject;
        }

        @Override
        public String toString() {
            return "ContentItem{" +
                    "type='" + type + '\'' +
                    ", url='" + url + '\'' +
                    ", name='" + name + '\'' +
                    ", site='" + site + '\'' +
                    ", siteUrl='" + siteUrl + '\'' +
                    ", score=" + score +
                    ", description='" + description + '\'' +
                    ", schemaObject=" + schemaObject +
                    '}';
        }
    }

    // Nested SchemaObject class
    public static class SchemaObject {
        @JsonProperty("catalogs")
        private List<Catalog> catalogs;

        @JsonProperty("@id")
        private String id;

        @JsonProperty("name")
        private String name;

        public SchemaObject() {}

        public List<Catalog> getCatalogs() {
            return catalogs;
        }

        public void setCatalogs(List<Catalog> catalogs) {
            this.catalogs = catalogs;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return "SchemaObject{" +
                    "catalogs=" + catalogs +
                    ", id='" + id + '\'' +
                    ", name='" + name + '\'' +
                    '}';
        }
    }
}
