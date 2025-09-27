package br.com.lolmatchmaking.backend.dto;

public class LcuInfo {
    private String host;
    private String detectedHost;
    private Integer port;
    private String protocol;
    private String password;
    private String timestamp;

    public LcuInfo() {}

    // getters and setters
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public String getDetectedHost() { return detectedHost; }
    public void setDetectedHost(String detectedHost) { this.detectedHost = detectedHost; }
    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }
    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
}

