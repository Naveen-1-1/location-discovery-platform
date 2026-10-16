package io.github.naveen11.placemap.livehub;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.AbstractProtocol;
import org.apache.tomcat.util.net.AbstractEndpoint;
import org.apache.tomcat.util.net.SocketProperties;
import org.junit.jupiter.api.Test;

class TomcatConfigTest {

    @Test
    void connectionsGetSmallSocketBuffers() throws Exception {
        var connector = new Connector("HTTP/1.1");

        new TomcatConfig().smallConnectionBuffers().customize(connector);

        SocketProperties socket = socketProperties(connector);
        assertThat(socket.getAppReadBufSize()).isEqualTo(TomcatConfig.READ_BUFFER);
        assertThat(socket.getAppWriteBufSize()).isEqualTo(TomcatConfig.WRITE_BUFFER);
    }

    /** What the endpoint will size each connection's buffers by (its getter is protected). */
    private static SocketProperties socketProperties(Connector connector) throws Exception {
        Method endpoint = AbstractProtocol.class.getDeclaredMethod("getEndpoint");
        endpoint.setAccessible(true);
        return ((AbstractEndpoint<?, ?>) endpoint.invoke(connector.getProtocolHandler())).getSocketProperties();
    }
}
