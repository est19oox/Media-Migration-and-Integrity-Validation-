package mmiv.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConfigurationProperties(prefix = "discovery")
public class DiscoveryProperties {

    private String root;
    private List<String> supportedFileTypes;

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root;
    }

    public List<String> getSupportedFileTypes() {
        return supportedFileTypes;
    }

    public void setSupportedFileTypes(List<String> supportedFileTypes) {
        this.supportedFileTypes = supportedFileTypes;
    }
}