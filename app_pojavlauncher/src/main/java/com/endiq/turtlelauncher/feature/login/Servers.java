package com.endiq.turtlelauncher.feature.login;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public class Servers {

    @SerializedName("server")
    private List<Server> server;
    @SerializedName("info")
    private String info;

    public List<Server> getServer() { return server; }
    public void setServer(List<Server> server) { this.server = server; }
    public String getInfo() { return info; }
    public void setInfo(String info) { this.info = info; }

    public static class Server {
        @SerializedName("baseUrl")
        private String baseUrl;
        @SerializedName("serverName")
        private String serverName;
        @SerializedName("register")
        private String register;

        @SerializedName("serverType")
        private int serverType = 0;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public String getServerName() { return serverName; }
        public void setServerName(String serverName) { this.serverName = serverName; }

        public String getRegister() { return register; }
        public void setRegister(String register) { this.register = register; }

        public int getServerType() { return serverType; }
        public void setServerType(int serverType) { this.serverType = serverType; }

        /** True if this is a nide8auth / mc-user.com 32-bit server. */
        public boolean isNide8Auth() { return serverType == 1; }

        /**
         * For nide8auth servers the baseUrl is the full mc-user URL,
         * e.g. https://auth.mc-user.com:233/<serverId>
         * This extracts just the serverId portion for the javaagent arg.
         */
        public String getNide8ServerId() {
            if (baseUrl == null) return "";
            String prefix = "https://auth.mc-user.com:233/";
            if (baseUrl.startsWith(prefix)) {
                return baseUrl.substring(prefix.length());
            }
            // Also handle http variant or trailing slash variants
            String altPrefix = "http://auth.mc-user.com:233/";
            if (baseUrl.startsWith(altPrefix)) {
                return baseUrl.substring(altPrefix.length());
            }
            // Fallback: return the raw baseUrl segment after last /
            int idx = baseUrl.lastIndexOf('/');
            return idx >= 0 ? baseUrl.substring(idx + 1) : baseUrl;
        }
    }
}
